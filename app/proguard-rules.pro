# kotlinx.serialization: keep generated serializers for backup models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.patheticgeek.alarmgroups.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.patheticgeek.alarmgroups.** {
    kotlinx.serialization.KSerializer serializer(...);
}
