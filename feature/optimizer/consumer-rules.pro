# Keep @Serializable PortableSwagProfile & UAD catalog JSON models
-keep,includedescriptorclasses class com.swagdroid.feature.optimizer.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class com.swagdroid.feature.optimizer.** { *; }
