package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.vfs.GameFileSystem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Async native read/decode results staged for the synchronous WebView intercept path.
 * Android's shouldInterceptRequest cannot block on callbacks, so workers fill this cache ahead.
 */
internal class NativeAssetPrefetch {
    private val plaintext = ConcurrentHashMap<String, ByteArray>()
    private val completed = AtomicInteger(0)

    fun get(path: String): ByteArray? = plaintext[path]

    fun prefetchPlaintext(fileSystem: GameFileSystem, logicalPaths: List<String>) {
        if (!RpgmNative.isAvailable()) return
        logicalPaths.forEach { logical ->
            val absolute = fileSystem.absoluteFile(logical)?.absolutePath ?: return@forEach
            RpgmNative.nativeReadFileAsync(
                absolute,
                object : RpgmNative.BytesCallback {
                    override fun onSuccess(bytes: ByteArray) {
                        plaintext[logical] = bytes
                        completed.incrementAndGet()
                        android.util.Log.i(
                            "MakerPlay",
                            "native.io op=prefetch-read path=$logical bytes=${bytes.size}",
                        )
                    }

                    override fun onError(message: String) {
                        android.util.Log.w(
                            "MakerPlay",
                            "native.io op=prefetch-read-failed path=$logical error=$message",
                        )
                    }
                },
            )
        }
    }

    fun prefetchEncrypted(
        hexKey: String,
        logicalPath: String,
        storedBytes: ByteArray,
    ) {
        if (!RpgmNative.isAvailable()) return
        RpgmNative.nativeDecodeAssetAsync(
            hexKey,
            storedBytes,
            object : RpgmNative.BytesCallback {
                override fun onSuccess(bytes: ByteArray) {
                    plaintext[logicalPath] = bytes
                    completed.incrementAndGet()
                    android.util.Log.i(
                        "MakerPlay",
                        "native.io op=prefetch-decode path=$logicalPath bytes=${bytes.size}",
                    )
                }

                override fun onError(message: String) {
                    android.util.Log.w(
                        "MakerPlay",
                        "native.io op=prefetch-decode-failed path=$logicalPath error=$message",
                    )
                }
            },
        )
    }

    fun completedCount(): Int = completed.get()
}
