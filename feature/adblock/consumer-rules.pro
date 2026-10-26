# Keep @Serializable custom AdBlock domain persistence models under R8
-keep,includedescriptorclasses class com.swagdroid.feature.adblock.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class com.swagdroid.feature.adblock.** { *; }
