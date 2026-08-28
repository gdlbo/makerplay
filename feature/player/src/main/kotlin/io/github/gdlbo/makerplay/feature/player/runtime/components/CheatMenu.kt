package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        CheatMenu(
            flags = flags,
            catalog = catalog,
            onFlagsChanged = onFlagsChanged,
            onOperation = onOperation,
            onClose = onClose,
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .padding(8.dp)
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
        val panelMaxWidth = if (landscape) 760.dp else 460.dp
        val panelWidthFraction = if (landscape) 0.84f else 0.94f
        val panelHeightFraction = if (landscape) 0.88f else 0.90f

        Surface(
            modifier = Modifier
                .widthIn(max = panelMaxWidth)
                .fillMaxWidth(panelWidthFraction)
                .fillMaxHeight(panelHeightFraction),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.cheats),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onOperation(CheatOperation.RefreshCatalog) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            stringResource(R.string.refresh_catalog),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.close),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                PrimaryScrollableTabRow(
                    selectedTabIndex = CheatTab.entries.indexOf(tab),
                    edgePadding = 8.dp,
                    containerColor = Color.Transparent,
                    divider = {},
                ) {
                    CheatTab.entries.forEach { candidate ->
                        Tab(
                            selected = tab == candidate,
                            onClick = {
                                focusManager.clearFocus(force = true)
                                tabName = candidate.name
                                if (candidate != CheatTab.BOOST) {
                                    onOperation(CheatOperation.RefreshCatalog)
                                }
                            },
                            text = {
                                Text(
                                    text = when (candidate) {
                                        CheatTab.BOOST -> stringResource(R.string.cheat_tab_boost)
                                        CheatTab.BATTLE -> stringResource(R.string.cheat_tab_battle)
                                        CheatTab.PARTY -> stringResource(R.string.cheat_tab_party)
                                        CheatTab.ITEMS -> stringResource(R.string.cheat_tab_items)
                                        CheatTab.DATA -> stringResource(R.string.cheat_tab_data)
                                        CheatTab.WORLD -> stringResource(R.string.cheat_tab_world)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            },
                            modifier = Modifier.height(36.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (tab) {
                    CheatTab.BOOST -> BoostTab(
                        flags = flags,
                        scrollState = boostScroll,
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

@Composable
private fun BoostTab(
    flags: CheatFlags,
    scrollState: ScrollState,
    onFlagsChanged: (CheatFlags) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel(stringResource(R.string.cheat_live_modifiers))
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel(stringResource(R.string.cheat_health_actions))
        CenteredChipRow {
            RecoveryTarget.entries.forEach { candidate ->
                DenseChip(
                    selected = selected == candidate,
                    onClick = { target = candidate.name },
                    label = recoveryTargetLabel(candidate),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(
                onClick = { onOperation(CheatOperation.Recover(selected)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.recover_selected)) }
            TextButton(
                onClick = { onOperation(CheatOperation.RefillResource(selected, CheatResource.MP)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.refill_mp)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(
                onClick = { onOperation(CheatOperation.RefillResource(selected, CheatResource.TP)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.refill_tp)) }
            TextButton(
                onClick = { onOperation(CheatOperation.ClearStates(selected)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.clear_states)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(
                onClick = {
                    if (selected.affectsParty()) {
                        onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
                    }
                    onOperation(CheatOperation.SetHpToOne(selected))
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.cheat_hp_one_short)) }
            TextButton(
                onClick = {
                    if (selected.affectsParty()) {
                        onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
                    }
                    onOperation(CheatOperation.Defeat(selected))
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.cheat_defeat_short)) }
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.cheat_gold_value, catalog.gold),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onOperation(CheatOperation.AddGold(1_000)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("+1k") }
                TextButton(
                    onClick = { onOperation(CheatOperation.AddGold(10_000)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("+10k") }
                TextButton(
                    onClick = { onOperation(CheatOperation.SetGold(999_999)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("999k") }
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
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "#${actor.id}  ${actor.name}  Lv ${actor.level}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "HP ${actor.hp}/${actor.mhp} · MP ${actor.mp}/${actor.mmp} · TP ${actor.tp}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            ) {
                TextButton(
                    onClick = {
                        onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.HP, actor.mhp))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.cheat_max_hp)) }
                TextButton(
                    onClick = {
                        onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.MP, actor.mmp))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.cheat_max_mp)) }
                TextButton(
                    onClick = {
                        onOperation(CheatOperation.SetActorStat(actor.id, CheatActorStat.TP, 100))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.cheat_max_tp)) }
                TextButton(
                    onClick = { onOperation(CheatOperation.AddExperience(actor.id, 1_000)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("+1k EXP") }
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
    val kind = CheatInventoryKind.entries.firstOrNull { it.name == kindName } ?: CheatInventoryKind.ITEM
    var query by rememberSaveable { mutableStateOf("") }
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
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 420.dp
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactSearchField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier.weight(1f),
                    )
                    InventoryKindChips(kind = kind, onKindChanged = { kindName = it.name })
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    InventoryKindChips(kind = kind, onKindChanged = { kindName = it.name })
                    CompactSearchField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            text = if (query.isBlank()) {
                stringResource(R.string.catalog_entries, filtered.size)
            } else {
                stringResource(R.string.catalog_matches, filtered.size, source.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
        )
        if (filtered.isEmpty()) {
            EmptyHint(stringResource(R.string.catalog_empty))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                lazyItems(filtered, key = { "${kind.name}-${it.id}" }) { entry ->
                    InventoryRow(
                        entry = entry,
                        onChange = { delta ->
                            onOperation(CheatOperation.AddInventory(kind, entry.id, delta))
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun InventoryKindChips(
    kind: CheatInventoryKind,
    onKindChanged: (CheatInventoryKind) -> Unit,
) {
    CenteredChipRow {
        CheatInventoryKind.entries.forEach { candidate ->
            DenseChip(
                selected = kind == candidate,
                onClick = { onKindChanged(candidate) },
                label = when (candidate) {
                    CheatInventoryKind.ITEM -> stringResource(R.string.inventory_items)
                    CheatInventoryKind.WEAPON -> stringResource(R.string.inventory_weapons)
                    CheatInventoryKind.ARMOR -> stringResource(R.string.inventory_armor)
                },
            )
        }
    }
}

@Composable
private fun InventoryRow(entry: CheatCatalogEntry, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "#${entry.id}  ${entry.name}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.cheat_owned, entry.value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onChange(-1) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "-1", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = { onChange(1) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, contentDescription = "+1", modifier = Modifier.size(16.dp))
        }
        TextButton(
            onClick = { onChange(10) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) { Text("+10") }
        TextButton(
            onClick = { onChange(99) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) { Text("+99") }
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
    val filtered = remember(source, query) {
        val needle = query.trim()
        source.filter { entry ->
            needle.isEmpty() ||
                entry.id.toString().contains(needle) ||
                entry.name.contains(needle, ignoreCase = true) ||
                entry.value.contains(needle, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 420.dp
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactSearchField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier.weight(1f),
                    )
                    CenteredChipRow {
                        DenseChip(
                            selected = kind == 0,
                            onClick = {
                                kind = 0
                                selectedVariableId = -1
                            },
                            label = stringResource(R.string.catalog_variables),
                        )
                        DenseChip(
                            selected = kind == 1,
                            onClick = {
                                kind = 1
                                selectedVariableId = -1
                            },
                            label = stringResource(R.string.catalog_switches),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CenteredChipRow {
                        DenseChip(
                            selected = kind == 0,
                            onClick = {
                                kind = 0
                                selectedVariableId = -1
                            },
                            label = stringResource(R.string.catalog_variables),
                        )
                        DenseChip(
                            selected = kind == 1,
                            onClick = {
                                kind = 1
                                selectedVariableId = -1
                            },
                            label = stringResource(R.string.catalog_switches),
                        )
                    }
                    CompactSearchField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (kind == 0 && selectedVariable != null) {
            val entry = selectedVariable!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactValueField(
                    value = variableDraft,
                    onValueChange = { variableDraft = it.take(160) },
                    placeholder = "#${entry.id}",
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                    textAlignStart = true,
                )
                TextButton(
                    onClick = {
                        onOperation(CheatOperation.SetVariable(entry.id, variableDraft))
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.set_variable)) }
            }
        }
        Text(
            text = if (query.isBlank()) {
                stringResource(R.string.catalog_entries, filtered.size)
            } else {
                stringResource(R.string.catalog_matches, filtered.size, source.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            lazyItems(filtered, key = { "${kind}-${it.id}" }) { entry ->
                if (kind == 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedVariableId = entry.id
                                variableDraft = entry.value.take(160)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.id.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = entry.value,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                    onOperation(CheatOperation.SetSwitch(entry.id, !enabled))
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.id.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

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
    var actorIdText by remember { mutableStateOf(catalog.actors.firstOrNull()?.id?.toString() ?: "1") }
    var paramIdText by remember { mutableStateOf("0") }
    var amountText by remember { mutableStateOf("10") }
    val mapId = mapIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val mapX = mapXText.toIntOrNull()?.takeIf { it in 0..9999 }
    val mapY = mapYText.toIntOrNull()?.takeIf { it in 0..9999 }
    val actorId = actorIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val paramId = paramIdText.toIntOrNull()?.takeIf { it in 0..7 }
    val amount = amountText.toIntOrNull()?.takeIf { it in -1_000_000_000..1_000_000_000 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                R.string.cheat_current_map,
                catalog.mapId,
                catalog.mapX,
                catalog.mapY,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactValueField(
                value = mapIdText,
                onValueChange = { mapIdText = it.filter(Char::isDigit).take(4) },
                placeholder = stringResource(R.string.map_id),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactValueField(
                value = mapXText,
                onValueChange = { mapXText = it.filter(Char::isDigit).take(4) },
                placeholder = "X",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactValueField(
                value = mapYText,
                onValueChange = { mapYText = it.filter(Char::isDigit).take(4) },
                placeholder = "Y",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                if (mapId != null && mapX != null && mapY != null) {
                    onOperation(CheatOperation.Teleport(mapId, mapX, mapY))
                }
            },
            enabled = mapId != null && mapX != null && mapY != null,
        ) { Text(stringResource(R.string.teleport)) }
        repeat(3) { slot ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                TextButton(
                    onClick = { onOperation(CheatOperation.SavePosition(slot)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.save_position, slot + 1)) }
                TextButton(
                    onClick = { onOperation(CheatOperation.RecallPosition(slot)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.recall_position, slot + 1)) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel(stringResource(R.string.character_progress))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactValueField(
                value = actorIdText,
                onValueChange = { actorIdText = it.filter(Char::isDigit).take(4) },
                placeholder = stringResource(R.string.actor_id),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactValueField(
                value = paramIdText,
                onValueChange = { paramIdText = it.filter(Char::isDigit).take(1) },
                placeholder = stringResource(R.string.parameter_id),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactValueField(
                value = amountText,
                onValueChange = {
                    amountText = it.filterIndexed { index, char ->
                        char.isDigit() || (char == '-' && index == 0)
                    }.take(11)
                },
                placeholder = stringResource(R.string.amount),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                if (actorId != null && paramId != null && amount != null) {
                    onOperation(CheatOperation.AddParameter(actorId, paramId, amount))
                }
            },
            enabled = actorId != null && paramId != null && amount != null,
        ) { Text(stringResource(R.string.add_parameter)) }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_catalog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
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
) {
    val align = if (textAlignStart) TextAlign.Start else TextAlign.Center
    val boxAlign = if (textAlignStart) Alignment.CenterStart else Alignment.Center
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp),
                contentAlignment = boxAlign,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun SpeedControl(
    title: String,
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$title  ${"%.1f".format(value)}x",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CenteredChipRow {
            SPEED_PRESETS.forEach { preset ->
                DenseChip(
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CenteredChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun DenseChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        },
        modifier = Modifier.height(28.dp),
        colors = FilterChipDefaults.filterChipColors(),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
        ),
    )
}

@Composable
private fun CompactToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
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
