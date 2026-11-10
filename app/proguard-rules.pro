-dontwarn org.slf4j.**

# mpv's native code calls back into these by name (events, properties, logs).
-keep class dev.jdtech.mpv.** { *; }
