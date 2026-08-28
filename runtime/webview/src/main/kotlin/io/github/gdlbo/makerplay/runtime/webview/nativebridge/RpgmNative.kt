package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.codec.AssetCodec
import io.github.gdlbo.makerplay.codec.AssetCodecException
import io.github.gdlbo.makerplay.codec.RpgMakerAssetCodec
import io.github.gdlbo.makerplay.codec.SeekableAssetSource
import io.github.gdlbo.makerplay.vfs.GamePath
import io.github.gdlbo.makerplay.vfs.IndexedGameFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
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

/**
 * Asset codec that decrypts the full stored blob via native code, then serves
 * ranged reads from the decoded buffer. Matches [RpgMakerAssetCodec] semantics.
 */
internal class NativeRpgMakerAssetCodec(
    private val hexKey: String,
) : AssetCodec {
    init {
        require(hexKey.length == 32 && hexKey.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "RPG Maker encryption key must contain exactly 32 hexadecimal characters"
        }
    }

    override val id: String = RpgMakerAssetCodec.ID
    override val cacheTag: String = "rpgmaker-native-${UUID.randomUUID()}"

    override fun logicalLength(storedLength: Long): Long {
        if (storedLength < 32L) throw AssetCodecException("Encrypted asset is truncated")
        return storedLength - 16L
    }

    override fun open(
        source: SeekableAssetSource,
        logicalOffset: Long,
        length: Long,
    ): InputStream {
        try {
            val storedLength = source.length
            val logicalLength = logicalLength(storedLength)
            if (logicalOffset < 0L || length < 0L || logicalOffset > logicalLength - length) {
                throw AssetCodecException("Invalid decoded asset range")
            }
            require(storedLength <= Int.MAX_VALUE) { "Encrypted asset is too large" }
            val stored = ByteArray(storedLength.toInt())
            var total = 0
            while (total < stored.size) {
                val read = source.readAt(total.toLong(), stored, total, stored.size - total)
                if (read <= 0) throw AssetCodecException("Encrypted asset cannot be opened")
                total += read
            }
            val plain = RpgmNative.nativeDecodeAsset(hexKey, stored)
            val start = logicalOffset.toInt()
            val end = (logicalOffset + length).toInt()
            return ByteArrayInputStream(plain, start, end - start)
        } catch (error: Exception) {
            if (error is AssetCodecException) throw error
            throw AssetCodecException("Encrypted asset cannot be opened", error)
        } finally {
            runCatching(source::close)
        }
    }
}
