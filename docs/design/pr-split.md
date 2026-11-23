# Splitting the work into two pull requests

The work on this branch goes upstream as two pull requests. Upstream matches Rex, and Rex only does
Tailscale, so the Netbird backend is its own, opt-in change on top:

| | Branch | Title | On top of |
| --- | --- | --- | --- |
| PR A | `split/ui` | UI and Rex parity | the merge base with `origin/master` |
| PR B | `split/netbird` | Netbird as an opt-in tailnet backend | `split/ui` |

Both branches are local and rebuilt from the tip by [tools/split/split-prs.sh](../../tools/split/split-prs.sh),
so the split can be made again after more commits land. Nothing is pushed by the script.

## Running it

```sh
# Go, ANDROID_HOME, ANDROID_NDK_HOME and JAVA_HOME as for tools/build-tailnet.sh
tools/split/split-prs.sh                 # split HEAD against origin/master
tools/split/split-prs.sh --tip origin/ccr-30ee0e05-w78lcg
tools/split/split-prs.sh --aar my.aar    # use this Tailscale-only tailnet.aar instead of building one
tools/split/split-prs.sh --keep          # keep the temporary worktree for a look
tools/split/split-prs.sh --refresh-patch # rewrite netbird.patch from split/ui, see below
```

It works in a temporary worktree and with git plumbing, so the working tree is not touched. It:

1. takes the tip's tree, less `tools/split/` and this note: that is **split/netbird's tree**;
2. takes that, less the paths in [netbird-paths.txt](../../tools/split/netbird-paths.txt), and
   reverts [netbird.patch](../../tools/split/netbird.patch) (the Netbird hunks of shared files) with
   `git apply -R --3way`;
3. writes `tailnet/go.mod` and `go.sum` without the Netbird module: drops its requirement and the
   `replace` lines that came with it, runs `go mod tidy`, and pins every module a package of the
   build or the tests comes from to the tip's version, so the Tailscale node is built from the
   same code;
4. builds `app/libs/tailnet.aar` from that `tailnet/` with `tools/build-tailnet.sh` (Tailscale only,
   no `me.leaver.rexpocket.go.netbird` classes) and checks it has no Netbird classes. That is
   **split/ui's tree**;
5. fails if `git grep -i netbird` finds anything on split/ui beyond
   [grep-allow.txt](../../tools/split/grep-allow.txt), which is empty;
6. commits split/ui on the merge base, one commit per line of
   [ui-topics.txt](../../tools/split/ui-topics.txt), and split/netbird on split/ui, one per line of
   [netbird-topics.txt](../../tools/split/netbird-topics.txt). A file goes to the first topic whose
   pathspecs match it. The commits carry the tip's author and dates, so the same tip and the same
   library give the same commits;
7. checks the invariant below and moves the two branches.

The library is the one input that is not reproducible: two gomobile builds of the same sources
differ by a few bytes (64 B of `libgojni.so` between two builds here). The script caches it under the git directory,
named by the `tailnet/` tree and the build script it came from, so a rerun with the same Go
sources reuses it and gives the same commits. `--aar` hands in one built elsewhere.

### The invariant

**split/netbird's tree is the tip's tree, less `tools/split/` and `docs/design/pr-split.md`.** The
script checks it and stops otherwise. Those two are the split's own tooling: they mention Netbird
(so they cannot be in A) and are no part of the Netbird feature (so they are not in B either). There
is no other difference: B's `tailnet.aar`, `go.mod` and `go.sum` are the tip's.

### When the patch stops applying

netbird.patch is the difference between split/ui and the tip in the shared files. A later commit
that changes the lines around a Netbird hunk makes `git apply --3way` leave conflict markers; one
that adds Netbird to a shared file makes the grep check fail; a new Netbird-only file needs a line in
netbird-paths.txt. The script then keeps its worktree and says where it is. To fix it:

1. In that worktree, resolve the conflicts so the tree is the tip without Netbird (or take the new
   Netbird lines out), and `git add -A`.
2. `git -C <worktree> commit -m wip` and `git branch -f split/ui <that commit>`.
3. `tools/split/split-prs.sh --refresh-patch` rewrites netbird.patch from split/ui and the tip.
4. Commit the new patch on the branch, remove the worktree (`git worktree remove --force <it>`),
   and run the script again.

## What goes where

