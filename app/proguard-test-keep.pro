# Added to the app's own rules for the `minified` build type only, the one device-test.yml runs a
# set of the instrumented tests against. The test APK calls straight into the app's classes, and
# into the AndroidX and Kotlin code the app ships (Compose's test rule reaches deep into
# compose-ui), so whatever the tests call must still be there: it is all kept from being removed,
# but still renamed (the test APK is rewritten with the app's mapping), so a name looked up by a
# string still breaks here as it would in rexpocket.apk.
#
# Not kept here, so that their own rules in proguard-rules.pro are what is tested: the classes JSch
# makes by name (Ed25519Signature, Ed25519KeyPairGen: their no-argument constructors) and GhosttyVt,
# which libghostty's JNI_OnLoad finds by name with its decodePng callback.
-keep,allowobfuscation class !me.leaver.rexpocket.core.Ed25519Signature,!me.leaver.rexpocket.core.Ed25519KeyPairGen,!me.leaver.rexpocket.terminal.GhosttyVt,me.leaver.rexpocket.** { *; }
-keep,allowobfuscation class kotlin.** { *; }
-keep,allowobfuscation class kotlinx.** { *; }
-keep,allowobfuscation class androidx.** { *; }
-keep,allowobfuscation class okhttp3.** { *; }
-keep,allowobfuscation class okio.** { *; }
