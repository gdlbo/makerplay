package io.github.gdlbo.makerplay.codec

class OpaquePluginSaveCodec(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : SaveCodec {
    init {
        require(maxBytes > 0) { "Opaque save limit must be positive" }
    }

    override val id: String = ID

    override fun encode(decoded: ByteArray): ByteArray {
        requireWithinLimit(decoded.size, maxBytes, "Opaque save")
        return decoded.copyOf()
    }

    override fun decode(encoded: ByteArray): ByteArray {
        requireWithinLimit(encoded.size, maxBytes, "Opaque save")
        return encoded.copyOf()
    }

    companion object {
        const val ID = "opaque-plugin"
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
    }
}