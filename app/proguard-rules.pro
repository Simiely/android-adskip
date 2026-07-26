<<<<<<< HEAD
# ── 保留所有 AdSkip 代码（避免 R8 误删） ──
-keep class com.simely.adskip.** { *; }

# ── 无障碍服务 ──
-keep public class * extends android.accessibilityservice.AccessibilityService {
    public <methods>;
}

# ── 数据模型字段名不被混淆 ──
-keepclassmembers class com.simely.adskip.model.** { <fields>; }

# ── EncryptedSharedPreferences ──
-dontwarn androidx.security.**
-keep class androidx.security.crypto.** { *; }

# ── tink ──
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**

# ── 枚举（R8 会默认优化掉 values/valueOf） ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable ──
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Material Components ──
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── AndroidX ──
-keep class androidx.appcompat.widget.** { *; }
-keep class androidx.core.** { *; }

# ── 标准 ProGuard ──
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
=======
# 保留默认混淆规则（当前未开启 minify，仅占位）
-keepattributes *Annotation*
-dontwarn androidx.security.**
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
