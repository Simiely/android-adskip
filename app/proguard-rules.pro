# ── 无障碍服务（系统通过 intent filter 绑定，R8 需要显式 keep） ──
-keep public class * extends android.accessibilityservice.AccessibilityService {
    public <methods>;
}

# ── 前台保活服务 ──
-keep class com.simely.adskip.service.KeepAliveService { *; }

# ── 开机广播接收器 ──
-keep class com.simely.adskip.BootReceiver { *; }

# ── 数据模型（org.json 手动反序列化，字段名不能被混淆） ──
-keep class com.simely.adskip.model.** { *; }

# ── 跨组件共享状态单例 ──
-keep class com.simely.adskip.AppState { *; }

# ── EncryptedSharedPreferences ──
-dontwarn androidx.security.**
-keep class androidx.security.crypto.** { *; }

# ── tink / security-crypto 引用了 javax.annotation（Android 不存在） ──
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**

# ── 自定义 View（layout XML 引用，类名不能被混淆） ──
-keep class com.simely.adskip.ui.ChartView { *; }

# ── ViewBinding 生成的类（反射创建） ──
-keep class com.simely.adskip.databinding.** { *; }

# ── MainActivity & 统计相关类 ──
-keep class com.simely.adskip.ui.MainActivity { *; }
-keep class com.simely.adskip.store.StatsStore { *; }

# ── 标准 ProGuard ──
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
