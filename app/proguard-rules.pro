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

# ===== 爬虫站源（type=3）=====
# 基类由 jar 继承，名字必须要保住；反射调用的方法也要保住，否则 R8 会改签名
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider { *; }
-keepclassmembers class * extends com.github.catvod.crawler.Spider {
    public <init>(...);
    public void init(android.content.Context, java.lang.String);
    public java.lang.String homeContent(boolean);
    public java.lang.String homeVideoContent();
    public java.lang.String categoryContent(java.lang.String, java.lang.String, boolean, java.util.HashMap);
    public java.lang.String detailContent(java.util.List);
    public java.lang.String searchContent(java.lang.String, boolean);
    public java.lang.String searchContent(java.lang.String, boolean, java.lang.String);
    public java.lang.String playerContent(java.lang.String, java.lang.String, java.util.List);
    public boolean isVideoFormat(java.lang.String);
    public boolean manualVideoCheck();
    public void destroy();
}

# ===== Kotlin 协程 =====
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留行号，线上崩溃栈才看得懂
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
