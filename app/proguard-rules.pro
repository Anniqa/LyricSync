# Add project specific ProGuard rules here.
-keep class com.lyricsync.app.lyrics.model.** { *; }
-keep class com.lyricsync.app.lyrics.cache.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
