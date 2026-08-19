package io.github.gdlbo.makerplay.codec

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class MvLzStringSaveCodec(
    private val maxEncodedBytes: Int = DEFAULT_MAX_BYTES,
    private val maxDecodedBytes: Int = DEFAULT_MAX_BYTES,
) : SaveCodec {
    init {
        require(maxEncodedBytes > 0) { "Encoded save limit must be positive" }
        require(maxDecodedBytes > 0) { "Decoded save limit must be positive" }
    }

    override val id: String = ID

    override fun encode(decoded: ByteArray): ByteArray {
        requireWithinLimit(decoded.size, maxDecodedBytes, "Decoded save")
        val text = decodeUtf8(decoded)
        val encoded = LzStringBase64.compress(text, maxEncodedBytes)
        return encoded.toByteArray(StandardCharsets.US_ASCII)
    }

    override fun decode(encoded: ByteArray): ByteArray {
        requireWithinLimit(encoded.size, maxEncodedBytes, "Encoded save")
        val value = decodeAscii(encoded)
        val text = LzStringBase64.decompress(value, maxDecodedBytes)
        val decoded = encodeUtf8(text)
        requireWithinLimit(decoded.size, maxDecodedBytes, "Decoded save")
        if (LzStringBase64.compress(text, maxEncodedBytes) != value) {
            throw SaveCodecException("MV save is not canonical LZ-String Base64")
        }
        return decoded
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw SaveCodecException("Decoded MV save is not valid UTF-8", error)
    }

    private fun encodeUtf8(value: String): ByteArray = try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (error: Exception) {
        throw SaveCodecException("MV save expands to invalid UTF-8", error)
    }

    private fun decodeAscii(bytes: ByteArray): String {
        if (bytes.any { it.toInt() !in 0..0x7f }) {
            throw SaveCodecException("MV save contains non-ASCII Base64 data")
        }
        return String(bytes, StandardCharsets.US_ASCII)
    }

    companion object {
        const val ID = "mv-lz-string-base64"
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
    }
}

private object LzStringBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    fun compress(input: String, maxBytes: Int): String {
        val dictionary = HashMap<String, Int>()
        val pending = HashSet<String>()
        var dictionarySize = 3
        var numberOfBits = 2
        var enlargeIn = 2
        var w = ""
        val output = BitWriter(maxBytes)

