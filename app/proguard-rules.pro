# Keep Xposed entry point
-keep class com.me.hooklocation.hook.HookEntry { *; }
-keep class de.robv.android.xposed.** { *; }

# Keep all hook classes
-keep class com.me.hooklocation.hook.** { *; }
-keep class com.me.hooklocation.utils.** { *; }
-keep class com.me.hooklocation.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
