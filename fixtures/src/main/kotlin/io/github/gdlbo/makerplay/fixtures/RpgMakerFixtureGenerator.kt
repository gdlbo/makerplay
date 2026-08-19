package io.github.gdlbo.makerplay.fixtures

import java.nio.charset.StandardCharsets

object RpgMakerFixtureGenerator {
    data class EncryptedFixture(
        val files: Map<String, ByteArray>,
        val encryptionKey: String,
        val logicalAssetPath: String,
        val plaintext: ByteArray,
        val expectedMimeType: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedFixture

            if (files != other.files) return false
            if (encryptionKey != other.encryptionKey) return false
            if (logicalAssetPath != other.logicalAssetPath) return false
            if (!plaintext.contentEquals(other.plaintext)) return false
            if (expectedMimeType != other.expectedMimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = files.hashCode()
            result = 31 * result + encryptionKey.hashCode()
            result = 31 * result + logicalAssetPath.hashCode()
            result = 31 * result + plaintext.contentHashCode()
            result = 31 * result + expectedMimeType.hashCode()
            return result
        }
    }

    fun mz(title: String = "Legal MZ Fixture"): Map<String, ByteArray> = files(
        "index.html" to "<!doctype html><title>MZ fixture</title>",
        "data/System.json" to """{"gameTitle":"$title","encryptionKey":""}""",
        "js/rmmz_core.js" to "Utils.RPGMAKER_VERSION = \"1.8.0\";",
        "js/plugins.js" to """var ${'$'}plugins = [{"name":"FixturePlugin","status":true},{"name":"Off","status":false}];""",
        "img/system/IconSet.png" to "fixture-image",
    )

    fun mvInWww(title: String = "Legal MV Fixture"): Map<String, ByteArray> = files(
        "Game.rpgproject" to "RPGMV 1.6.2",
        "www/index.html" to "<!doctype html><title>MV fixture</title>",
        "www/data/System.json" to """{"gameTitle":"$title","encryptionKey":""}""",
        "www/js/rpg_core.js" to "Utils.RPGMAKER_VERSION = '1.6.2';",
        "www/js/plugins.js" to "var ${'$'}plugins = [];",
        "www/audio/bgm/Theme.ogg" to "fixture-audio",
    )

    fun standardEncryptedAssets(): List<EncryptedFixture> = listOf(
        encryptedFixture(
            "",
            "img/pictures/Encrypted.png",
            "img/pictures/Encrypted.png_",
            "image/png"
        ),
        encryptedFixture("", "audio/bgm/Encrypted.ogg", "audio/bgm/Encrypted.ogg_", "audio/ogg"),
        encryptedFixture(
            "www/",
            "img/pictures/Encrypted.png",
            "www/img/pictures/Encrypted.rpgmvp",
            "image/png"
        ),
        encryptedFixture(
            "www/",
            "audio/bgm/Encrypted.ogg",
            "www/audio/bgm/Encrypted.rpgmvo",
            "audio/ogg"
        ),
        encryptedFixture(
            "www/",
            "audio/bgm/Encrypted.m4a",
            "www/audio/bgm/Encrypted.rpgmvm",
            "audio/mp4"
        ),
    )

    private fun encryptedFixture(
        root: String,
        logicalAssetPath: String,
        storedAssetPath: String,
        expectedMimeType: String,
    ): EncryptedFixture {
        val plaintext = ByteArray(64) { it.toByte() }.also { bytes ->
            val signature = when {
                logicalAssetPath.endsWith(".png") -> PNG_SIGNATURE
                logicalAssetPath.endsWith(".m4a") -> M4A_SIGNATURE
                else -> OGG_SIGNATURE
            }
            signature.copyInto(bytes)
        }
        val key = hex(ENCRYPTION_KEY)
        val encryptedBody = plaintext.copyOf().also { body ->
            repeat(16) { index ->
                body[index] = (body[index].toInt() xor key[index].toInt()).toByte()
            }
        }
        val engineScript = if (root.isEmpty()) {
            "js/rmmz_core.js" to "Utils.RPGMAKER_VERSION = \"1.8.0\";"
        } else {
            "www/js/rpg_core.js" to "Utils.RPGMAKER_VERSION = '1.6.2';"
        }
        val generated = linkedMapOf(
            "${root}index.html" to "<!doctype html><title>Encrypted fixture</title>".toByteArray(),
            "${root}data/System.json" to (
                    "{\"gameTitle\":\"Encrypted fixture\",\"hasEncryptedImages\":true," +
                            "\"hasEncryptedAudio\":true,\"encryptionKey\":\"$ENCRYPTION_KEY\"}"
                    ).toByteArray(),
            engineScript.first to engineScript.second.toByteArray(),
            storedAssetPath to HEADER + encryptedBody,
        )
        return EncryptedFixture(
            generated,
            ENCRYPTION_KEY,
            logicalAssetPath,
            plaintext,
            expectedMimeType
        )
    }

    private fun files(vararg entries: Pair<String, String>): Map<String, ByteArray> =
        entries.associate { (path, value) -> path to value.toByteArray(StandardCharsets.UTF_8) }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private const val ENCRYPTION_KEY = "000102030405060708090a0b0c0d0e0f"
    private val HEADER = hex("5250474d560000000003010000000000")
    private val PNG_SIGNATURE = hex("89504e470d0a1a0a")
    private val OGG_SIGNATURE = "OggS".toByteArray(StandardCharsets.US_ASCII)
    private val M4A_SIGNATURE = hex("00000018667479704d344120")
}