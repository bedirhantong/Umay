# R8 shrinks and optimizes release builds; these are the rules it can't infer by itself.

# Umay is open source: obfuscating it hides nothing, while readable stack traces in
# crash reports from testers' phones (see diagnostics/CrashLog) are worth a lot.
# Shrinking and optimization still apply.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization (maps, field logs) and CameraX ship their own consumer rules.