        for (character in input) {
            val c = character.toString()
            if (!dictionary.containsKey(c)) {
                dictionary[c] = dictionarySize++
                pending += c
            }
            val wc = w + c
            if (dictionary.containsKey(wc)) {
                w = wc
                continue
            }
            if (writeDictionaryValue(w, pending, dictionary, numberOfBits, output)) {
                enlargeIn--
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numberOfBits
                    numberOfBits++
                }
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits++
            }
            dictionary[wc] = dictionarySize++
            w = c
        }

        if (w.isNotEmpty()) {
            if (writeDictionaryValue(w, pending, dictionary, numberOfBits, output)) {
                enlargeIn--
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numberOfBits
                    numberOfBits++
                }
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits++
            }
        }
        output.write(2, numberOfBits)
        val result = output.finish()
        val padded = result + "===".take((4 - result.length % 4) % 4)
        requireWithinLimit(padded.length, maxBytes, "Encoded save")
        return padded
    }

    fun decompress(input: String, maxChars: Int): String {
        validateBase64(input)
        val reader = BitReader(input)
        val dictionary = ArrayList<String>(minOf(input.length * 2, maxChars + 4)).apply {
            add("")
            add("")
            add("")
        }
        var dictionaryChars = 0L
        var enlargeIn = 4
        var dictionarySize = 4
        var numberOfBits = 3
        val first = when (reader.read(2)) {
            0 -> reader.read(8).toChar().toString()
            1 -> reader.read(16).toChar().toString()
            2 -> return ""
            else -> throw SaveCodecException("MV save has an invalid LZ-String prefix")
        }
        dictionary += first
        dictionaryChars += first.length
        var w = first
        val result = StringBuilder().append(first)
        checkOutputSize(result.length.toLong(), maxChars)

        while (true) {
            var code = reader.read(numberOfBits)
            when (code) {
                0 -> {
                    addDictionaryEntry(
                        dictionary,
                        reader.read(8).toChar().toString(),
                        maxChars,
                        dictionaryChars
                    )
                    dictionaryChars += 1
                    code = dictionarySize++
                    enlargeIn--
                }

                1 -> {
                    addDictionaryEntry(
                        dictionary,
                        reader.read(16).toChar().toString(),
                        maxChars,
                        dictionaryChars
                    )
                    dictionaryChars += 1
                    code = dictionarySize++
                    enlargeIn--
                }

                2 -> return result.toString()
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits++
            }
            val entry = when {
                code < dictionary.size -> dictionary[code]
                code == dictionarySize -> w + w[0]
                else -> throw SaveCodecException("MV save contains an invalid LZ-String code")
            }
            checkOutputSize(result.length.toLong() + entry.length, maxChars)
            result.append(entry)
            val next = w + entry[0]
            addDictionaryEntry(dictionary, next, maxChars, dictionaryChars)
            dictionaryChars += next.length
            dictionarySize++
            enlargeIn--
            w = entry
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits++
            }
        }
    }

    private fun writeDictionaryValue(
        value: String,
        pending: MutableSet<String>,
        dictionary: Map<String, Int>,
        numberOfBits: Int,
        output: BitWriter,
    ): Boolean {
        if (pending.remove(value)) {
            val character = value[0].code
            if (character < 256) {
                output.write(0, numberOfBits)
                output.write(character, 8)
            } else {
                output.write(1, numberOfBits)
                output.write(character, 16)
            }
            return true
        }
        output.write(dictionary.getValue(value), numberOfBits)
        return false
    }

    private fun validateBase64(input: String) {
        if (input.isEmpty() || input.length % 4 != 0) {
            throw SaveCodecException("MV save has invalid Base64 padding")
        }
        val paddingStart = input.indexOf('=').let { if (it == -1) input.length else it }
        val padding = input.length - paddingStart
        if (padding > 3 || input.substring(0, paddingStart)
                .any { ALPHABET.indexOf(it) !in 0..63 } ||
            input.substring(paddingStart).any { it != '=' }
        ) {
            throw SaveCodecException("MV save contains invalid Base64 data")
        }
    }

    private fun addDictionaryEntry(
        dictionary: MutableList<String>,
        value: String,
        maxChars: Int,
        currentChars: Long,
    ) {
        val dictionaryLimit = maxChars.toLong() * 4 + 4
        if (value.length > maxChars || currentChars + value.length > dictionaryLimit) {
            throw SaveCodecException("Decoded save exceeds the configured limit")
        }
        dictionary += value
    }

    private fun checkOutputSize(size: Long, maxChars: Int) {
        if (size > maxChars) throw SaveCodecException("Decoded save exceeds the configured limit")
    }

    private class BitWriter(private val maxBytes: Int) {
        private val output = StringBuilder()
        private var value = 0
        private var position = 0

        fun write(number: Int, bits: Int) {
            var remaining = number
            repeat(bits) {
                value = (value shl 1) or (remaining and 1)
                if (position == 5) {
                    append(value)
                    value = 0
                    position = 0
                } else {
                    position++
                }
                remaining = remaining ushr 1
            }
        }

        fun finish(): String {
            while (true) {
                value = value shl 1
                if (position == 5) {
                    append(value)
                    return output.toString()
                }
                position++
            }
        }

        private fun append(index: Int) {
            if (output.length >= maxBytes) throw SaveCodecException("Encoded save exceeds the configured limit")
            output.append(ALPHABET[index])
        }
    }

    private class BitReader(private val input: String) {
        private var value = ALPHABET.indexOf(input[0])
        private var position = 32
        private var index = 1

        fun read(bits: Int): Int {
            var result = 0
            var power = 1
            repeat(bits) {
                val bit = value and position
                position = position ushr 1
                if (position == 0) {
                    position = 32
                    if (index >= input.length) throw SaveCodecException("MV save is truncated")
                    value = ALPHABET.indexOf(input[index++])
                    if (value < 0) throw SaveCodecException("MV save contains invalid Base64 data")
                }
                if (bit != 0) result = result or power
                power = power shl 1
            }
            return result
        }
    }
}