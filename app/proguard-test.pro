# The test APK of the `minified` build type (see proguard-test-keep.pro). R8 runs on it only to
# rewrite its references into the app with the app's mapping: nothing in it is removed or renamed,
# since the test runner finds the test classes and methods by name.
-dontshrink
-dontoptimize
-keep class * { *; }
-keepattributes *
-dontwarn **
