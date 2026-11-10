# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cozycards.**$$serializer { *; }
-keepclassmembers class com.cozycards.** { *** Companion; }
-keepclasseswithmembers class com.cozycards.** { kotlinx.serialization.KSerializer serializer(...); }
# Room
-keep class * extends androidx.room.RoomDatabase
