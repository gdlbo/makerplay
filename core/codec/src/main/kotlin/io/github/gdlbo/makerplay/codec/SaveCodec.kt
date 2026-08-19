package io.github.gdlbo.makerplay.codec

interface SaveCodec {
    val id: String

    fun encode(decoded: ByteArray): ByteArray

    fun decode(encoded: ByteArray): ByteArray
}

class SaveCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun requireWithinLimit(size: Int, limit: Int, description: String) {
    if (size > limit) throw SaveCodecException("$description exceeds the configured limit")
}