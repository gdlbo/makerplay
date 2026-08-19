package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCatalogEntry
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.CheatInventoryKind
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.CheatResource
import io.github.gdlbo.makerplay.runtime.api.RecoveryTarget
import kotlin.math.abs

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
            .background(Color.Black.copy(alpha = 0.76f))
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
                .padding(12.dp)
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
    val fields = remember { CheatMenuFields() }

    Surface(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.94f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CheatMenuHeader(onClose = onClose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 600.dp) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            BoostsSection(
                                flags = flags,
                                onFlagsChanged = onFlagsChanged,
                                onRecover = { onOperation(CheatOperation.Recover(it)) },
                                onRefillResource = { target, resource ->
                                    onOperation(CheatOperation.RefillResource(target, resource))
                                },
                                onClearStates = { onOperation(CheatOperation.ClearStates(it)) },
                                onSetHpToOne = { onOperation(CheatOperation.SetHpToOne(it)) },
                                onDefeat = { onOperation(CheatOperation.Defeat(it)) },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            GameStateSection(
                                fields = fields,
                                catalog = catalog,
                                onOperation = onOperation,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        BoostsSection(
                            flags = flags,
                            onFlagsChanged = onFlagsChanged,
                            onRecover = { onOperation(CheatOperation.Recover(it)) },
                            onRefillResource = { target, resource ->
                                onOperation(CheatOperation.RefillResource(target, resource))
                            },
                            onClearStates = { onOperation(CheatOperation.ClearStates(it)) },
                            onSetHpToOne = { onOperation(CheatOperation.SetHpToOne(it)) },
                            onDefeat = { onOperation(CheatOperation.Defeat(it)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        GameStateSection(
                            fields = fields,
                            catalog = catalog,
                            onOperation = onOperation,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatMenuHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.cheats),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.cheat_menu_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, stringResource(R.string.close))
        }
    }
}

@Composable
private fun BoostsSection(
    flags: CheatFlags,
    onFlagsChanged: (CheatFlags) -> Unit,
    onRecover: (RecoveryTarget) -> Unit,
    onRefillResource: (RecoveryTarget, CheatResource) -> Unit,
    onClearStates: (RecoveryTarget) -> Unit,
    onSetHpToOne: (RecoveryTarget) -> Unit,
    onDefeat: (RecoveryTarget) -> Unit,
) {
    var actionTarget by remember { mutableStateOf(RecoveryTarget.PARTY) }
    HealthActionsSection(
        actionTarget = actionTarget,
        onTargetChanged = { actionTarget = it },
        onRecover = onRecover,
        onRefillResource = onRefillResource,
        onClearStates = onClearStates,
        onSetHpToOne = { target ->
            if (target.affectsParty()) {
                onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
            }
            onSetHpToOne(target)
        },
        onDefeat = { target ->
            if (target.affectsParty()) {
                onFlagsChanged(flags.copy(godMode = false, infiniteHp = false))
            }
            onDefeat(target)
        },
    )
    CheatSectionHeading(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.cheat_live_modifiers),
        subtitle = stringResource(R.string.cheat_live_modifiers_description),
    )
    Column {
        CheatToggle(
            icon = Icons.Default.Security,
            title = stringResource(R.string.god_mode),
            description = stringResource(R.string.god_mode_description),
            checked = flags.godMode,
            onCheckedChange = { onFlagsChanged(flags.copy(godMode = it)) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        CheatToggle(
            icon = Icons.Default.Favorite,
            title = stringResource(R.string.infinite_hp),
            description = stringResource(R.string.infinite_hp_description),
            checked = flags.infiniteHp,
            onCheckedChange = { onFlagsChanged(flags.copy(infiniteHp = it)) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        CheatToggle(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.infinite_mp),
            description = stringResource(R.string.infinite_mp_description),
            checked = flags.infiniteMp,
            onCheckedChange = { onFlagsChanged(flags.copy(infiniteMp = it)) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        CheatToggle(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.no_clip),
            description = stringResource(R.string.no_clip_description),
            checked = flags.noClip,
            onCheckedChange = { onFlagsChanged(flags.copy(noClip = it)) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SpeedHackControl(flags = flags, onFlagsChanged = onFlagsChanged)
    }
}

@Composable
private fun HealthActionsSection(
    actionTarget: RecoveryTarget,
    onTargetChanged: (RecoveryTarget) -> Unit,
    onRecover: (RecoveryTarget) -> Unit,
    onRefillResource: (RecoveryTarget, CheatResource) -> Unit,
    onClearStates: (RecoveryTarget) -> Unit,
    onSetHpToOne: (RecoveryTarget) -> Unit,
    onDefeat: (RecoveryTarget) -> Unit,
) {
    CheatSectionHeading(
        icon = Icons.Default.Favorite,
        title = stringResource(R.string.cheat_health_actions),
        subtitle = stringResource(R.string.cheat_health_actions_description),
    )
    CheatActionHeading(Icons.Default.Tune, stringResource(R.string.cheat_target))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecoveryTarget.entries.chunked(2).forEach { rowTargets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTargets.forEach { target ->
                    FilterChip(
                        selected = actionTarget == target,
                        onClick = { onTargetChanged(target) },
                        label = { Text(recoveryTargetLabel(target), maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    FilledTonalButton(onClick = { onRecover(actionTarget) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.recover_selected))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { onRefillResource(actionTarget, CheatResource.MP) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.refill_mp))
        }
        FilledTonalButton(
            onClick = { onRefillResource(actionTarget, CheatResource.TP) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.refill_tp))
        }
    }
    TextButton(
        onClick = { onClearStates(actionTarget) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.clear_states))
    }
    FilledTonalButton(
        onClick = { onSetHpToOne(actionTarget) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(hpToOneTargetLabel(actionTarget))
    }
    Button(
        onClick = { onDefeat(actionTarget) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(defeatTargetLabel(actionTarget))
    }
    Text(
        text = stringResource(R.string.cheat_health_actions_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun defeatTargetLabel(target: RecoveryTarget): String = stringResource(
    when (target) {
        RecoveryTarget.LEADER -> R.string.defeat_target_leader
        RecoveryTarget.PARTY -> R.string.defeat_target_party
        RecoveryTarget.ENEMIES -> R.string.defeat_target_enemies
        RecoveryTarget.ALL -> R.string.defeat_target_all
    },
)

@Composable
private fun hpToOneTargetLabel(target: RecoveryTarget): String = stringResource(
    when (target) {
        RecoveryTarget.LEADER -> R.string.hp_one_target_leader
        RecoveryTarget.PARTY -> R.string.hp_one_target_party
        RecoveryTarget.ENEMIES -> R.string.hp_one_target_enemies
        RecoveryTarget.ALL -> R.string.hp_one_target_all
    },
)

private fun RecoveryTarget.affectsParty(): Boolean =
    this == RecoveryTarget.LEADER || this == RecoveryTarget.PARTY || this == RecoveryTarget.ALL

@Composable
private fun SpeedHackControl(
    flags: CheatFlags,
    onFlagsChanged: (CheatFlags) -> Unit,
) {
    CheatActionHeading(Icons.Default.Speed, stringResource(R.string.speed_hack))
    Text(
        text = stringResource(R.string.speed_hack_value, flags.playerSpeedMultiplier),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Slider(
        value = flags.playerSpeedMultiplier.toFloat().coerceIn(1f, 8f),
        onValueChange = { value ->
            onFlagsChanged(flags.copy(playerSpeedMultiplier = value.toDouble()))
        },
        valueRange = 1f..8f,
        steps = 13,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.speed_hack_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GameStateSection(
    fields: CheatMenuFields,
    catalog: CheatCatalog,
    onOperation: (CheatOperation) -> Unit,
) {
    var showManualEditor by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    CheatSectionHeading(
        icon = Icons.Default.Tune,
        title = stringResource(R.string.cheat_game_state),
        subtitle = stringResource(R.string.cheat_game_state_description),
    )
    CurrencyEditor(fields = fields, onOperation = onOperation)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TextButton(onClick = { showManualEditor = !showManualEditor }) {
        Icon(Icons.Default.Edit, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (showManualEditor) R.string.hide_manual_editor else R.string.show_manual_editor,
            ),
        )
    }
    if (showManualEditor) {
        ManualEditor(fields = fields, onOperation = onOperation)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TextButton(onClick = { showAdvanced = !showAdvanced }) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (showAdvanced) R.string.hide_advanced_cheats else R.string.show_advanced_cheats))
    }
    if (showAdvanced) AdvancedCheatsSection(fields = fields, onOperation = onOperation)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    FilledTonalButton(
        onClick = {
            showCatalog = !showCatalog
            if (showCatalog) onOperation(CheatOperation.RefreshCatalog)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (showCatalog) R.string.hide_catalog else R.string.show_catalog,
            ),
        )
    }
    if (showCatalog) {
        CheatCatalogSection(
            fields = fields,
            catalog = catalog,
            onOperation = onOperation,
            onRefresh = { onOperation(CheatOperation.RefreshCatalog) },
        )
    }
}

@Composable
private fun CurrencyEditor(fields: CheatMenuFields, onOperation: (CheatOperation) -> Unit) {
    CheatActionHeading(Icons.Default.Paid, stringResource(R.string.cheat_currency))
    OutlinedTextField(
        value = fields.goldText,
        onValueChange = fields::updateGold,
        label = { Text(stringResource(R.string.gold_amount)) },
        singleLine = true,
        isError = fields.goldText.isNotEmpty() && fields.goldAmount == null,
        supportingText = {
            Text(
                if (fields.goldText.isNotEmpty() && fields.goldAmount == null) {
                    stringResource(R.string.gold_amount_error)
                } else {
                    stringResource(R.string.gold_amount_hint)
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { fields.goldAmount?.let { onOperation(CheatOperation.AddGold(it)) } },
        enabled = fields.goldAmount != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_gold))
    }
}

@Composable
private fun AdvancedCheatsSection(fields: CheatMenuFields, onOperation: (CheatOperation) -> Unit) {
    CheatActionHeading(Icons.Default.AutoAwesome, stringResource(R.string.character_progress))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = fields.actorIdText,
            onValueChange = fields::updateActorId,
            label = { Text(stringResource(R.string.actor_id)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fields.advancedAmountText,
            onValueChange = fields::updateAdvancedAmount,
            label = { Text(stringResource(R.string.amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {
                onOperation(
                    CheatOperation.AddExperience(
                        checkNotNull(fields.actorId),
                        checkNotNull(fields.advancedAmount)
                    )
                )
            },
            enabled = fields.actorId != null && fields.advancedAmount != null,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.add_experience)) }
        OutlinedTextField(
            value = fields.parameterIdText,
            onValueChange = fields::updateParameterId,
            label = { Text(stringResource(R.string.parameter_id)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    FilledTonalButton(
        onClick = {
            onOperation(
                CheatOperation.AddParameter(
                    checkNotNull(fields.actorId),
                    checkNotNull(fields.parameterId),
                    checkNotNull(fields.advancedAmount),
                ),
            )
        },
        enabled = fields.actorId != null && fields.parameterId != null && fields.advancedAmount != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.add_parameter)) }

    CheatActionHeading(Icons.Default.Add, stringResource(R.string.inventory))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        CheatInventoryKind.entries.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = fields.inventoryKind == kind,
                onClick = { fields.inventoryKind = kind },
                shape = SegmentedButtonDefaults.itemShape(index, CheatInventoryKind.entries.size),
                modifier = Modifier.weight(1f),
            ) { Text(inventoryKindLabel(kind)) }
        }
    }
    OutlinedTextField(
        value = fields.inventoryIdText,
        onValueChange = fields::updateInventoryId,
        label = { Text(stringResource(R.string.inventory_id)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FilledTonalButton(
        onClick = {
            onOperation(
                CheatOperation.AddInventory(
                    fields.inventoryKind,
                    checkNotNull(fields.inventoryId),
                    checkNotNull(fields.advancedAmount).coerceIn(-9999, 9999),
                ),
            )
        },
        enabled = fields.inventoryId != null && fields.advancedAmount != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.change_inventory)) }

    CheatActionHeading(Icons.Default.Tune, stringResource(R.string.world_tools))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = fields.mapIdText,
            onValueChange = fields::updateMapId,
            label = { Text(stringResource(R.string.map_id)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fields.mapXText,
            onValueChange = fields::updateMapX,
            label = { Text("X") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fields.mapYText,
            onValueChange = fields::updateMapY,
            label = { Text("Y") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    FilledTonalButton(
        onClick = {
            onOperation(
                CheatOperation.Teleport(
                    checkNotNull(fields.mapId),
                    checkNotNull(fields.mapX),
                    checkNotNull(fields.mapY)
                )
            )
        },
        enabled = fields.mapId != null && fields.mapX != null && fields.mapY != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.teleport)) }
    repeat(3) { slot ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { onOperation(CheatOperation.SavePosition(slot)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.save_position, slot + 1)) }
            FilledTonalButton(
                onClick = { onOperation(CheatOperation.RecallPosition(slot)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.recall_position, slot + 1)) }
        }
    }
}

@Composable
private fun inventoryKindLabel(kind: CheatInventoryKind): String = stringResource(
    when (kind) {
        CheatInventoryKind.ITEM -> R.string.inventory_items
        CheatInventoryKind.WEAPON -> R.string.inventory_weapons
        CheatInventoryKind.ARMOR -> R.string.inventory_armor
    },
)

@Composable
private fun VariableEditor(fields: CheatMenuFields, onOperation: (CheatOperation) -> Unit) {
    val variableId = fields.variableId ?: return
    val variableName = fields.variableName ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = "#$variableId  $variableName",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedTextField(
                value = fields.variableValueText,
                onValueChange = fields::updateVariableValue,
                label = { Text(stringResource(R.string.value)) },
                singleLine = true,
                isError = fields.variableValueText.isNotEmpty() && fields.variableValue == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = {
                    onOperation(
                        CheatOperation.SetVariable(
                            variableId,
                            checkNotNull(fields.variableValue),
                        ),
                    )
                },
                enabled = fields.variableValue != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.set_variable))
            }
        }
    }
}

@Composable
private fun ManualEditor(fields: CheatMenuFields, onOperation: (CheatOperation) -> Unit) {
    CheatActionHeading(Icons.Default.Tune, stringResource(R.string.cheat_variable_editor))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = fields.variableIdText,
            onValueChange = fields::updateVariableId,
            label = { Text(stringResource(R.string.variable)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fields.variableValueText,
            onValueChange = fields::updateVariableValue,
            label = { Text(stringResource(R.string.value)) },
            singleLine = true,
            isError = fields.variableValueText.isNotEmpty() && fields.variableValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    FilledTonalButton(
        onClick = {
            onOperation(
                CheatOperation.SetVariable(
                    checkNotNull(fields.variableId),
                    checkNotNull(fields.variableValue),
                ),
            )
        },
        enabled = fields.variableId != null && fields.variableValue != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.set_variable))
    }
    CheatActionHeading(Icons.Default.ToggleOn, stringResource(R.string.cheat_switch_editor))
    OutlinedTextField(
        value = fields.switchIdText,
        onValueChange = fields::updateSwitchId,
        label = { Text(stringResource(R.string.game_switch)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = fields.switchEnabled,
            onClick = { fields.switchEnabled = true },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.on)) }
        SegmentedButton(
            selected = !fields.switchEnabled,
            onClick = { fields.switchEnabled = false },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.off)) }
    }
    FilledTonalButton(
        onClick = {
            onOperation(
                CheatOperation.SetSwitch(
                    checkNotNull(fields.switchId),
                    fields.switchEnabled
                )
            )
        },
        enabled = fields.switchId != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.apply_switch))
    }
}

@Composable
private fun CheatCatalogSection(
    fields: CheatMenuFields,
    catalog: CheatCatalog,
    onOperation: (CheatOperation) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CheatCatalogKind.VARIABLES) }
    val source = if (kind == CheatCatalogKind.VARIABLES) catalog.variables else catalog.switches
    val normalizedQuery = query.trim()
    val filtered = remember(source, normalizedQuery) {
        source.filter { entry ->
            normalizedQuery.isEmpty() ||
                    entry.id.toString().contains(normalizedQuery) ||
                    entry.name.contains(normalizedQuery, ignoreCase = true) ||
                    entry.value.contains(normalizedQuery, ignoreCase = true)
        }
    }
    val visible = filtered.take(MAX_VISIBLE_CATALOG_ENTRIES)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            CheatSectionHeading(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                title = stringResource(R.string.cheat_catalog),
                subtitle = stringResource(R.string.cheat_catalog_description),
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, stringResource(R.string.refresh_catalog))
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(80) },
        label = { Text(stringResource(R.string.search_catalog)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = kind == CheatCatalogKind.VARIABLES,
            onClick = { kind = CheatCatalogKind.VARIABLES },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.catalog_variables))
        }
        SegmentedButton(
            selected = kind == CheatCatalogKind.SWITCHES,
            onClick = { kind = CheatCatalogKind.SWITCHES },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.catalog_switches))
        }
    }
    Text(
        text = stringResource(R.string.catalog_count, visible.size, filtered.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (kind == CheatCatalogKind.VARIABLES && fields.variableName != null) {
        VariableEditor(fields = fields, onOperation = onOperation)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (visible.isEmpty()) {
        Text(
            text = stringResource(R.string.catalog_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    } else {
        Column {
            visible.forEachIndexed { index, entry ->
                if (kind == CheatCatalogKind.VARIABLES) {
                    CheatCatalogRow(
                        entry = entry,
                        selected = fields.variableId == entry.id,
                        onClick = { fields.selectVariable(entry) },
                    )
                } else {
                    CheatSwitchCatalogRow(
                        entry = entry,
                        onToggle = {
                            onOperation(
                                CheatOperation.SetSwitch(
                                    id = entry.id,
                                    enabled = !entry.value.equals("ON", ignoreCase = true),
                                ),
                            )
                        },
                    )
                }
                if (index != visible.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun CheatCatalogRow(
    entry: CheatCatalogEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
                else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.id.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.edit_catalog_entry),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CheatSwitchCatalogRow(
    entry: CheatCatalogEntry,
    onToggle: () -> Unit,
) {
    val enabled = entry.value.equals("ON", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.id.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = null)
    }
}

private enum class CheatCatalogKind { VARIABLES, SWITCHES }

private const val MAX_VISIBLE_CATALOG_ENTRIES = 40

private class CheatMenuFields {
    var goldText by mutableStateOf("1000")
        private set
    var variableIdText by mutableStateOf("1")
        private set
    var variableValueText by mutableStateOf("0")
        private set
    var variableName: String? by mutableStateOf(null)
        private set
    var switchIdText by mutableStateOf("1")
        private set
    var switchEnabled by mutableStateOf(true)
    var actorIdText by mutableStateOf("1")
        private set
    var advancedAmountText by mutableStateOf("1000")
        private set
    var parameterIdText by mutableStateOf("0")
        private set
    var inventoryKind by mutableStateOf(CheatInventoryKind.ITEM)
    var inventoryIdText by mutableStateOf("1")
        private set
    var mapIdText by mutableStateOf("1")
        private set
    var mapXText by mutableStateOf("0")
        private set
    var mapYText by mutableStateOf("0")
        private set

    val goldAmount: Int?
        get() = goldText.toLongOrNull()?.takeIf { it in 0..1_000_000_000 }?.toInt()
    val variableId: Int?
        get() = variableIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val variableValue: Double?
        get() = variableValueText.toDoubleOrNull()
            ?.takeIf { it.isFinite() && abs(it) <= 1_000_000_000 }
    val switchId: Int?
        get() = switchIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val actorId: Int?
        get() = actorIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val advancedAmount: Int?
        get() = advancedAmountText.toIntOrNull()?.takeIf { it in -1_000_000_000..1_000_000_000 }
    val parameterId: Int?
        get() = parameterIdText.toIntOrNull()?.takeIf { it in 0..7 }
    val inventoryId: Int?
        get() = inventoryIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val mapId: Int?
        get() = mapIdText.toIntOrNull()?.takeIf { it in 1..9999 }
    val mapX: Int?
        get() = mapXText.toIntOrNull()?.takeIf { it in 0..9999 }
    val mapY: Int?
        get() = mapYText.toIntOrNull()?.takeIf { it in 0..9999 }

    fun updateGold(value: String) {
        goldText = value.filter(Char::isDigit).take(10)
    }

    fun updateVariableId(value: String) {
        variableIdText = value.filter(Char::isDigit).take(4)
    }

    fun updateVariableValue(value: String) {
        variableValueText = value.replace(',', '.').take(12)
    }

    fun selectVariable(entry: CheatCatalogEntry) {
        variableName = entry.name
        variableIdText = entry.id.toString()
        variableValueText = entry.value.toDoubleOrNull()?.toString().orEmpty()
    }

    fun updateSwitchId(value: String) {
        switchIdText = value.filter(Char::isDigit).take(4)
    }

    fun updateActorId(value: String) {
        actorIdText = value.filter(Char::isDigit).take(4)
    }

    fun updateAdvancedAmount(value: String) {
        advancedAmountText =
            value.filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }
                .take(11)
    }

    fun updateParameterId(value: String) {
        parameterIdText = value.filter(Char::isDigit).take(1)
    }

    fun updateInventoryId(value: String) {
        inventoryIdText = value.filter(Char::isDigit).take(4)
    }

    fun updateMapId(value: String) {
        mapIdText = value.filter(Char::isDigit).take(4)
    }

    fun updateMapX(value: String) {
        mapXText = value.filter(Char::isDigit).take(4)
    }

    fun updateMapY(value: String) {
        mapYText = value.filter(Char::isDigit).take(4)
    }
}

@Composable
private fun CheatSectionHeading(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheatActionHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheatToggle(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}