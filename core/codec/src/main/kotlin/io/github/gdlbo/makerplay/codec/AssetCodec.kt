package io.github.gdlbo.makerplay.codec

import java.io.Closeable
import java.io.InputStream

interface SeekableAssetSource : Closeable {
    val length: Long

    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

interface AssetCodec {
    val id: String
    val cacheTag: String

    fun logicalLength(storedLength: Long): Long

    /** The returned stream owns [source] and closes it on close or failure. */
    fun open(
        source: SeekableAssetSource,
        logicalOffset: Long,
        length: Long,
    ): InputStream
}

class AssetCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AssetCodecRegistry private constructor(
    private val codecs: Map<String, AssetCodec>,
) {
    operator fun get(id: String): AssetCodec? = codecs[id]

    companion object {
        val EMPTY = AssetCodecRegistry(emptyMap())

        fun of(vararg codecs: AssetCodec): AssetCodecRegistry {
            require(codecs.size <= MAX_CODECS) { "Too many asset codecs" }
            val byId = buildMap {
                codecs.forEach { codec ->
                    require(ID_PATTERN.matches(codec.id)) { "Invalid asset codec ID" }
                    require(CACHE_TAG_PATTERN.matches(codec.cacheTag)) { "Invalid asset codec cache tag" }
                    require(put(codec.id, codec) == null) { "Duplicate asset codec ID" }
                }
            }
            return AssetCodecRegistry(byId)
        }

        private const val MAX_CODECS = 32
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val CACHE_TAG_PATTERN = Regex("[a-zA-Z0-9._-]{1,64}")
    }
}