# Release builds (R8 is off in debug). The Anthropic Java SDK maps JSON to model classes with Jackson reflection.
-keep class com.anthropic.** { *; }
-keepclassmembers class com.anthropic.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations
-dontwarn com.anthropic.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# kotlinx.serialization (adapter specs, fixtures)
-keepattributes *Annotation*
-keepclassmembers class com.readerapp.** { *** Companion; }
-keepclasseswithmembers class com.readerapp.** { kotlinx.serialization.KSerializer serializer(...); }
