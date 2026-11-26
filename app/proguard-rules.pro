# R8 keep rules for RexPocket. Everything a library or the platform finds by name; the rest R8
# may rename and remove. AGP's proguard-android-optimize.txt already keeps native method names
# with their classes, enum values()/valueOf, Parcelable CREATORs and manifest components; AndroidX,
# Compose, Glance, coroutines and OkHttp ship their own consumer rules; the tailnet AAR (gomobile)
# keeps go.** and me.leaver.rexpocket.go.**.

# JSch builds every cipher, MAC, KEX, signature and key-pair generator from a class name in its
# config (JSch.getConfig("aes128-ctr") -> Class.forName(...).getDeclaredConstructor().newInstance()).
# Its own com.jcraft.jsch.jce/.juz/.jgss classes and the two this app plugs in by name
# (configureJsch in core's SshTunnel.kt) must keep their names and no-argument constructors.
-keep class com.jcraft.jsch.** { *; }
-keep class me.leaver.rexpocket.core.Ed25519Signature { <init>(); *; }
-keep class me.leaver.rexpocket.core.Ed25519KeyPairGen { <init>(); *; }
# JSch's optional back ends (Bouncy Castle, JNA/Pageant, log4j, slf4j, JUL-on-9, Unix sockets)
# are not on Android's classpath and never configured here.
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**

# net.i2p eddsa: used directly, but EdDSASecurityProvider names its classes as strings, and the
# keys are java.io.Serializable. Small library; keep it whole.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn sun.security.x509.**

# libghostty_jni.so: JNI_OnLoad finds GhosttyVt by name and its static decodePng([B)[I callback;
# the native methods themselves are covered by AGP's default rules.
-keep class me.leaver.rexpocket.terminal.GhosttyVt {
    native <methods>;
    static int[] decodePng(byte[]);
}

# The tailnet library's Go code calls the Kotlin implementations of its Platform interfaces through
# the interface methods, which the AAR's rules keep; keep the implementing classes' overrides too.
-keep class * implements me.leaver.rexpocket.go.tailnet.Platform { *; }
-keep class * implements me.leaver.rexpocket.go.netbird.Platform { *; }

# Names that end up in diagnostics and the problem report (javaClass.simpleName of exceptions
# and of TailnetStage): keep them readable without the mapping file.
-keepnames class * extends java.lang.Throwable
-keepnames class me.leaver.rexpocket.core.TailnetStage { *; }
-keepnames class me.leaver.rexpocket.core.TailnetStage$* { *; }

# Stack traces in the diagnostics log: file and line numbers, retraced with mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
