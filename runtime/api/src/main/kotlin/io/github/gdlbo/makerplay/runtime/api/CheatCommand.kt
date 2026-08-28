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
    val gameSpeedMultiplier: Double = 1.0,
    val noClip: Boolean = false,
    val holdToSkipDialog: Boolean = true,
) {
    fun toSetFlags(): CheatOperation.SetFlags = CheatOperation.SetFlags(
        godMode = godMode,
        infiniteHp = infiniteHp,
        infiniteMp = infiniteMp,
        playerSpeedMultiplier = playerSpeedMultiplier,
        gameSpeedMultiplier = gameSpeedMultiplier,
        noClip = noClip,
        holdToSkipDialog = holdToSkipDialog,
    )
}

data class CheatCatalogEntry(
    val id: Int,
    val name: String,
    val value: String,
)

data class CheatActorEntry(
    val id: Int,
    val name: String,
    val level: Int,
    val hp: Int,
    val mhp: Int,
    val mp: Int,
    val mmp: Int,
    val tp: Int,
    val exp: Int,
)

data class CheatCatalog(
    val gold: Int = 0,
    val mapId: Int = 0,
    val mapX: Int = 0,
    val mapY: Int = 0,
    val actors: List<CheatActorEntry> = emptyList(),
    val items: List<CheatCatalogEntry> = emptyList(),
    val weapons: List<CheatCatalogEntry> = emptyList(),
    val armors: List<CheatCatalogEntry> = emptyList(),
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
    HP,
    MP,
    TP,
}

enum class CheatInventoryKind { ITEM, WEAPON, ARMOR }

enum class CheatActorStat { HP, MP, TP, EXP }

sealed interface CheatOperation {
    data class SetFlags(
        val godMode: Boolean,
        val infiniteHp: Boolean,
        val infiniteMp: Boolean,
        val playerSpeedMultiplier: Double = 1.0,
        val gameSpeedMultiplier: Double = 1.0,
        val noClip: Boolean = false,
        val holdToSkipDialog: Boolean = true,
    ) : CheatOperation

    data class AddGold(val amount: Int) : CheatOperation

    data class SetGold(val amount: Int) : CheatOperation

    data class AddExperience(val actorId: Int, val amount: Int) : CheatOperation

    data class AddParameter(val actorId: Int, val parameterId: Int, val amount: Int) :
        CheatOperation

    data class SetActorStat(val actorId: Int, val stat: CheatActorStat, val value: Int) :
        CheatOperation

    data class AddInventory(val kind: CheatInventoryKind, val id: Int, val amount: Int) :
        CheatOperation

    data class Teleport(val mapId: Int, val x: Int, val y: Int) : CheatOperation

    data class SavePosition(val slot: Int) : CheatOperation

    data class RecallPosition(val slot: Int) : CheatOperation

    data class SetVariable(val id: Int, val value: String) : CheatOperation

    data class SetSwitch(val id: Int, val enabled: Boolean) : CheatOperation

    data class Recover(val target: RecoveryTarget) : CheatOperation

    data class RefillResource(val target: RecoveryTarget, val resource: CheatResource) :
        CheatOperation

    data class ClearStates(val target: RecoveryTarget) : CheatOperation

    data class SetHpToOne(val target: RecoveryTarget) : CheatOperation

    data class Defeat(val target: RecoveryTarget) : CheatOperation

    data object RefreshCatalog : CheatOperation
}