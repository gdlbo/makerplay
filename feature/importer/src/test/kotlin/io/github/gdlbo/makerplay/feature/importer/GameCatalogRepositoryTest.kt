package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameEngine
import io.github.gdlbo.makerplay.model.GameSummary
import io.github.gdlbo.makerplay.model.RuntimeBackendId
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GameCatalogRepositoryTest {
    private lateinit var testRoot: File
    private lateinit var store: PrivateGameStore

    @Before
    fun setUp() {
        testRoot = Files.createTempDirectory("makerplay-catalog-test").toFile()
        store = PrivateGameStore(File(testRoot, "games"))
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun reorderedGamesKeepTheirOrderAfterCatalogRecreation() {
        installGame("older", installedAt = 1L)
        installGame("newer", installedAt = 2L)
        val catalog = GameCatalogRepository(store)

        catalog.reorderGames(listOf("older", "newer"))

        assertEquals(listOf("older", "newer"), GameCatalogRepository(store).games.value.map { it.id })
    }

    @Test
    fun refreshRemovesDirectGameWhoseSourceFolderWasDeleted() {
        val source = File(testRoot, "linked-game").apply { mkdirs() }
        installGame("linked", installedAt = 1L, directSource = source)
        installGame("copied", installedAt = 2L)
        val catalog = GameCatalogRepository(store)

        source.deleteRecursively()
        catalog.refresh()

        assertEquals(listOf("copied"), catalog.games.value.map { it.id })
        assertEquals(false, File(testRoot, "games/linked").exists())
        assertEquals(true, File(testRoot, "games/copied").exists())
    }

    private fun installGame(id: String, installedAt: Long, directSource: File? = null) {
        val game = GameSummary(
            id = id,
            title = id,
            engine = GameEngine.MZ,
            backend = RuntimeBackendId.WEBVIEW,
            installedAtEpochMillis = installedAt,
        )
        val staging = store.begin(id)
        store.writeMetadata(staging, game, directSource)
        store.commit(staging, id)
    }
}
