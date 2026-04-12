# ============================================================
# ProGuard / R8 规则 - MessageForwardersCenter
# ============================================================

# ---------- 调试辅助 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 项目代码（不混淆，保留所有类名和字段名） ----------
-keep class info.loveyu.mfca.** { *; }

# ---------- SnakeYAML Engine ----------
-keep class org.snakeyaml.** { *; }
-dontwarn org.snakeyaml.**

# ---------- Eclipse Paho MQTT ----------
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# ---------- NanoHTTPD ----------
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# NanoHTTPD Method enum accessed via reflection
-keepclassmembers enum fi.iki.elonen.NanoHTTPD$Method {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# NanoHTTPD Response and its subclasses accessed via reflection
-keep class fi.iki.elonen.NanoHTTPD$Response { *; }
-keep class fi.iki.elonen.NanoHTTPD$Response$* { *; }

# ---------- OkHttp ----------
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp internal classes used via reflection
-keep class okhttp3.internal.** { *; }
-dontwarn okhttp3.internal.**

# ---------- Kotlinx Coroutines ----------
-keepnames class kotlinx.coroutines.internal.** { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
