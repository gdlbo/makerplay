package io.github.gdlbo.makerplay.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class CopiedGameExporterTest {
    @Test
    fun packagesGameInstallAndSavesIntoZip() {
        val root = Files.createTempDirectory("makerplay-export-test").toFile()
        try {
            val gameDir = File(root, "game-install").apply { mkdirs() }
            File(gameDir, "index.html").writeText("<html></html>")
            File(gameDir, "www").mkdirs()
            File(gameDir, "www/data.json").writeText("{\"ok\":true}")
            File(gameDir, ".makerplay.properties").writeText("id=demo")

            val savesDir = File(root, "saves").apply { mkdirs() }
            File(savesDir, "file1.sav").writeBytes(byteArrayOf(1, 2, 3))

            val output = File(root, "out/demo.zip")
            CopiedGameExporter.packageZip(gameDir, savesDir, output)

            assertTrue(output.isFile)
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(names.contains("game/index.html"))
                assertTrue(names.contains("game/www/data.json"))
                assertTrue(names.contains("game/.makerplay.properties"))
                assertTrue(names.contains("saves/file1.sav"))
                zip.getInputStream(zip.getEntry("game/index.html")).use { input ->
                    assertEquals("<html></html>", input.readBytes().decodeToString())
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packagesGameWithoutSavesWhenMissing() {
        val root = Files.createTempDirectory("makerplay-export-nosaves").toFile()
        try {
            val gameDir = File(root, "game").apply { mkdirs() }
            File(gameDir, "Game.rpgproject").writeText("RPGMV 1.6.0")
            val output = File(root, "export.zip")
            CopiedGameExporter.packageZip(gameDir, savesDirectory = null, outputZip = output)

            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(names.any { it.startsWith("game/") })
                assertFalse(names.any { it.startsWith("saves/") })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sanitizesExportFileName() {
        assertEquals("Cool_Game.zip", CopiedGameExporter.sanitizeFileName("Cool/Game?", "id-1"))
        assertEquals("id-1.zip", CopiedGameExporter.sanitizeFileName("   ", "id-1"))
    }
}
