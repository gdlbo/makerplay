package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmBootPrefetchPlans
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RpgmBootPrefetchPlansTest {
    private lateinit var root: File
    private lateinit var fileSystem: GameFileSystem

    @Before
    fun setUp() {
        root = Files.createTempDirectory("makerplay-prefetch-plan").toFile()
        write("data/System.json", """{"encryptionKey":"0123456789abcdef0123456789abcdef","hasEncryptedImages":true,"startMapId":42,"title1Name":"Forest","titleBgm":{"name":"Theme1"}}""")
        write("data/MapInfos.json", "[]")
        write("data/CommonEvents.json", "[]")
        write("data/Map001.json", "{}")
        write("data/Map042.json", "{}")
        write("img/titles1/Forest.png_", byteArrayOf(1, 2))
        write("data/Actors.json", "[]")
        write("img/system/Window.png_", byteArrayOf(1, 2, 3, 4))
        write("img/tilesets/World.png_", byteArrayOf(5, 6, 7, 8, 9))
        write("img/characters/Actor1.png_", byteArrayOf(9, 8, 7))
        write("img/faces/Actor1.png_", byteArrayOf(2, 2))
        write("audio/bgm/Theme1.ogg_", byteArrayOf(3, 3, 3))
        fileSystem = GameFileSystem(GameFileIndex.build(root))
    }

    @Test
    fun `plaintext plan includes actual start map without unrelated maps`() {
        val paths = RpgmBootPrefetchPlans.plaintextHotPaths(fileSystem)
        assertTrue(paths.contains("data/System.json"))
        assertTrue(paths.contains("data/MapInfos.json"))
        assertTrue(paths.contains("data/Map042.json"))
        assertFalse(paths.contains("data/Map001.json"))
        assertTrue(paths.size >= 5)
    }

    @Test
    fun `media plan includes title dependencies without speculative folders`() {
        val key = RpgmBootPrefetchPlans.readEncryptionKey(fileSystem)
        assertTrue(key == "0123456789abcdef0123456789abcdef")
        val media = RpgmBootPrefetchPlans.encryptedLogicalPaths(fileSystem)
        assertFalse(media.any { it.startsWith("img/tilesets/") })
        assertFalse(media.any { it.startsWith("img/faces/") })
        assertFalse(media.any { it.startsWith("img/characters/") })
        assertTrue("img/titles1/Forest.png" in media)
        assertTrue("audio/bgm/Theme1.ogg" in media)
        assertTrue(media.any { it.startsWith("img/system/") })
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    private fun write(relative: String, text: String) {
        write(relative, text.toByteArray(Charsets.UTF_8))
    }

    private fun write(relative: String, bytes: ByteArray) {
        val file = File(root, relative)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
    }
}
