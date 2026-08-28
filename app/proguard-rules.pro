# Runtime JavaScript bridges must opt in with explicit @JavascriptInterface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- librpgm_native (async JNI callbacks) -------------------------------------
# R8 must not rename BytesCallback.onSuccess/onError or release crashes with:
#   NoSuchMethodError: no non-static method "...;.onSuccess([B)V"
-keep class io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative {
    public static <methods>;
}
-keep interface io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative$BytesCallback {
    <methods>;
}
-keepclassmembers class * implements io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative$BytesCallback {
    void onSuccess(byte[]);
    void onError(java.lang.String);
}
-keep class io.github.gdlbo.makerplay.runtime.webview.nativebridge.** {
    <methods>;
}

# --- wolf_native JNI ----------------------------------------------------------
-keep class io.github.gdlbo.makerplay.runtime.wolf.WolfNativeJni {
    <methods>;
}
