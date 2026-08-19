package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameCatalogRepository(private val store: PrivateGameStore) {
    private val mutableGames = MutableStateFlow(store.listGames())
    val games: StateFlow<List<GameSummary>> = mutableGames.asStateFlow()

    fun refresh() {
        mutableGames.value = store.listGames()
    }

    fun reorderGames(gameIds: List<String>) {
        val currentGames = mutableGames.value
        val gamesById = currentGames.associateBy(GameSummary::id)
        val reordered = buildList(currentGames.size) {
            gameIds.distinct().mapNotNullTo(this) { gamesById[it] }
            addAll(currentGames.filterNot { it.id in gameIds })
        }
        if (reordered == currentGames) return
        store.writeGameOrder(reordered.map(GameSummary::id))
        mutableGames.value = reordered
    }

    fun deleteGame(gameId: String): Boolean = store.deleteGame(gameId).also { deleted ->
        if (deleted) refresh()
    }
}