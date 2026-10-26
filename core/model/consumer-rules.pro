-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.swagdroid.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.swagdroid.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
