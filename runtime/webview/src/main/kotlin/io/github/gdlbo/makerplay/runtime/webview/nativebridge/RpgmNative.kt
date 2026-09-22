package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.codec.AssetCodec
import io.github.gdlbo.makerplay.codec.RpgMakerAssetCodec
import io.github.gdlbo.makerplay.codec.SeekableAssetSource
import io.github.gdlbo.makerplay.vfs.GamePath
import io.github.gdlbo.makerplay.vfs.IndexedGameFile
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object RpgmNative {
    private val loadAttempted = AtomicBoolean(false)
    @Volatile
    private var available: Boolean = false
    @Volatile
    private var forceDisabled: Boolean = false

    /** Test/ADB switch: create `files/disable-rpgm-native` to force JVM IO for A/B metrics. */
    fun setForceDisabled(disabled: Boolean) {
        forceDisabled = disabled
    }

    fun isAvailable(): Boolean {
        if (forceDisabled) return false
        if (loadAttempted.compareAndSet(false, true)) {
            available = runCatching {
                System.loadLibrary("rpgm_native")
                true
            }.getOrDefault(false)
        }
        return available && !forceDisabled
    }

    fun codecFactoryOrDefault(): (String) -> AssetCodec = { hexKey ->
        if (isAvailable()) {
            NativeRpgMakerAssetCodec(hexKey)
        } else {
            RpgMakerAssetCodec.fromHexKey(hexKey)
        }
    }

    fun entryScannerOrNull(root: File): (() -> List<IndexedGameFile>?)? {
        if (!isAvailable()) return null
        return {
            runCatching { listIndexedFiles(root) }.getOrNull()
        }
    }

    private fun listIndexedFiles(root: File): List<IndexedGameFile> {
        val encoded = nativeListFiles(root.absolutePath)
        return encoded.map { row ->
            val parts = row.split('\u0001')
            require(parts.size == 3) { "Invalid native index row" }
            IndexedGameFile(
                path = GamePath.parse(parts[0]),
                size = parts[1].toLong(),
                lastModifiedMillis = parts[2].toLong(),
            )
        }
    }

    @JvmStatic
    external fun nativeDecodeAsset(hexKey: String, storedBytes: ByteArray): ByteArray

    /** Non-blocking XOR decrypt on the native worker pool. */
    @JvmStatic
    external fun nativeDecodeAssetAsync(hexKey: String, storedBytes: ByteArray, callback: BytesCallback)

    interface BytesCallback {
        fun onSuccess(bytes: ByteArray)
        fun onError(message: String)
    }

    @JvmStatic
    external fun nativeReadFile(path: String): ByteArray

    /** Non-blocking: completes [callback] on a native worker thread. */
    @JvmStatic
    external fun nativeReadFileAsync(path: String, callback: BytesCallback)

    @JvmStatic
    external fun nativeListFiles(rootPath: String): Array<String>
}

/** Compatibility wrapper using bounded streaming decryption, including byte-range reads. */
internal class NativeRpgMakerAssetCodec(hexKey: String) : AssetCodec {
    private val streaming = RpgMakerAssetCodec.fromHexKey(hexKey)
    override val id: String get() = streaming.id
    override val cacheTag: String get() = streaming.cacheTag
    override fun logicalLength(storedLength: Long): Long = streaming.logicalLength(storedLength)
    override fun open(source: SeekableAssetSource, logicalOffset: Long, length: Long): InputStream =
        streaming.open(source, logicalOffset, length)
}
