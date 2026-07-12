# Add project specific ProGuard rules here.
# Keep app classes
-keep class com.imi.smartedge.sidebar.panel.** { *; }
-keepclassmembers class com.imi.smartedge.sidebar.panel.** { *; }

# Keep ViewBinding
-keep class com.imi.smartedge.sidebar.panel.databinding.** { *; }

# Skydoves ColorPickerView
-keep class com.skydoves.colorpickerview.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.GeneratedAppGlideModule
-keepclassmembers public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Audit M7: strip android.util.Log calls in release builds to prevent
# fingerprint / interaction leakage via connected debug tools. Errors that
# must reach Crashlytics/Play Console get filtered upstream; in-app logging
# is purely diagnostic.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
