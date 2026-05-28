# Capacitor framework
-keep class com.getcapacitor.** { *; }
-keepclassmembers class * {
    @com.getcapacitor.annotation.CapacitorPlugin public *;
    @com.getcapacitor.annotation.PluginMethod public *;
}

# App plugin classes (Capacitor bridge)
-keep class io.github.jukomu.bridge.** { *; }
-keep class io.github.jukomu.data.** { *; }
-keep class io.github.jukomu.service.** { *; }
-keep class io.github.jukomu.MainActivity { *; }

# EncryptedSharedPreferences (androidx.security)
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class * {
    @androidx.security.crypto.EncryptedSharedPreferences <fields>;
}

# Gson serialization (used by OkHttp / JMComic API)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# JMComic library uses java.awt.* only in non-Android (desktop) AwtImageProcessor
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# SLF4J (Android binding)
-keep class uk.uuid.slf4j.** { *; }
-dontwarn org.slf4j.**

# Keep source file names for crash logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
