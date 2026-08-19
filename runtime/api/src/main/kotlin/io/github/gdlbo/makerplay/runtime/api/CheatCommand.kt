package io.github.gdlbo.makerplay.runtime.api

data class CheatCommand(
    val sequence: Long,
    val operation: CheatOperation,
)

data class CheatFlags(
    val godMode: Boolean = false,
    val infiniteHp: Boolean = false,
    val infiniteMp: Boolean = false,
    val playerSpeedMultiplier: Double = 1.0,
    val noClip: Boolean = false,
)

data class CheatCatalogEntry(
    val id: Int,
    val name: String,
    val value: String,
)

data class CheatCatalog(
    val variables: List<CheatCatalogEntry> = emptyList(),
    val switches: List<CheatCatalogEntry> = emptyList(),
)

enum class RecoveryTarget {
    LEADER,
    PARTY,
    ENEMIES,
    ALL,
}

enum class CheatResource {
    MP,
    TP,
}

enum class CheatInventoryKind { ITEM, WEAPON, ARMOR }

sealed interface CheatOperation {
    data class SetFlags(
        val godMode: Boolean,
        val infiniteHp: Boolean,
        val infiniteMp: Boolean,
        val playerSpeedMultiplier: Double = 1.0,
        val noClip: Boolean = false,
    ) : CheatOperation

    data class AddGold(val amount: Int) : CheatOperation

    data class AddExperience(val actorId: Int, val amount: Int) : CheatOperation

    data class AddParameter(val actorId: Int, val parameterId: Int, val amount: Int) :
        CheatOperation

    data class AddInventory(val kind: CheatInventoryKind, val id: Int, val amount: Int) :
        CheatOperation

    data class Teleport(val mapId: Int, val x: Int, val y: Int) : CheatOperation

    data class SavePosition(val slot: Int) : CheatOperation

    data class RecallPosition(val slot: Int) : CheatOperation

    data class SetVariable(val id: Int, val value: Double) : CheatOperation

    data class SetSwitch(val id: Int, val enabled: Boolean) : CheatOperation

    data class Recover(val target: RecoveryTarget) : CheatOperation

    data class RefillResource(val target: RecoveryTarget, val resource: CheatResource) :
        CheatOperation

    data class ClearStates(val target: RecoveryTarget) : CheatOperation

    data class SetHpToOne(val target: RecoveryTarget) : CheatOperation

    data class Defeat(val target: RecoveryTarget) : CheatOperation

    data object RefreshCatalog : CheatOperation
}