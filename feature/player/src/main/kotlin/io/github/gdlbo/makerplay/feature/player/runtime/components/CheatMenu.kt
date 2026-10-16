package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.runtime.api.CheatActorEntry
import io.github.gdlbo.makerplay.runtime.api.CheatActorStat
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCatalogEntry
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.CheatInventoryKind
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.CheatResource
import io.github.gdlbo.makerplay.runtime.api.RecoveryTarget
import kotlin.math.abs
import androidx.compose.foundation.lazy.items as lazyItems

private enum class CheatTab { BOOST, BATTLE, PARTY, ITEMS, DATA, WORLD }

private val SPEED_PRESETS = listOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)

@Composable
internal fun CheatOverlay(
    flags: CheatFlags,
    catalog: CheatCatalog,
    onFlagsChanged: (CheatFlags) -> Unit,
    onOperation: (CheatOperation) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardOpen = imeBottom > 0
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) }
            .imePadding(),
        contentAlignment = if (keyboardOpen) Alignment.BottomCenter else Alignment.Center,
    ) {
        CheatMenu(
            flags = flags,
            catalog = catalog,
            onFlagsChanged = onFlagsChanged,
            onOperation = onOperation,
            onClose = onClose,
            keyboardOpen = keyboardOpen,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(6.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        )
    }
}

