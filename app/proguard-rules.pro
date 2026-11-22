# Preserve source file & line numbers for in-app DiagnosticsLogger crash traces
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if class *
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}
-if class * {
    static **$Companion Companion;
}
-keep class <1>$$serializer { *; }
-keepclassmembers class com.mflgameday.live.** {
    *** Companion;
}
-keepclasseswithmembers class com.mflgameday.live.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.mflgameday.live.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil image loading
-dontwarn coil.**
