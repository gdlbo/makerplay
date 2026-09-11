package io.github.gdlbo.makerplay.runtime.webview

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Synchronous `crypto` hashing for the Node compatibility layer.
 *
 * The browser exposes only async SubtleCrypto, while games call `createHash(...).digest()`
 * synchronously, so the digest runs on the platform provider (BoringSSL/Conscrypt).
 */
internal object NodeHashSupport {
    private val DIGESTS = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha224" to "SHA-224",
        "sha256" to "SHA-256",
        "sha384" to "SHA-384",
        "sha512" to "SHA-512",
    )

    private val MACS = mapOf(
        "md5" to "HmacMD5",
        "sha1" to "HmacSHA1",
        "sha224" to "HmacSHA224",
        "sha256" to "HmacSHA256",
        "sha384" to "HmacSHA384",
        "sha512" to "HmacSHA512",
    )

    fun digest(algorithm: String, payload: ByteArray): ByteArray = try {
        MessageDigest.getInstance(requireNotNull(DIGESTS[canonical(algorithm)])).digest(payload)
    } catch (error: ProtocolFailure) {
        throw error
    } catch (_: Exception) {
        throw ProtocolFailure("invalid")
    }

    fun hmac(algorithm: String, key: ByteArray, payload: ByteArray): ByteArray = try {
        val name = requireNotNull(MACS[canonical(algorithm)])
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        mac.doFinal(payload)
    } catch (error: ProtocolFailure) {
        throw error
    } catch (_: Exception) {
        throw ProtocolFailure("invalid")
    }

    private fun canonical(algorithm: String): String =
        algorithm.lowercase(Locale.ROOT).replace("-", "")
}
