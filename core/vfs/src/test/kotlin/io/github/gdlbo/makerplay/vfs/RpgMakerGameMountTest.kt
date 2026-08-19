package io.github.gdlbo.makerplay.vfs

import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class RpgMakerGameMountTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("makerplay-mount-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun encryptedFixturesMountWithTheirPrivateSystemKey() {
        RpgMakerFixtureGenerator.standardEncryptedAssets().forEach { fixture ->
            root.deleteRecursively()
            root.mkdirs()
            fixture.files.forEach { (path, bytes) ->
                write(path.removePrefix("www/"), bytes)
            }
            GameFileIndex.build(root).write()

            val opened =
                RpgMakerGameMount.open(root).open(fixture.logicalAssetPath) as VfsOpenResult.Found

            opened.stream.use { assertArrayEquals(fixture.plaintext, it.readBytes()) }
        }
    }

    @Test
    fun protectedSystemMetadataRecoversTheStandardKeyFromAnEncryptedPng() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("89504e470d0a1a0a0000000d49484452") + ByteArray(32) { it.toByte() }
        val body = plaintext.copyOf().also { bytes ->
            repeat(16) { index -> bytes[index] = (bytes[index].toInt() xor key[index].toInt()).toByte() }
        }
        write("index.html", "game".toByteArray())
        write("data/System.json", protectedPayload())
        write("img/pictures/Encrypted.png_", RPG_MAKER_HEADER + body)
        GameFileIndex.build(root).write()

        val opened = RpgMakerGameMount.open(root).open("img/pictures/Encrypted.png") as VfsOpenResult.Found

        opened.stream.use { assertArrayEquals(plaintext, it.readBytes()) }
    }

    @Test
    fun malformedOrMissingEncryptionMetadataFailsWithoutLeakingTheValue() {
        val privateValue = "not-a-valid-private-value"
        listOf(
            "{\"hasEncryptedImages\":true,\"encryptionKey\":\"$privateValue\"}",
            "{not-json",
        ).forEach { systemJson ->
            root.deleteRecursively()
            root.mkdirs()
            write("index.html", "game".toByteArray())
            write("data/System.json", systemJson.toByteArray())
            GameFileIndex.build(root).write()

            val error =
                assertThrows(GameMountException::class.java) { RpgMakerGameMount.open(root) }
            assertTrue(!error.message.orEmpty().contains(privateValue))
        }
    }

    @Test
    fun oversizedSystemMetadataIsRejectedBeforeParsing() {
        write("index.html", "game".toByteArray())
        write("data/System.json", ByteArray(2 * 1024 * 1024 + 1) { 'x'.code.toByte() })
        GameFileIndex.build(root).write()

        assertThrows(GameMountException::class.java) { RpgMakerGameMount.open(root) }
    }

    @Test
    fun malformedUtf8SystemMetadataIsRejected() {
        write("index.html", "game".toByteArray())
        write(
            "data/System.json",
            byteArrayOf('{'.code.toByte(), 0xc3.toByte(), 0x28, '}'.code.toByte())
        )
        GameFileIndex.build(root).write()

        assertThrows(GameMountException::class.java) { RpgMakerGameMount.open(root) }
    }

    private fun write(path: String, bytes: ByteArray) {
        File(root, path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun protectedPayload(): ByteArray {
        val container = ByteArray(32)
        "Salted__".toByteArray().copyInto(container)
        return Base64.getEncoder().encode(container)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        val RPG_MAKER_HEADER = byteArrayOf(
            0x52, 0x50, 0x47, 0x4d, 0x56, 0x00, 0x00, 0x00,
            0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
