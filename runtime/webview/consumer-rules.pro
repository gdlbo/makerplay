# JNI entry points + async callback surface for librpgm_native.
# Without these, R8 renames BytesCallback.onSuccess/onError and release crashes with:
#   NoSuchMethodError: no non-static method "...;.onSuccess([B)V"

-keep class io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative {
    public static <methods>;
}
-keep interface io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative$BytesCallback {
    <methods>;
}
-keepclassmembers,allowobfuscation class * implements io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative$BytesCallback {
    void onSuccess(byte[]);
    void onError(java.lang.String);
}
-keep class io.github.gdlbo.makerplay.runtime.webview.nativebridge.NativeRpgMakerAssetCodec {
    <init>(java.lang.String);
    <methods>;
}
-keep class io.github.gdlbo.makerplay.runtime.webview.nativebridge.NativeAssetPrefetch {
    <methods>;
}

# WebView @JavascriptInterface bridges (names must survive minify).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
