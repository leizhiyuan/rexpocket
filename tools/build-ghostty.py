#!/usr/bin/env python3
"""Builds app/src/main/jniLibs/arm64-v8a/libghostty_jni.so: the official libghostty-vt, built from
the Ghostty revision pinned in native/ghostty/source.json, plus the JNI layer in native/ghostty/.

Zig and the Ghostty source are downloaded once into native/.build/cache and checked against the
checksums in the manifest. Requires python3, curl and an Android NDK (see android_ndk). No Rust is needed.

With --host it builds the same sources for this computer instead (BUILD/host/libghostty_jni.*),
which is what the JVM unit tests load: they run the real libghostty-vt, not a stand-in. That needs
a JDK for jni.h: JAVA_HOME, or --jdk.

To move to a newer Ghostty: change `revision`, `source_url` and `source_sha256` in the manifest
(and `zig_version` if the revision asks for another one), then rebuild.
"""
import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native/ghostty"
BUILD = ROOT / "native/.build"
OUTPUT = ROOT / "app/src/main/jniLibs/arm64-v8a/libghostty_jni.so"


def run(command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, **kwargs)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, expected, path):
    if path.exists() and sha256(path) == expected:
        return
    part = path.with_suffix(path.suffix + ".part")
    run(["curl", "-fL", "--retry", "2", url, "-o", part])
    if sha256(part) != expected:
        raise RuntimeError(f"checksum mismatch: {url}")
    part.replace(path)


def extract(archive, destination):
    if destination.exists():
        return
    base = destination.parent.resolve()
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            (base / member.name).resolve().relative_to(base)
            if member.issym() or member.islnk():
                link = (base / member.name).parent / member.linkname if member.issym() else base / member.linkname
                link.resolve().relative_to(base)
        tar.extractall(base)


def android_ndk():
    """ANDROID_NDK_HOME, else the newest NDK of the first SDK that has one: the SDK of
    local.properties, then ANDROID_HOME and ANDROID_SDK_ROOT."""
    if os.environ.get("ANDROID_NDK_HOME"):
        return Path(os.environ["ANDROID_NDK_HOME"])
    sdks = []
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdks.append(Path(line.split("=", 1)[1].strip()))
    sdks += [Path(os.environ[v]) for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(v)]
    sdks.append(Path.home() / "Library/Android/sdk")
    for sdk in sdks:
        versions = sorted((sdk / "ndk").glob("*/"), key=lambda p: [int(x) for x in p.name.split(".") if x.isdigit()])
        if versions:
            return versions[-1]
    raise SystemExit("no Android NDK found; install one or set ANDROID_NDK_HOME")


def main():
    parser = argparse.ArgumentParser(description="Build libghostty_jni for Android arm64, or with --host for this computer.")
    parser.add_argument("--host", action="store_true", help="build for this computer, for the JVM unit tests")
    parser.add_argument("--jdk", help="JDK providing jni.h for --host; defaults to JAVA_HOME")
    args = parser.parse_args()
    manifest = json.loads((NATIVE / "source.json").read_text())
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64"}[platform.machine()]
    host = {"Darwin": "macos", "Linux": "linux"}[platform.system()]
    cache = BUILD / "cache"
    cache.mkdir(parents=True, exist_ok=True)

    zig = manifest["zig"][f"{arch}-{host}"]
    zig_archive = cache / Path(zig["tarball"]).name
    source_archive = cache / f"ghostty-{manifest['revision']}.tar.gz"
    download(zig["tarball"], zig["shasum"], zig_archive)
    download(manifest["source_url"], manifest["source_sha256"], source_archive)
    zig_root = cache / zig_archive.name.removesuffix(".tar.xz")
    source_root = cache / f"ghostty-{manifest['revision']}"
    extract(zig_archive, zig_root)
    extract(source_archive, source_root)

    env = os.environ.copy()
    if not env.get("GHOSTTY_ZIG_KEEP_PROXY"):
        # Zig's own HTTP client fetches Ghostty's dependencies, and an HTTP proxy such as a local
        # Surge/Clash one answers it 400 Bad Request. Direct access works; set
        # GHOSTTY_ZIG_KEEP_PROXY=1 if this network only reaches the internet through a proxy.
        for name in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
            env.pop(name, None)
    # Ghostty's Zig dependencies stay with this build, not in the user's shared ~/.cache/zig.
    env.setdefault("ZIG_GLOBAL_CACHE_DIR", str(BUILD / "zig-cache"))

    zig_build = [zig_root / "zig", "build", "-Demit-lib-vt", "-Demit-macos-app=false", "-Demit-xcframework=false",
                 "-Doptimize=ReleaseFast"]
    objects = BUILD / ("obj-host" if args.host else "obj")
    objects.mkdir(parents=True, exist_ok=True)
    sources = [NATIVE / "shim.c", NATIVE / "jni.c"]
    common = ["-c", "-fPIC", "-O2", "-Wall", "-Wextra", "-Wno-c99-designator", "-DGHOSTTY_STATIC"]

    if args.host:
        jdk = Path(args.jdk or os.environ.get("JAVA_HOME") or "")
        include = jdk / "include"
        if not (include / "jni.h").exists():
            raise SystemExit("--host needs a JDK for jni.h: set JAVA_HOME or pass --jdk")
        prefix = BUILD / "prefix-host"
        run([*zig_build, "--prefix", prefix], cwd=source_root, env=env)
        out = BUILD / "host" / ("libghostty_jni.dylib" if host == "macos" else "libghostty_jni.so")
        out.parent.mkdir(exist_ok=True)
        platform_include = include / ("darwin" if host == "macos" else "linux")
        for source in sources:
            run(["cc", *common, "-I", prefix / "include", "-I", include, "-I", platform_include,
                 source, "-o", objects / (source.stem + ".o")], env=env)
        run(["c++", "-shared", *(objects / (s.stem + ".o") for s in sources), prefix / "lib/libghostty-vt.a", "-o", out], env=env)
        print(f"built {out}")
        return

    ndk = android_ndk()
    toolchain = ndk / "toolchains/llvm/prebuilt" / ("darwin-x86_64" if host == "macos" else "linux-x86_64")
    api = manifest["android_api"]
    clang = toolchain / f"bin/aarch64-linux-android{api}-clang"
    env["ANDROID_NDK_HOME"] = str(ndk)
    prefix = BUILD / "prefix-arm64"
    run([*zig_build, f"-Dtarget=aarch64-linux-android.{api}", "--prefix", prefix], cwd=source_root, env=env)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    for source in sources:
        run([clang, *common, "-I", prefix / "include", source, "-o", objects / (source.stem + ".o")], env=env)
    # Linked with the C++ driver because libghostty-vt carries C++ (SIMD) code; the C++ runtime is
    # linked in statically so the APK needs no libc++_shared.so. 16 KB page alignment: Android 15
    # devices with 16 KB pages refuse a library whose LOAD segments are aligned to less.
    run([f"{clang}++", "-shared", *(objects / (s.stem + ".o") for s in sources),
         prefix / "lib/libghostty-vt.a",
         "-static-libstdc++", "-Wl,-z,max-page-size=16384", "-Wl,--gc-sections", "-Wl,-s",
         "-o", OUTPUT], env=env)
    print(f"built {OUTPUT}")


if __name__ == "__main__":
    main()
