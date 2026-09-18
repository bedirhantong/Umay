# R8 shrinks and optimizes release builds; these are the rules it can't infer by itself.

# Umay is open source: obfuscating it hides nothing, while readable stack traces in
# crash reports from testers' phones (see diagnostics/CrashLog) are worth a lot.
# Shrinking and optimization still apply.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# TensorFlow Lite (obstacle detection): the native runtime reaches these classes
# through JNI, which R8 can't see, so they'd otherwise be stripped as unused.
-keep class org.tensorflow.lite.** { *; }
# The Task library references the optional GPU delegate, which isn't a dependency.
-dontwarn org.tensorflow.lite.gpu.**

# kotlinx.serialization (maps, field logs) and CameraX ship their own consumer rules.
