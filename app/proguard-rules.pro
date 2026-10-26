# =============================================================================
# SwagDroid Production R8 / ProGuard Rules
# =============================================================================

# Preserve line numbers for offline SwagCrashGuard diagnostics without leaking local paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku & Sui (including reflective Shizuku.newProcess call)
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-dontwarn rikka.sui.**

# libsu Root Shell & IPC
-keep class com.topjohnwu.superuser.** { *; }

# kotlinx.serialization (polymorphic StateVault operations & Optimizer portable JSON profiles)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swagdroid.**$$serializer { *; }
-keepclassmembers class com.swagdroid.** {
    *** Companion;
}
-keepclasseswithmembers class com.swagdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.swagdroid.** { *; }

# Room entities & generated database implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
