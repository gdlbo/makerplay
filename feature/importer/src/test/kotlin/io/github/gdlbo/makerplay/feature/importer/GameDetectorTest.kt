package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import io.github.gdlbo.makerplay.model.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class GameDetectorTest {

    @Test
    fun `detects wolf native deployment`() {
        val entries = listOf(
            entry("Game.exe", byteArrayOf(0x4d, 0x5a)),
            entry("Game.dat", ByteArray(9) { if (it == 8) 0x55 else 0 }),
            entry("CommonEvent.dat", byteArrayOf(1)),
            entry("Data/MapData/MapTree.dat", byteArrayOf(1)),
        )

        val detected = GameDetector().detect(entries, "Wolf sample")

        assertEquals(GameEngine.WOLF, detected.engine)
        assertEquals("Wolf sample", detected.title)
        assertEquals("WOLF RPG v3", detected.engineVersion)
    }

    private fun entry(path: String, bytes: ByteArray) = ImportEntry(
        relativePath = path,
        size = bytes.size.toLong(),
        open = { ByteArrayInputStream(bytes) },
    )
    private val detector = GameDetector()

    @Test
    fun detectsMZVersionTitleAndEnabledPlugins() {
        val game = detector.detect(RpgMakerFixtureGenerator.mz().asImportSource().entries())

        assertEquals(GameEngine.MZ, game.engine)
        assertEquals("Legal MZ Fixture", game.title)
        assertEquals("1.8.0", game.engineVersion)
        assertEquals(listOf("FixturePlugin"), game.plugins)
        assertEquals("", game.sourcePrefix)
    }

    @Test
    fun detectsMVDeploymentInsideWww() {
        val files = RpgMakerFixtureGenerator.mvInWww() +
            ("www/Icon/ICON.PNG" to "artwork".toByteArray())
        val game = detector.detect(files.asImportSource().entries())

        assertEquals(GameEngine.MV, game.engine)
        assertEquals("1.6.2", game.engineVersion)
        assertEquals("www/", game.sourcePrefix)
        assertEquals("Icon/ICON.PNG", game.artworkRelativePath)
    }

    @Test
    fun usesGameFolderNameWhenSystemTitleIsBlank() {
        val files = RpgMakerFixtureGenerator.mz() +
            ("data/System.json" to "{\"gameTitle\":\"   \"}".toByteArray())

        val game = detector.detect(files.asImportSource().entries(), fallbackTitle = " My Game ")

        assertEquals("My Game", game.title)
    }

    @Test
    fun acceptsCryptoJsProtectedSystemAndObfuscatedPluginMetadata() {
        val files = RpgMakerFixtureGenerator.mz() + mapOf(
            "data/System.json" to protectedPayload(),
            "js/plugins.js" to "var _0xac8fe2=_0x2230;function _0x2230(){}".toByteArray(),
        )

        val game = detector.detect(files.asImportSource().entries(), fallbackTitle = "TE")

        assertEquals("TE", game.title)
        assertEquals(emptyList<String>(), game.plugins)
    }

    @Test(expected = ImportFailure::class)
    fun rejectsDeploymentWithoutSystemMetadata() {
        val files = RpgMakerFixtureGenerator.mz().filterKeys { it != "data/System.json" }
        detector.detect(files.asImportSource().entries())
    }

    private fun protectedPayload(): ByteArray {
        val container = ByteArray(32)
        "Salted__".toByteArray().copyInto(container)
        return Base64.getEncoder().encode(container)
    }
}
