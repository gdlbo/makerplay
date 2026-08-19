package io.github.gdlbo.makerplay.feature.library

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.gdlbo.makerplay.feature.importer.ImportUiState
import io.github.gdlbo.makerplay.feature.library.components.EmptyLibrary
import io.github.gdlbo.makerplay.feature.library.components.GameCard
import io.github.gdlbo.makerplay.feature.library.components.ImportStatus
import io.github.gdlbo.makerplay.feature.library.components.LibraryBottomBar
import io.github.gdlbo.makerplay.feature.library.components.LibraryTopBar
import io.github.gdlbo.makerplay.model.GameSummary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    games: List<GameSummary>,
    importState: ImportUiState,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onPlay: (GameSummary) -> Unit,
    onGameSettings: (GameSummary) -> Unit,
    onDelete: (GameSummary) -> Unit,
    onClearWebData: (GameSummary) -> Unit,
    onReorderGames: (List<String>) -> Unit,
    onRunSmokeTest: () -> Unit,
    onSettings: () -> Unit,
    artworkFile: (GameSummary) -> File?,
    showRuntimeSmokeTest: Boolean,
) {
    val gridState = rememberLazyGridState()
    val hapticFeedback = LocalHapticFeedback.current
    var orderedGames by remember { mutableStateOf(games) }
    var draggedGameId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(games, draggedGameId) {
        if (draggedGameId == null) orderedGames = games
    }

    fun finishReorder() {
        if (draggedGameId != null) onReorderGames(orderedGames.map(GameSummary::id))
        draggedGameId = null
        dragOffset = Offset.Zero
    }

    Scaffold(
        topBar = { LibraryTopBar(games = games, onSettings = onSettings) },
        bottomBar = {
            LibraryBottomBar(
                hasGames = games.isNotEmpty(),
                importState = importState,
                onImport = onImport,
                onRunSmokeTest = onRunSmokeTest,
                showRuntimeSmokeTest = showRuntimeSmokeTest,
            )
        },
    ) { contentPadding ->
        if (games.isEmpty()) {
            EmptyLibrary(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                importEnabled = importState !is ImportUiState.Running,
                onImport = onImport,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImportStatus(importState, onCancelImport)
                }
                items(orderedGames, key = GameSummary::id) { game ->
                    val isDragged = draggedGameId == game.id
                    GameCard(
                        game = game,
                        artwork = artworkFile(game),
                        modifier = Modifier
                            .animateItem()
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                if (isDragged) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    shadowElevation = 12.dp.toPx()
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            }
                            .pointerInput(game.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedGameId = game.id
                                        dragOffset = Offset.Zero
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragCancel = ::finishReorder,
                                    onDragEnd = ::finishReorder,
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        var draggedInfo = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == game.id }
                                            ?: return@detectDragGesturesAfterLongPress
                                        var draggedCenter = Offset(
                                            x = draggedInfo.offset.x + dragOffset.x + draggedInfo.size.width / 2f,
                                            y = draggedInfo.offset.y + dragOffset.y + draggedInfo.size.height / 2f,
                                        )
                                        val layoutInfo = gridState.layoutInfo
                                        val edgeScroll = when {
                                            draggedCenter.y < layoutInfo.viewportStartOffset + 48.dp.toPx() -> {
                                                (draggedCenter.y - layoutInfo.viewportStartOffset - 48.dp.toPx())
                                                    .coerceAtLeast(-32.dp.toPx())
                                            }

                                            draggedCenter.y > layoutInfo.viewportEndOffset - 48.dp.toPx() -> {
                                                (draggedCenter.y - layoutInfo.viewportEndOffset + 48.dp.toPx())
                                                    .coerceAtMost(32.dp.toPx())
                                            }

                                            else -> 0f
                                        }
                                        if (edgeScroll != 0f) {
                                            val consumedScroll =
                                                gridState.dispatchRawDelta(edgeScroll)
                                            dragOffset += Offset(0f, consumedScroll)
                                            draggedInfo = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == game.id }
                                                ?: return@detectDragGesturesAfterLongPress
                                            draggedCenter = Offset(
                                                x = draggedInfo.offset.x + dragOffset.x + draggedInfo.size.width / 2f,
                                                y = draggedInfo.offset.y + dragOffset.y + draggedInfo.size.height / 2f,
                                            )
                                        }
                                        val targetInfo =
                                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                                item.key != game.id &&
                                                        draggedCenter.x >= item.offset.x &&
                                                        draggedCenter.x <= item.offset.x + item.size.width &&
                                                        draggedCenter.y >= item.offset.y &&
                                                        draggedCenter.y <= item.offset.y + item.size.height
                                            } ?: return@detectDragGesturesAfterLongPress
                                        val fromIndex =
                                            orderedGames.indexOfFirst { it.id == game.id }
                                        val toIndex =
                                            orderedGames.indexOfFirst { it.id == targetInfo.key }
                                        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
                                            return@detectDragGesturesAfterLongPress
                                        }
                                        dragOffset += Offset(
                                            x = draggedInfo.offset.x - targetInfo.offset.x.toFloat(),
                                            y = draggedInfo.offset.y - targetInfo.offset.y.toFloat(),
                                        )
                                        orderedGames = orderedGames.toMutableList().apply {
                                            add(toIndex, removeAt(fromIndex))
                                        }
                                    },
                                )
                            },
                        onPlay = { onPlay(game) },
                        onSettings = { onGameSettings(game) },
                        onDelete = { onDelete(game) },
                        onClearWebData = { onClearWebData(game) },
                    )
                }
            }
        }
    }
}