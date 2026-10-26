# Keep @Serializable models & polymorphic VaultOperation serializers under R8
-keep,includedescriptorclasses class com.swagdroid.core.model.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class com.swagdroid.core.model.** { *; }
-keepclassmembers class com.swagdroid.core.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
