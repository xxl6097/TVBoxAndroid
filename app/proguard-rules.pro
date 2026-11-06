# ===== 本项目 =====
-keep class com.hong.tvbox.core.** { *; }
-keep class com.hong.tvbox.data.** { *; }

# ===== Gson =====
# 泛型签名与注解必须保留，否则 TypeToken<List<Vod>> 反序列化会拿到 LinkedTreeMap
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# ===== OkHttp / Okio =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===== Media3 / ExoPlayer =====
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ===== Leanback =====
-keep class androidx.leanback.** { *; }

# ===== Glide =====
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ===== ZXing（二维码）=====
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ===== Kotlin 协程 =====
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留行号，线上崩溃栈才看得懂
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