### Only in PR B (netbird-paths.txt)

- `tailnet/netbird/`: the embedded Netbird node behind the `netbird` build tag, and `unavailable.go`,
  the stub the default build has.
- `app/…/net/NetbirdBox.kt`: the node's lifecycle on the phone.
- `tools/ci/netbird-e2e/` and `.github/workflows/netbird-e2e.yml`: the end-to-end test against a
  self-hosted Netbird.
- `docs/design/tailnet-backends.md`: the Netbird design note.

### Shared files, with Netbird hunks (netbird.patch, 35 files)

| Area | Files | What PR B adds |
| --- | --- | --- |
| Backend choice | `core/…/TailnetBackend.kt`, `app/…/net/TailnetNode.kt` | the `Netbird` entry (no sign-in step) and its one `TailnetEntry` in `TailnetNodes.of`; `NetbirdConfig` |
| Settings | `data/HostStore.kt`, `ui/HomeScreen.kt`, `ui/RexApp.kt`, `ui/RexViewModel.kt` | the management server setting, the setup key, the Netbird fields and hint in the Tailnet dialog, `joinTailnet`'s two Netbird arguments, the config of a Netbird node |
| Tailnet details | `core/…/TailnetState.kt`, `TailnetPath.kt`, `ui/TailnetUi.kt` | `PeerLink.Relay`, a peer's `latencyMs`, `TailnetPath.Relay` and the ping's `relayServer` |
| Names | `core/…/HostAddress.kt` | `.netbird.cloud` is on the tailnet |
| Comments | `TailnetStage.kt`, `TailnetBox.kt`, `Common.kt` | the words "Netbird" where a comment names it |
| Strings | `values/strings.xml`, `values-zh/strings.xml`, `values*/tailnet_strings.xml` | `netbird_*`, `tailnet_path_relay`, `tailnet_link_relay` (zh-rTW has only the last; it falls back to zh) |
| Tests | the core tailnet tests, `HostAddressTest`, `TailnetHarness`, `ScreenshotsTest`, `TailnetNodeInstrumentedTest` | the Netbird cases, the Netbird sample and screenshot, the instrumented Netbird load and start checks |
| Build | `app/build.gradle.kts`, `tools/build-tailnet.sh`, `.gitignore` | `-Prexpocket.netbird=true`, `REXPOCKET_NETBIRD=1` and `tailnet-netbird.aar` |
| CI | `android-apk.yml`, `device-test.yml` | the Netbird APK; device tests on the Netbird build |
| Docs | the three READMEs, `docs/design/tailnet-status.md` | Netbird in the features, setup, how-it-works, build and acknowledgements; the Netbird state fields |

`tools/build-tailnet.sh` on split/ui is the merge base's: everything the tip changed in it is for
Netbird (the tag, the second package, `-checklinkname=0`).

### Generated for PR A

- `tailnet/go.mod`, `tailnet/go.sum`: without Netbird, they differ from the merge base's by one
  indirect requirement.
- `app/libs/tailnet.aar`: Tailscale only. The tip's default library also carries the Netbird stub's
  Java package (`me.leaver.rexpocket.go.netbird`, four classes) and its native code, about 14 KB.

### The seam

Three small changes on the branch make B an addition instead of edits all over shared code:

- `TailnetNodes` is a registry of `TailnetEntry(backend, node, available)`, filled in one place,
  `TailnetNodes.of`; the view model asks it for a backend's node, the offered backends and the nodes
  to stop (`TailnetNodesTest`).
- `TailnetBackend` carries its display name and whether its node signs in, so the screens and
  `tailnetSteps` do not test for Netbird (`TailnetBackendTest`).
- The Tailnet dialog shows the backend choice only when there is more than one, so PR A has no
  one-chip row, and the default build of the tip has none either.

`docs/design/tailnet-status.md` was split out of the Netbird note for the same reason: how the
node's state reaches the screen is the same for every backend.

## The pull requests

The repository has no pull request template, so both descriptions use Summary, Changes, Testing and
Notes. Review and merge A first; open B against A's branch (or against `master` once A is in).

### PR A: UI and Rex parity