@Composable
private fun CheatMenu(
    flags: CheatFlags,
    catalog: CheatCatalog,
    onFlagsChanged: (CheatFlags) -> Unit,
    onOperation: (CheatOperation) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOpen: Boolean = false,
) {
    var tabName by rememberSaveable { mutableStateOf(CheatTab.BOOST.name) }
    val tab = CheatTab.entries.firstOrNull { it.name == tabName } ?: CheatTab.BOOST
    val focusManager = LocalFocusManager.current
    val boostScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val battleScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val partyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val itemsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val dataListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val worldScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    LaunchedEffect(Unit) { onOperation(CheatOperation.RefreshCatalog) }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val panelMaxWidth = if (landscape) 780.dp else 460.dp
        val panelWidthFraction = if (landscape) 0.90f else 0.95f
        val panelModifier = if (keyboardOpen) {
            Modifier
                .widthIn(max = panelMaxWidth)
                .fillMaxWidth(panelWidthFraction)
                .heightIn(min = 140.dp, max = maxHeight)
                .fillMaxHeight(0.98f)
        } else {
            Modifier
                .widthIn(max = panelMaxWidth)
                .fillMaxWidth(panelWidthFraction)
                .fillMaxHeight(if (landscape) 0.92f else 0.88f)
        }

        Surface(
            modifier = panelModifier,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CheatTopHeader(
                    selectedTab = tab,
                    landscape = landscape,
                    onSelectTab = { candidate ->
                        focusManager.clearFocus(force = true)
                        tabName = candidate.name
                        if (candidate != CheatTab.BOOST) {
                            onOperation(CheatOperation.RefreshCatalog)
                        }
                    },
                    onRefresh = { onOperation(CheatOperation.RefreshCatalog) },
                    onClose = onClose,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    when (tab) {
                        CheatTab.BOOST -> BoostTab(
                            flags = flags,
                            scrollState = boostScroll,
                            landscape = landscape,
                            onFlagsChanged = onFlagsChanged,
                        )

                        CheatTab.BATTLE -> BattleTab(
                            flags = flags,
                            scrollState = battleScroll,
                            onFlagsChanged = onFlagsChanged,
                            onOperation = onOperation,
                        )

                        CheatTab.PARTY -> PartyTab(
                            catalog = catalog,
                            listState = partyListState,
                            onOperation = onOperation,
                        )

                        CheatTab.ITEMS -> ItemsTab(
                            catalog = catalog,
                            listState = itemsListState,
                            onOperation = onOperation,
                        )

                        CheatTab.DATA -> DataTab(
                            catalog = catalog,
                            listState = dataListState,
                            onOperation = onOperation,
                        )

                        CheatTab.WORLD -> WorldTab(
                            catalog = catalog,
                            scrollState = worldScroll,
                            onOperation = onOperation,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatTopHeader(
    selectedTab: CheatTab,
    landscape: Boolean,
    onSelectTab: (CheatTab) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    if (landscape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cheats),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            ExpressiveTabRow(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                modifier = Modifier.weight(1f),
            )
            HeaderActionButtons(onRefresh = onRefresh, onClose = onClose)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cheats),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                HeaderActionButtons(onRefresh = onRefresh, onClose = onClose)
            }
            ExpressiveTabRow(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HeaderActionButtons(
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onRefresh,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh_catalog),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpressiveTabRow(
    selectedTab: CheatTab,
    onSelectTab: (CheatTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(30.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheatTab.entries.forEach { candidate ->
                val selected = selectedTab == candidate
                Surface(
                    onClick = { onSelectTab(candidate) },
                    shape = if (selected) CircleShape else RoundedCornerShape(8.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        Text(
                            text = when (candidate) {
                                CheatTab.BOOST -> stringResource(R.string.cheat_tab_boost)
                                CheatTab.BATTLE -> stringResource(R.string.cheat_tab_battle)
                                CheatTab.PARTY -> stringResource(R.string.cheat_tab_party)
                                CheatTab.ITEMS -> stringResource(R.string.cheat_tab_items)
                                CheatTab.DATA -> stringResource(R.string.cheat_tab_data)
                                CheatTab.WORLD -> stringResource(R.string.cheat_tab_world)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoostTab(
    flags: CheatFlags,
    scrollState: ScrollState,
    landscape: Boolean,
    onFlagsChanged: (CheatFlags) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(stringResource(R.string.cheat_live_modifiers))
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactToggle(
                        title = stringResource(R.string.god_mode),
                        description = stringResource(R.string.god_mode_description),
                        checked = flags.godMode,
                        onCheckedChange = { onFlagsChanged(flags.copy(godMode = it)) },
                    )
                    CompactToggle(
                        title = stringResource(R.string.infinite_hp),
                        description = stringResource(R.string.infinite_hp_description),
                        checked = flags.infiniteHp,
                        onCheckedChange = { onFlagsChanged(flags.copy(infiniteHp = it)) },
                    )
                    CompactToggle(
                        title = stringResource(R.string.infinite_mp),
                        description = stringResource(R.string.infinite_mp_description),
                        checked = flags.infiniteMp,
                        onCheckedChange = { onFlagsChanged(flags.copy(infiniteMp = it)) },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactToggle(
                        title = stringResource(R.string.no_clip),
                        description = stringResource(R.string.no_clip_description),
                        checked = flags.noClip,
                        onCheckedChange = { onFlagsChanged(flags.copy(noClip = it)) },
                    )
                    CompactToggle(
                        title = stringResource(R.string.hold_to_skip_dialog),
                        description = stringResource(R.string.hold_to_skip_dialog_description),
                        checked = flags.holdToSkipDialog,
                        onCheckedChange = { onFlagsChanged(flags.copy(holdToSkipDialog = it)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeedControl(
                    title = stringResource(R.string.game_speed),
                    value = flags.gameSpeedMultiplier,
                    onValueChange = { onFlagsChanged(flags.copy(gameSpeedMultiplier = it)) },
                    modifier = Modifier.weight(1f),
                )
                SpeedControl(
                    title = stringResource(R.string.walk_speed),
                    value = flags.playerSpeedMultiplier,
                    onValueChange = { onFlagsChanged(flags.copy(playerSpeedMultiplier = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            CompactToggle(
                title = stringResource(R.string.god_mode),
                description = stringResource(R.string.god_mode_description),
                checked = flags.godMode,
                onCheckedChange = { onFlagsChanged(flags.copy(godMode = it)) },
            )
            CompactToggle(
                title = stringResource(R.string.infinite_hp),
                description = stringResource(R.string.infinite_hp_description),
                checked = flags.infiniteHp,
                onCheckedChange = { onFlagsChanged(flags.copy(infiniteHp = it)) },
            )
            CompactToggle(
                title = stringResource(R.string.infinite_mp),
                description = stringResource(R.string.infinite_mp_description),
                checked = flags.infiniteMp,
                onCheckedChange = { onFlagsChanged(flags.copy(infiniteMp = it)) },
            )
            CompactToggle(
                title = stringResource(R.string.no_clip),
                description = stringResource(R.string.no_clip_description),
                checked = flags.noClip,
                onCheckedChange = { onFlagsChanged(flags.copy(noClip = it)) },
            )
            CompactToggle(
                title = stringResource(R.string.hold_to_skip_dialog),
                description = stringResource(R.string.hold_to_skip_dialog_description),
                checked = flags.holdToSkipDialog,
                onCheckedChange = { onFlagsChanged(flags.copy(holdToSkipDialog = it)) },
            )
            SpeedControl(
                title = stringResource(R.string.game_speed),
                value = flags.gameSpeedMultiplier,
                onValueChange = { onFlagsChanged(flags.copy(gameSpeedMultiplier = it)) },
            )
            SpeedControl(
                title = stringResource(R.string.walk_speed),
                value = flags.playerSpeedMultiplier,
                onValueChange = { onFlagsChanged(flags.copy(playerSpeedMultiplier = it)) },
            )
        }
    }
}

@Composable
private fun BattleTab(
    flags: CheatFlags,
    scrollState: ScrollState,
    onFlagsChanged: (CheatFlags) -> Unit,
    onOperation: (CheatOperation) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(RecoveryTarget.PARTY.name) }
    val selected = RecoveryTarget.entries.firstOrNull { it.name == target } ?: RecoveryTarget.PARTY
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel(stringResource(R.string.cheat_health_actions))
        SegmentedPillRow {
            RecoveryTarget.entries.forEach { candidate ->
                ExpressiveChip(
                    selected = selected == candidate,
                    onClick = { target = candidate.name },
                    label = recoveryTargetLabel(candidate),
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TonalActionPill(
                        label = stringResource(R.string.recover_selected),
                        onClick = { onOperation(CheatOperation.Recover(selected)) },
                        modifier = Modifier.weight(1f),
                    )
                    TonalActionPill(
                        label = stringResource(R.string.refill_mp),
                        onClick = {
                            onOperation(CheatOperation.RefillResource(selected, CheatResource.MP))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TonalActionPill(
                        label = stringResource(R.string.refill_tp),
                        onClick = {
                            onOperation(CheatOperation.RefillResource(selected, CheatResource.TP))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TonalActionPill(
                        label = stringResource(R.string.clear_states),
                        onClick = { onOperation(CheatOperation.ClearStates(selected)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TonalActionPill(
                        label = stringResource(R.string.cheat_hp_one_short),
                        destructive = true,
                        onClick = {
                            if (selected.affectsParty()) {
                                onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
                            }
                            onOperation(CheatOperation.SetHpToOne(selected))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TonalActionPill(
                        label = stringResource(R.string.cheat_defeat_short),
                        destructive = true,
                        onClick = {
                            if (selected.affectsParty()) {
                                onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
                            }
                            onOperation(CheatOperation.Defeat(selected))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.cheat_health_actions_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PartyTab(
    catalog: CheatCatalog,
    listState: LazyListState,
    onOperation: (CheatOperation) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cheat_gold_value, catalog.gold),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    MiniActionPill("+1k") { onOperation(CheatOperation.AddGold(1_000)) }
                    MiniActionPill("+10k") { onOperation(CheatOperation.AddGold(10_000)) }
                    MiniActionPill("999k", highlighted = true) {
                        onOperation(CheatOperation.SetGold(999_999))
                    }
                }
            }
        }
        if (catalog.actors.isEmpty()) {
            item {
                EmptyHint(stringResource(R.string.cheat_party_empty))
            }
        } else {
            lazyItems(catalog.actors, key = { it.id }) { actor ->
                ActorCard(actor = actor, onOperation = onOperation)
            }
        }
    }
}

@Composable
private fun ActorCard(actor: CheatActorEntry, onOperation: (CheatOperation) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "#${actor.id} ${actor.name}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "Lv ${actor.level}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = "HP ${actor.hp}/${actor.mhp}  ·  MP ${actor.mp}/${actor.mmp}  ·  TP ${actor.tp}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MiniActionPill(
                    label = stringResource(R.string.cheat_max_hp),
                    modifier = Modifier.weight(1f),
                ) {
                    onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.HP, actor.mhp))
                }
                MiniActionPill(
                    label = stringResource(R.string.cheat_max_mp),
                    modifier = Modifier.weight(1f),
                ) {
                    onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.MP, actor.mmp))
                }
                MiniActionPill(
                    label = stringResource(R.string.cheat_max_tp),
                    modifier = Modifier.weight(1f),
                ) {
                    onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.TP, 100))
                }
                MiniActionPill(
                    label = "+1k EXP",
                    highlighted = true,
                    modifier = Modifier.weight(1f),
                ) {
                    onOperation(CheatOperation.AddExperience(actor.id, 1_000))
                }
            }
        }
    }
}

@Composable
private fun ItemsTab(
    catalog: CheatCatalog,
    listState: LazyListState,
    onOperation: (CheatOperation) -> Unit,
) {
    var kindName by rememberSaveable { mutableStateOf(CheatInventoryKind.ITEM.name) }
    val kind =
        CheatInventoryKind.entries.firstOrNull { it.name == kindName } ?: CheatInventoryKind.ITEM
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var systemKeyboard by remember { mutableStateOf(false) }
    var searchShown by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val source = when (kind) {
        CheatInventoryKind.ITEM -> catalog.items
        CheatInventoryKind.WEAPON -> catalog.weapons
        CheatInventoryKind.ARMOR -> catalog.armors
    }
    val filtered = remember(source, query) {
        val needle = query.trim()
        source.filter { entry ->
            needle.isEmpty() ||
                    entry.id.toString().contains(needle) ||
                    entry.name.contains(needle, ignoreCase = true) ||
                    entry.value.contains(needle, ignoreCase = true)
        }
    }
    val countLabel = if (query.isBlank()) {
        stringResource(R.string.catalog_entries, filtered.size)
    } else {
        stringResource(R.string.catalog_matches, filtered.size, source.size)
    }

    AdaptivePadScaffold(
        showPad = searchShown && searching && !systemKeyboard,
        allowMinus = false,
        onDigit = { digit -> query = (query + digit).take(80) },
        onMinus = {},
        onBackspace = { query = query.dropLast(1) },
        onSystemKeyboard = { systemKeyboard = true },
        onClosePad = { searching = false },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CompactCatalogHeader(
                searchShown = searchShown,
                query = query,
                searching = searching,
                systemKeyboard = systemKeyboard,
                countLabel = countLabel,
                onToggleSearch = {
                    searchShown = !searchShown
                    if (!searchShown) {
                        searching = false
                        systemKeyboard = false
                        query = ""
                    } else {
                        searching = true
                        if (!systemKeyboard) keyboard?.hide()
                    }
                },
                onQueryChange = { query = it.take(80) },
                onActivateSearch = {
                    searching = true
                    if (!systemKeyboard) keyboard?.hide()
                },
                onToggleSystemKeyboard = {
                    searching = true
                    systemKeyboard = !systemKeyboard
                    if (!systemKeyboard) keyboard?.hide()
                },
            ) {
                CheatInventoryKind.entries.forEach { candidate ->
                    ExpressiveChip(
                        selected = kind == candidate,
                        onClick = { kindName = candidate.name },
                        label = when (candidate) {
                            CheatInventoryKind.ITEM -> stringResource(R.string.inventory_items)
                            CheatInventoryKind.WEAPON -> stringResource(R.string.inventory_weapons)
                            CheatInventoryKind.ARMOR -> stringResource(R.string.inventory_armor)
                        },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (filtered.isEmpty()) {
                EmptyHint(stringResource(R.string.catalog_empty))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    lazyItems(filtered, key = { "${kind.name}-${it.id}" }) { entry ->
                        InventoryRow(
                            entry = entry,
                            onChange = { delta ->
                                onOperation(CheatOperation.AddInventory(kind, entry.id, delta))
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryRow(entry: CheatCatalogEntry, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "#${entry.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(34.dp),
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = stringResource(R.string.cheat_owned, entry.value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        MiniIconPill(icon = Icons.Default.Remove, contentDescription = "-1") { onChange(-1) }
        MiniIconPill(icon = Icons.Default.Add, contentDescription = "+1") { onChange(1) }
        MiniActionPill("+10") { onChange(10) }
        MiniActionPill("+99", highlighted = true) { onChange(99) }
    }
}

@Composable
private fun DataTab(
    catalog: CheatCatalog,
    listState: LazyListState,
    onOperation: (CheatOperation) -> Unit,
) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedVariableId by rememberSaveable { mutableIntStateOf(-1) }
    var variableDraft by rememberSaveable { mutableStateOf("") }
    val source = if (kind == 0) catalog.variables else catalog.switches
    val selectedVariable = source.firstOrNull { it.id == selectedVariableId }
    var keyTarget by remember { mutableStateOf(KeyTarget.NONE) }
    var systemKeyboard by remember { mutableStateOf(false) }
    var searchShown by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val activeValue = when (keyTarget) {
        KeyTarget.SEARCH -> query
        KeyTarget.EDIT -> variableDraft
        KeyTarget.NONE -> ""
    }

    fun typeInto(text: String) {
        when (keyTarget) {
            KeyTarget.SEARCH -> query = text.take(80)
            KeyTarget.EDIT -> variableDraft = text.take(160)
            KeyTarget.NONE -> Unit
        }
    }

    fun openTarget(target: KeyTarget) {
        keyTarget = target
        if (!systemKeyboard) keyboard?.hide()
    }

    val filtered = remember(source, query) {
        val needle = query.trim()
        source.filter { entry ->
            needle.isEmpty() ||
                    entry.id.toString().contains(needle) ||
                    entry.name.contains(needle, ignoreCase = true) ||
                    entry.value.contains(needle, ignoreCase = true)
        }
    }
    val countLabel = if (query.isBlank()) {
        stringResource(R.string.catalog_entries, filtered.size)
    } else {
        stringResource(R.string.catalog_matches, filtered.size, source.size)
    }

    AdaptivePadScaffold(
        showPad = keyTarget != KeyTarget.NONE && !systemKeyboard,
        allowMinus = keyTarget == KeyTarget.EDIT,
        onDigit = { digit -> typeInto(activeValue + digit) },
        onMinus = {
            val toggled = if (activeValue.startsWith("-")) {
                activeValue.removePrefix("-")
            } else {
                "-$activeValue"
            }
            typeInto(toggled)
        },
        onBackspace = { typeInto(activeValue.dropLast(1)) },
        onSystemKeyboard = { systemKeyboard = true },
        onClosePad = { keyTarget = KeyTarget.NONE },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CompactCatalogHeader(
                searchShown = searchShown,
                query = query,
                searching = keyTarget == KeyTarget.SEARCH,
                systemKeyboard = systemKeyboard && keyTarget == KeyTarget.SEARCH,
                countLabel = countLabel,
                onToggleSearch = {
                    searchShown = !searchShown
                    if (!searchShown) {
                        if (keyTarget == KeyTarget.SEARCH) keyTarget = KeyTarget.NONE
                        systemKeyboard = false
                        query = ""
                    } else {
                        openTarget(KeyTarget.SEARCH)
                    }
                },
                onQueryChange = { query = it.take(80) },
                onActivateSearch = { openTarget(KeyTarget.SEARCH) },
                onToggleSystemKeyboard = {
                    keyTarget = KeyTarget.SEARCH
                    systemKeyboard = !systemKeyboard
                    if (!systemKeyboard) keyboard?.hide()
                },
            ) {
                ExpressiveChip(
                    selected = kind == 0,
                    onClick = {
                        kind = 0
                        selectedVariableId = -1
                    },
                    label = stringResource(R.string.catalog_variables),
                )
                ExpressiveChip(
                    selected = kind == 1,
                    onClick = {
                        kind = 1
                        selectedVariableId = -1
                        if (keyTarget == KeyTarget.EDIT) keyTarget = KeyTarget.NONE
                    },
                    label = stringResource(R.string.catalog_switches),
                )
            }

            AnimatedVisibility(visible = kind == 0 && selectedVariable != null) {
                selectedVariable?.let { entry ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "#${entry.id}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            CompactValueField(
                                value = variableDraft,
                                onValueChange = { variableDraft = it.take(160) },
                                placeholder = "#${entry.id}",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                                textAlignStart = true,
                                active = keyTarget == KeyTarget.EDIT,
                                systemKeyboard = systemKeyboard && keyTarget == KeyTarget.EDIT,
                                onActivate = { openTarget(KeyTarget.EDIT) },
                                onSystemKeyboard = {
                                    keyTarget = KeyTarget.EDIT
                                    systemKeyboard = !systemKeyboard
                                    if (!systemKeyboard) keyboard?.hide()
                                },
                            )
                            ValueStepperRow(
                                enabled = variableDraft.isBlank() || variableDraft.toDoubleOrNull() != null,
                                onStep = { delta ->
                                    val current = variableDraft.toDoubleOrNull() ?: 0.0
                                    val nextValue = current + delta
                                    val next = if (nextValue == nextValue.toLong().toDouble()) {
                                        nextValue.toLong().toString()
                                    } else {
                                        nextValue.toString()
                                    }
                                    variableDraft = next.take(160)
                                    onOperation(CheatOperation.SetVariable(entry.id, next))
                                },
                            )
                            MiniActionPill(
                                label = stringResource(R.string.set_variable),
                                highlighted = true,
                            ) {
                                onOperation(CheatOperation.SetVariable(entry.id, variableDraft))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (filtered.isEmpty()) {
                EmptyHint(stringResource(R.string.catalog_empty))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    lazyItems(filtered, key = { "${kind}-${it.id}" }) { entry ->
                        if (kind == 0) {
                            val isSelected = entry.id == selectedVariableId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable {
                                        selectedVariableId = entry.id
                                        variableDraft = entry.value.take(160)
                                        openTarget(KeyTarget.EDIT)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${entry.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(38.dp),
                                )
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Text(
                                        text = entry.value,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            horizontal = 6.dp,
                                            vertical = 2.dp
                                        ),
                                    )
                                }
                            }
                        } else {
                            val enabled = entry.value.equals("ON", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = enabled,
                                        role = Role.Switch,
                                        onValueChange = {
                                            onOperation(
                                                CheatOperation.SetSwitch(
                                                    entry.id,
                                                    !enabled
                                                )
                                            )
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${entry.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(38.dp),
                                )
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = null,
                                    modifier = Modifier.scale(0.72f),
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

private enum class KeyTarget { NONE, SEARCH, EDIT }

@Composable
private fun WorldTab(
    catalog: CheatCatalog,
    scrollState: ScrollState,
    onOperation: (CheatOperation) -> Unit,
) {
    var mapIdText by remember(catalog.mapId) {
        mutableStateOf(catalog.mapId.takeIf { it > 0 }?.toString() ?: "1")
    }
    var mapXText by remember(catalog.mapX) { mutableStateOf(catalog.mapX.toString()) }
    var mapYText by remember(catalog.mapY) { mutableStateOf(catalog.mapY.toString()) }
    var actorIdText by remember {
        mutableStateOf(catalog.actors.firstOrNull()?.id?.toString() ?: "1")
    }
    var paramIdText by remember { mutableStateOf("0") }
    var amountText by remember { mutableStateOf("10") }

    val mapId = mapIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val mapX = mapXText.toIntOrNull()?.takeIf { it in 0..9999 }
    val mapY = mapYText.toIntOrNull()?.takeIf { it in 0..9999 }
    val actorId = actorIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val paramId = paramIdText.toIntOrNull()?.takeIf { it in 0..7 }
    val amount = amountText.toIntOrNull()?.takeIf { it in -1_000_000_000..1_000_000_000 }

    var activeNumber by remember { mutableStateOf<Int?>(null) }
    var systemNumberKeyboard by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun selectNumber(index: Int) {
        if (activeNumber == index && !systemNumberKeyboard) {
            activeNumber = null
            return
        }
        if (activeNumber != index) systemNumberKeyboard = false
        activeNumber = index
        if (!systemNumberKeyboard) keyboard?.hide()
    }

    fun editNumber(index: Int, next: String) {
        when (index) {
            0 -> mapIdText = next.filter(Char::isDigit).take(4)
            1 -> mapXText = next.filter(Char::isDigit).take(4)
            2 -> mapYText = next.filter(Char::isDigit).take(4)
            3 -> actorIdText = next.filter(Char::isDigit).take(4)
            4 -> paramIdText = next.filter(Char::isDigit).take(1)
            5 -> amountText = next.filterIndexed { i, char ->
                char.isDigit() || (char == '-' && i == 0)
            }.take(11)
        }
    }

    val currentActiveValue = when (activeNumber) {
        0 -> mapIdText
        1 -> mapXText
        2 -> mapYText
        3 -> actorIdText
        4 -> paramIdText
        5 -> amountText
        else -> ""
    }

    AdaptivePadScaffold(
        showPad = activeNumber != null && !systemNumberKeyboard,
        allowMinus = activeNumber == 5,
        onDigit = { digit -> activeNumber?.let { editNumber(it, currentActiveValue + digit) } },
        onMinus = {
            activeNumber?.let { index ->
                val toggled = if (currentActiveValue.startsWith("-")) {
                    currentActiveValue.removePrefix("-")
                } else {
                    "-$currentActiveValue"
                }
                editNumber(index, toggled)
            }
        },
        onBackspace = { activeNumber?.let { editNumber(it, currentActiveValue.dropLast(1)) } },
        onSystemKeyboard = { systemNumberKeyboard = true },
        onClosePad = { activeNumber = null },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cheat_current_map,
                                catalog.mapId,
                                catalog.mapX,
                                catalog.mapY,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        MiniActionPill(
                            label = stringResource(R.string.teleport),
                            highlighted = true,
                            enabled = mapId != null && mapX != null && mapY != null,
                        ) {
                            if (mapId != null && mapX != null && mapY != null) {
                                onOperation(CheatOperation.Teleport(mapId, mapX, mapY))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NumberValueField(
                            value = mapIdText,
                            onValueChange = { editNumber(0, it) },
                            placeholder = stringResource(R.string.map_id),
                            selected = activeNumber == 0,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 0,
                            onSelect = { selectNumber(0) },
                            onToggleSystemKeyboard = {
                                activeNumber = 0
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NumberValueField(
                            value = mapXText,
                            onValueChange = { editNumber(1, it) },
                            placeholder = "X",
                            selected = activeNumber == 1,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 1,
                            onSelect = { selectNumber(1) },
                            onToggleSystemKeyboard = {
                                activeNumber = 1
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NumberValueField(
                            value = mapYText,
                            onValueChange = { editNumber(2, it) },
                            placeholder = "Y",
                            selected = activeNumber == 2,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 2,
                            onSelect = { selectNumber(2) },
                            onToggleSystemKeyboard = {
                                activeNumber = 2
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(3) { slot ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    MiniActionPill(
                                        label = stringResource(R.string.save_position, slot + 1),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        onOperation(CheatOperation.SavePosition(slot))
                                    }
                                    MiniActionPill(
                                        label = stringResource(R.string.recall_position, slot + 1),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        onOperation(CheatOperation.RecallPosition(slot))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.character_progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        MiniActionPill(
                            label = stringResource(R.string.add_parameter),
                            highlighted = true,
                            enabled = actorId != null && paramId != null && amount != null,
                        ) {
                            if (actorId != null && paramId != null && amount != null) {
                                onOperation(CheatOperation.AddParameter(actorId, paramId, amount))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NumberValueField(
                            value = actorIdText,
                            onValueChange = { editNumber(3, it) },
                            placeholder = stringResource(R.string.actor_id),
                            selected = activeNumber == 3,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 3,
                            onSelect = { selectNumber(3) },
                            onToggleSystemKeyboard = {
                                activeNumber = 3
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NumberValueField(
                            value = paramIdText,
                            onValueChange = { editNumber(4, it) },
                            placeholder = stringResource(R.string.parameter_id),
                            selected = activeNumber == 4,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 4,
                            onSelect = { selectNumber(4) },
                            onToggleSystemKeyboard = {
                                activeNumber = 4
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                        NumberValueField(
                            value = amountText,
                            onValueChange = { editNumber(5, it) },
                            placeholder = stringResource(R.string.amount),
                            selected = activeNumber == 5,
                            systemKeyboard = systemNumberKeyboard && activeNumber == 5,
                            onSelect = { selectNumber(5) },
                            onToggleSystemKeyboard = {
                                activeNumber = 5
                                systemNumberKeyboard = !systemNumberKeyboard
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Docks the NumberPad on the right side in horizontal/short mode so it never gets cropped,
 * and docks it cleanly at the bottom in portrait mode.
 */
@Composable
private fun AdaptivePadScaffold(
    showPad: Boolean,
    allowMinus: Boolean,
    onDigit: (String) -> Unit,
    onMinus: () -> Unit,
    onBackspace: () -> Unit,
    onSystemKeyboard: () -> Unit,
    onClosePad: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight || maxHeight < 360.dp
        if (sideBySide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()) {
                    content()
                }
                if (showPad) {
                    NumberPad(
                        allowMinus = allowMinus,
                        fillHeight = true,
                        onDigit = onDigit,
                        onMinus = onMinus,
                        onBackspace = onBackspace,
                        onSystemKeyboard = onSystemKeyboard,
                        onClose = onClosePad,
                        modifier = Modifier
                            .width(192.dp)
                            .fillMaxHeight()
                            .padding(end = 8.dp, top = 4.dp, bottom = 6.dp),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    content()
                }
                if (showPad) {
                    NumberPad(
                        allowMinus = allowMinus,
                        fillHeight = false,
                        onDigit = onDigit,
                        onMinus = onMinus,
                        onBackspace = onBackspace,
                        onSystemKeyboard = onSystemKeyboard,
                        onClose = onClosePad,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCatalogHeader(
    searchShown: Boolean,
    query: String,
    searching: Boolean,
    systemKeyboard: Boolean,
    countLabel: String,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onActivateSearch: () -> Unit,
    onToggleSystemKeyboard: () -> Unit,
    chips: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchToggle(shown = searchShown, onToggle = onToggleSearch)
            SegmentedPillRow(
                modifier = Modifier.weight(1f),
                content = chips,
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        AnimatedVisibility(visible = searchShown) {
            CompactSearchField(
                value = query,
                onValueChange = onQueryChange,
                active = searching,
                systemKeyboard = systemKeyboard,
                onActivate = onActivateSearch,
                onSystemKeyboard = onToggleSystemKeyboard,
                onClear = { onQueryChange("") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    systemKeyboard: Boolean = false,
    onActivate: () -> Unit = {},
    onSystemKeyboard: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    FieldShell(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(R.string.search_catalog),
        align = TextAlign.Start,
        keyboardType = KeyboardType.Text,
        active = active,
        systemKeyboard = systemKeyboard,
        onClick = onActivate,
        onSystemKeyboard = onSystemKeyboard,
        leading = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = if (value.isNotEmpty()) {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(22.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun FieldShell(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    align: TextAlign,
    keyboardType: KeyboardType,
    onClick: () -> Unit,
    onSystemKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    systemKeyboard: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(systemKeyboard, active) {
        if (systemKeyboard && active) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val boxAlign = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.Center
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Box(modifier = Modifier.weight(1f), contentAlignment = boxAlign) {
            if (systemKeyboard) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = align,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = align,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    },
                )
            } else {
                Text(
                    text = value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = align,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        IconButton(onClick = onSystemKeyboard, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Keyboard,
                contentDescription = stringResource(R.string.system_keyboard),
                modifier = Modifier.size(14.dp),
                tint = if (systemKeyboard) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun CompactValueField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    textAlignStart: Boolean = false,
    active: Boolean = false,
    systemKeyboard: Boolean = false,
    onActivate: () -> Unit = {},
    onSystemKeyboard: () -> Unit = {},
) {
    FieldShell(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        align = if (textAlignStart) TextAlign.Start else TextAlign.Center,
        keyboardType = keyboardType,
        onClick = onActivate,
        onSystemKeyboard = onSystemKeyboard,
        modifier = modifier,
        active = active,
        systemKeyboard = systemKeyboard,
    )
}

@Composable
private fun NumberValueField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    selected: Boolean,
    systemKeyboard: Boolean,
    onSelect: () -> Unit,
    onToggleSystemKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (systemKeyboard) {
        CompactValueField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardType = KeyboardType.Number,
            active = selected,
            systemKeyboard = true,
            onActivate = onSelect,
            onSystemKeyboard = onToggleSystemKeyboard,
            modifier = modifier,
        )
        return
    }
    Surface(
        onClick = onSelect,
        modifier = modifier.height(28.dp),
        shape = if (selected) CircleShape else RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            Text(
                text = value.ifEmpty { "0" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchToggle(
    shown: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (shown) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (shown) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (shown) Icons.Default.Close else Icons.Default.Search,
                contentDescription = stringResource(
                    if (shown) R.string.hide_search else R.string.show_search,
                ),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun NumberPad(
    allowMinus: Boolean,
    fillHeight: Boolean,
    onDigit: (String) -> Unit,
    onMinus: () -> Unit,
    onBackspace: () -> Unit,
    onSystemKeyboard: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
            )
            rows.forEachIndexed { rowIndex, row ->
                val rowModifier = if (fillHeight) {
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                } else {
                    Modifier
                        .height(30.dp)
                        .fillMaxWidth()
                }
                Row(
                    modifier = rowModifier,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    row.forEach { digit ->
                        NumberKey(
                            label = digit,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onDigit(digit) },
                        )
                    }
                    when (rowIndex) {
                        0 -> NumberKey(
                            label = "",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = onBackspace,
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                        )

                        1 -> NumberKey(
                            label = "",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = onSystemKeyboard,
                            icon = {
                                Icon(
                                    Icons.Default.Keyboard,
                                    contentDescription = stringResource(R.string.system_keyboard),
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                        )

                        else -> NumberKey(
                            label = "",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = onClose,
                            icon = {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.close),
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                        )
                    }
                }
            }
            val bottomRowModifier = if (fillHeight) {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            } else {
                Modifier
                    .height(30.dp)
                    .fillMaxWidth()
            }
            Row(
                modifier = bottomRowModifier,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (allowMinus) {
                    NumberKey(
                        label = "−",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = onMinus,
                    )
                } else {
                    NumberKey(
                        label = "00",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onDigit("00") },
                    )
                }
                NumberKey(
                    label = "0",
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight(),
                    onClick = { onDigit("0") },
                )
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight())
            }
        }
    }
}

@Composable
private fun NumberKey(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                icon()
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ValueStepperRow(
    enabled: Boolean,
    onStep: (Double) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(-10.0 to "-10", -1.0 to "-1", 1.0 to "+1", 10.0 to "+10").forEach { (delta, label) ->
            MiniActionPill(
                label = label,
                enabled = enabled,
                onClick = { onStep(delta) },
            )
        }
    }
}

@Composable
private fun SpeedControl(
    title: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "${"%.1f".format(value)}x",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            SegmentedPillRow {
                SPEED_PRESETS.forEach { preset ->
                    ExpressiveChip(
                        selected = abs(value - preset) < 0.01,
                        onClick = { onValueChange(preset) },
                        label = "${preset.toInt()}x",
                    )
                }
            }
            Slider(
                value = value.toFloat().coerceIn(1f, 8f),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = 1f..8f,
                steps = 13,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            )
        }
    }
}

@Composable
private fun SegmentedPillRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.height(28.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun ExpressiveChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Surface(
        onClick = onClick,
        shape = if (selected) CircleShape else RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxHeight(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TonalActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (destructive) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = modifier.height(30.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniActionPill(
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
            highlighted -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier.height(24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 7.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniIconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CompactToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.scale(0.76f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    )
}

@Composable
private fun recoveryTargetLabel(target: RecoveryTarget): String = stringResource(
    when (target) {
        RecoveryTarget.LEADER -> R.string.recovery_target_leader
        RecoveryTarget.PARTY -> R.string.recovery_target_party
        RecoveryTarget.ENEMIES -> R.string.recovery_target_enemies
        RecoveryTarget.ALL -> R.string.recovery_target_all
    },
)

private fun RecoveryTarget.affectsParty(): Boolean =
    this == RecoveryTarget.LEADER || this == RecoveryTarget.PARTY || this == RecoveryTarget.ALL