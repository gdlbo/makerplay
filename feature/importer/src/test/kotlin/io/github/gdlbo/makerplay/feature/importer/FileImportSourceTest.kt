package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test

class FileImportSourceTest {
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = Files.createTempDirectory("makerplay-file-source-test").toFile()
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun importsAndReopensDirectMzWithoutChangingSource() = runBlocking {
        val sourceRoot = File(testRoot, "source-mz")
        val fixture = RpgMakerFixtureGenerator.mz()
        fixture.writeTo(sourceRoot)
        val before = fixture.mapValues { (_, bytes) -> bytes.copyOf() }
        val gamesRoot = File(testRoot, "games")

        val result = GameImportEngine(now = { 42L }).import(
            source = FileImportSource.forJvmTest(sourceRoot, listOf(testRoot)),
            store = PrivateGameStore(gamesRoot),
            importId = "direct-mz",
        )

        before.forEach { (path, bytes) -> assertArrayEquals(bytes, File(sourceRoot, path).readBytes()) }
        assertEquals(result.game, PrivateGameStore(gamesRoot).listGames().single())
        assertTrue(File(gamesRoot, "direct-mz/index.html").isFile)
    }

    @Test
    fun importsDesktopMvAndStripsWwwWithoutChangingSource() = runBlocking {
        val sourceRoot = File(testRoot, "source-mv")
        val fixture = RpgMakerFixtureGenerator.mvInWww()
        fixture.writeTo(sourceRoot)
        val gamesRoot = File(testRoot, "games")

        val result = GameImportEngine().import(
            source = FileImportSource.forJvmTest(sourceRoot, listOf(testRoot)),
            store = PrivateGameStore(gamesRoot),
            importId = "direct-mv",
        )

        fixture.forEach { (path, bytes) -> assertArrayEquals(bytes, File(sourceRoot, path).readBytes()) }
        assertEquals(result.game, PrivateGameStore(gamesRoot).listGames().single())
        assertTrue(File(gamesRoot, "direct-mv/index.html").isFile)
        assertFalse(File(gamesRoot, "direct-mv/www").exists())
    }

    @Test
    fun rejectsDirectoryOutsideAllowedStorageRoot() {
        val allowed = File(testRoot, "allowed").apply { mkdirs() }
        val outside = File(testRoot, "outside").apply { mkdirs() }

        val error = assertThrows(ImportFailure::class.java) {
            FileImportSource.forJvmTest(outside, listOf(allowed))
        }

        assertEquals("The selected folder is outside shared storage.", error.userMessage)
    }

    @Test
    fun rejectsSymbolicLinkInsideSource() {
        val sourceRoot = File(testRoot, "source-link").apply { mkdirs() }
        val outside = File(testRoot, "outside.txt").apply { writeText("outside") }
        val link = File(sourceRoot, "linked.txt").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val error = assertThrows(ImportFailure::class.java) {
            FileImportSource.forJvmTest(sourceRoot, listOf(testRoot)).entries()
        }

        assertEquals("The selected folder contains an unsupported file link.", error.userMessage)
    }

    private fun Map<String, ByteArray>.writeTo(root: File) {
        forEach { (path, bytes) ->
            File(root, path).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }
    }
}