> **Summary.** Brings the app up to the Rex desktop: the key bar, terminal controls, Activity, the
> command palette and menu, closed tabs and the clipboard match what Rex does, and the tailnet's
> state is on the home screen. Tailscale only, as Rex is.
>
> **Changes.** One commit per topic: the Rex protocol reference; key bar customization, groups and
> presets; terminal controls and gestures, full screen and keyboard insets; Activity, the command
> palette and the ⋮ menu; closed tabs, the clipboard, copy output, Clear/Reset and the real-Rex
> matching; tailnet status (the node's state, devices and each step to a host) with a rebuilt
> `tailnet.aar`; home, first run, icons, large text and updates; the test harnesses; CI
> (`android-apk` with the kept debug key, `device-test`, `rex-integration`, `rex-real`).
>
> **Testing.** `./gradlew :core:test :app:testDebugUnitTest`, `go test ./...` in `tailnet/`,
> `:app:assembleDebug`, and the four workflows.
>
> **Notes.** The commits group the change by topic for review; only the last one builds.

### PR B: Netbird as an opt-in tailnet backend

> **Summary.** Adds Netbird as a second tailnet backend, built in only on request: the default APK
> stays Tailscale only, and `-Prexpocket.netbird=true` with `REXPOCKET_NETBIRD=1
> tools/build-tailnet.sh` builds one with Netbird (about 30 MB more).
>
> **Changes.** The embedded Netbird node in `tailnet/netbird` behind the `netbird` tag, with a stub
> for the default build; `NetbirdBox` registered in `TailnetNodes.of`; the Netbird choice, management
> server and setup key in the Tailnet dialog; its state fields (relay link, latency, relay path); the
> end-to-end workflow against a self-hosted Netbird; device tests on the Netbird build; docs.
>
> **Testing.** As for A, plus `go test -tags netbird ./...` and the `Netbird e2e` workflow.
>
> **Notes.** The default build gains the stub's Java package and native code, and the Netbird
> dialog code and strings: about 19 KB of APK.

## What to check

On split/ui:

- `git grep -i netbird split/ui` finds nothing; `unzip -l app/libs/tailnet.aar | grep -i netbird`
  neither.
- The tests and assemble tasks above pass, and the debug APK is the tip's default less about 19 KB
  (see Verification).
- Settings › Tailnet shows no backend choice and joins Tailscale as before.
- `device-test` on API 34: the three clipboard tests (`EmptyHomeTest.theCommandIsCopiedWithATap`,
  `FollowInputTest.pasteBringsThePaneToTheNewestOutput`, `LaunchTest.aProgramsCopyInThePaneOnScreenGoesOnTheClipboard`)
  used to time out when the system's copied overlay kept the focus, since only the app in focus may
  read the clipboard. They now read it holding `READ_CLIPBOARD_IN_BACKGROUND`, adopted from the shell
  (`clipboardInBackground` in TermHarness.kt), so the read, the app's Paste included, no longer
  waits on the focus; they pass on every run.

On split/netbird:

- `git diff split/netbird <tip>` lists only `tools/split/` and this note.
- `go test ./...` and `go test -tags netbird ./...` pass; the Netbird workflows pass.
- With `-Prexpocket.netbird=true`, Settings › Tailnet offers Tailscale and Netbird.

## Verification

Measured when this tooling was added, on the tip of that day, on a computer without
libghostty (`TerminalSessionTest` needs it and is left out; `buildGhostty` is skipped, so the APKs
have no libghostty either):

| | split/ui (A) | split/netbird (B) = tip |
| --- | --- | --- |
| `:core:test` | 273 passed, 1 skipped | 277 passed, 1 skipped |
| `:app:testDebugUnitTest` | 243 passed, 10 skipped | 244 passed, 10 skipped |
| `go test ./...` in `tailnet/` | 10 passed | 11 passed |
| `go test -tags netbird ./...` | - | 27 passed |
| `:app:assembleDebug`, `:app:assembleDebugAndroidTest` | built | built |
| debug APK (default) | 32,490,726 B | 32,509,462 B |
| `libgojni.so` | 21,213,896 B (21,213,832 B in the build the APK was measured with) | 21,228,296 B |
| `git grep -i netbird` | nothing | - |

A has four core tests fewer (the Netbird name, its relay path twice and its ping) and one
screenshot fewer (the Netbird details); its tailnet tests lack the stub's. The APKs differ by
18,736 B: the stub's native code (about 14.4 KB) and the Netbird classes, dialog code and strings.

