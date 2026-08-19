package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameImportEngineTest {
    private lateinit var root: java.io.File
    private lateinit var store: PrivateGameStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("makerplay-import-test").toFile()
        store = PrivateGameStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun importsAndReopensMZWithoutChangingSource() = runBlocking {
        val sourceFiles = RpgMakerFixtureGenerator.mz() +
            ("icon/icon.png" to "artwork".toByteArray())
        val originalFiles = sourceFiles.mapValues { (_, bytes) -> bytes.copyOf() }

        val result = GameImportEngine(now = { 1234L }).import(
            source = sourceFiles.asImportSource(),
            store = store,
            importId = "mz-fixture",
        )

        originalFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, sourceFiles.getValue(path)) }
        assertTrue(java.io.File(root, "mz-fixture/index.html").isFile)
        assertTrue(java.io.File(root, "mz-fixture/${GameFileIndex.INDEX_FILE}").isFile)
        assertEquals("icon/icon.png", result.game.artworkRelativePath)
        assertEquals(result.game, PrivateGameStore(root).listGames().single())
        assertEquals(java.io.File(root, result.game.id).canonicalFile, store.findInstalledGame(result.game.id))
        assertNull(store.findInstalledGame("../outside"))
    }

    @Test
    fun stripsDesktopWwwPrefixWhenImportingMV() = runBlocking {
        val sourceFiles = RpgMakerFixtureGenerator.mvInWww()
        val originalFiles = sourceFiles.mapValues { (_, bytes) -> bytes.copyOf() }
        val result = GameImportEngine().import(
            source = sourceFiles.asImportSource(),
            store = store,
            importId = "mv-fixture",
        )

        originalFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, sourceFiles.getValue(path)) }
        assertTrue(java.io.File(root, "mv-fixture/index.html").isFile)
        assertFalse(java.io.File(root, "mv-fixture/www").exists())
        assertFalse(java.io.File(root, "mv-fixture/Game.rpgproject").exists())
        assertEquals(result.game, PrivateGameStore(root).listGames().single())
    }

    @Test
    fun reportsScanningBeforeCopyingAndThenPublishesTotal() = runBlocking {
        val sourceFiles = RpgMakerFixtureGenerator.mz()
        val progress = mutableListOf<ImportProgress>()

        GameImportEngine().import(
            source = sourceFiles.asImportSource(),
            store = store,
            importId = "progress-fixture",
            onProgress = progress::add,
        )

        assertEquals(ImportPhase.SCANNING, progress.first().phase)
        assertEquals(0L, progress.first().copiedFiles)
        val copyingStarted = progress.first { it.phase == ImportPhase.COPYING }
        assertEquals(0L, copyingStarted.copiedFiles)
        assertEquals(sourceFiles.size.toLong(), copyingStarted.totalFiles)
        assertEquals(ImportPhase.FINALIZING, progress.last().phase)
    }

    @Test
    fun deletesInstalledGameDirectory() = runBlocking {
        val result = GameImportEngine().import(
            source = RpgMakerFixtureGenerator.mz().asImportSource(),
            store = store,
            importId = "delete-fixture",
        )

        assertTrue(store.deleteGame(result.game.id))
        assertFalse(java.io.File(root, result.game.id).exists())
        assertTrue(store.listGames().isEmpty())
        assertFalse(store.deleteGame(result.game.id))
    }

    @Test
    fun linksGameWithoutCopyingOrDeletingSource() = runBlocking {
        val sourceRoot = Files.createTempDirectory("makerplay-direct-source").toFile()
        try {
            val sourceFiles = RpgMakerFixtureGenerator.mvInWww() +
                ("www/gamepad.json" to "game-owned-config".encodeToByteArray())
            sourceFiles.forEach { (path, bytes) ->
                java.io.File(sourceRoot, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }

            val result = GameImportEngine().link(
                source = sourceFiles.asImportSource(),
                sourceRoot = sourceRoot,
                store = store,
                importId = "direct-fixture",
            )

            val contentRoot = java.io.File(sourceRoot, "www").canonicalFile
            assertEquals(contentRoot, store.findInstalledGame(result.game.id))
            assertTrue(store.isDirectGame(result.game.id))
            assertTrue(java.io.File(root, "direct-fixture/${GameFileIndex.INDEX_FILE}").isFile)
            assertFalse(java.io.File(contentRoot, GameFileIndex.INDEX_FILE).exists())
            val index = GameFileIndex.loadOrBuild(
                contentRoot,
                requireNotNull(store.findIndexDirectory(result.game.id)),
            )
            assertTrue(index.entries.any { it.path.value == "index.html" })
            assertFalse(index.entries.any { it.path.value == "gamepad.json" })
            assertTrue(java.io.File(contentRoot, "gamepad.json").isFile)

            assertTrue(store.deleteGame(result.game.id))
            assertTrue(java.io.File(contentRoot, "index.html").isFile)
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    @Test
    fun loadsExistingMetadataWithoutArtworkPath() {
        val installed = java.io.File(root, "existing-game").apply { mkdirs() }
        val metadata = Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("id", "existing-game")
            setProperty("title", "Existing game")
            setProperty("engine", "MV")
            setProperty("backend", "WEBVIEW")
            setProperty("engineVersion", "1.6.2")
            setProperty("installedAtEpochMillis", "1234")
            setProperty("pluginCount", "0")
        }
        FileOutputStream(java.io.File(installed, PrivateGameStore.METADATA_FILE)).use { output ->
            metadata.store(output, null)
        }

        assertNull(PrivateGameStore(root).listGames().single().artworkRelativePath)
    }

    @Test
    fun cancellationDeletesOnlyItsStagingDirectory() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                GameImportEngine().import(
                    source = RpgMakerFixtureGenerator.mz().asImportSource(),
                    store = store,
                    importId = "cancelled-fixture",
                    onProgress = { throw CancellationException("test cancellation") },
                )
            }
        }

        assertFalse(java.io.File(root, ".staging/cancelled-fixture").exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun rejectsCaseFoldedDuplicatePaths() {
        val bytes = "x".toByteArray()
        val source = ImportSource {
            RpgMakerFixtureGenerator.mz().asImportSource().entries() + listOf(
                ImportEntry("INDEX.HTML", 1) { ByteArrayInputStream(bytes) },
            )
        }

        val error = assertThrows(ImportFailure::class.java) {
            runBlocking { GameImportEngine().import(source, store, "duplicate-fixture") }
        }

        assertEquals("The selected folder contains duplicate file paths.", error.userMessage)
    }

    @Test
    fun rejectsReservedMetadataPathAfterRemovingWwwPrefix() {
        val sourceFiles = RpgMakerFixtureGenerator.mvInWww() +
            ("www/${PrivateGameStore.METADATA_FILE}" to "malicious".toByteArray())

        val error = assertThrows(ImportFailure::class.java) {
            runBlocking {
                GameImportEngine().import(sourceFiles.asImportSource(), store, "reserved-fixture")
            }
        }

        assertEquals("The selected folder contains an unsafe file path.", error.userMessage)
        assertFalse(java.io.File(root, ".staging/reserved-fixture").exists())
    }

    @Test
    fun rejectsReservedVfsIndexPath() {
        val sourceFiles = RpgMakerFixtureGenerator.mz() +
            (GameFileIndex.INDEX_FILE to "spoofed".toByteArray())

        val error = assertThrows(ImportFailure::class.java) {
            runBlocking { GameImportEngine().import(sourceFiles.asImportSource(), store, "index-spoof") }
        }

        assertEquals("The selected folder contains an unsafe file path.", error.userMessage)
    }
}
