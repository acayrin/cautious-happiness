# ML Kit keeps its model metadata via reflection; keep the entry points.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Room generated code is referenced from the @Database annotation.
-keep class * extends androidx.room.RoomDatabase { *; }
