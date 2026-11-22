# Smart Clipboard ProGuard / R8 Rules

# Keep Kotlin Serialization & Moshi DTOs
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer Companion;
}

# Keep Retrofit service interfaces & generic signatures
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable
-keep interface com.example.integrations.** { *; }

# Keep Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep Hilt / WorkManager workers
-keep class com.example.core.sync.SyncWorker { *; }
