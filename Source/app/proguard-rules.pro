# kotlinx.serialization: keep generated serializers of DroidVM model classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class tech.astrix.droidvm.**$$serializer { *; }
-keepclassmembers class tech.astrix.droidvm.** {
    *** Companion;
}
-keepclasseswithmembers class tech.astrix.droidvm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
