# Add project specific ProGuard rules here.
# NOTE: there is deliberately NO blanket `-keep` for the app package.
# The old `-keep class com.imi.smartedge.sidebar.panel.** { *; }` kept
# every app class and member, so minify/shrinkResources barely shrank or
# obfuscated any app code. Nothing in the app needs it:
#  - Activities/Services/Receivers/Providers are kept by R8's default
#    rules for manifest-referenced components.
#  - Reflection only touches framework classes (android.os.SystemProperties,
#    AppOpsManager.checkOpNoThrow) — never app classes.
#  - JSON is parsed with org.json by hand (no reflection-based serializer).
#  - No Parcelable/Serializable types.
# Keep only what has a real reason below.

# Shizuku's provider is manifest-referenced; keep it explicit so a
# future manifest refactor can't silently drop it.
-keep class rikka.shizuku.ShizukuProvider { *; }

# Strip android.util.Log calls in release builds to prevent
# fingerprint / interaction leakage via connected debug tools. In-app
# logging is purely diagnostic: there is no crash-reporting library in
# this app (no Crashlytics / Play Console integration — it is a
# GitHub/F-Droid app), so release logs go nowhere and are stripped.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
