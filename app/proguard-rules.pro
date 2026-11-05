# 保留模型类
-keep class com.hong.tvbox.core.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# ExoPlayer
-keep class androidx.media3.** { *; }
