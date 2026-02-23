# DataGrail Consent SDK ProGuard Rules

# Keep public API
-keep public class com.datagrail.consent.DataGrailConsent { *; }
-keep public class com.datagrail.consent.models.** { *; }

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.datagrail.consent.**$$serializer { *; }
-keepclassmembers class com.datagrail.consent.** {
    *** Companion;
}
-keepclasseswithmembers class com.datagrail.consent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
