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
