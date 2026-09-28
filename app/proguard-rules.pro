# kotlinx.serialization: keep generated serializers for backup models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.geek.alarmy.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.geek.alarmy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
