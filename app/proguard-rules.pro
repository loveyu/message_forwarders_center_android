# Add project specific ProGuard rules here.
-keep class info.loveyu.mfca.server.** { *; }
-keep class info.loveyu.mfca.constants.** { *; }

# SnakeYAML requires java.beans classes on Android
-keep class java.beans.** { *; }
