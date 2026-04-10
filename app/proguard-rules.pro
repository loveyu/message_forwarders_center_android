# Add project specific ProGuard rules here.
-keep class info.loveyu.mfca.server.** { *; }
-keep class info.loveyu.mfca.constants.** { *; }

# SnakeYAML references java.beans classes which don't exist on Android
# These are only used in BeanAccess.DEFAULT mode; using -dontwarn lets R8 proceed
-dontwarn java.beans.**
