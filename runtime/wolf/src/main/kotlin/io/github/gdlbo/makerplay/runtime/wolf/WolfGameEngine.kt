package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.MapFile
import io.github.gdlbo.makerplay.wolfformat.TileSetData
import java.util.ArrayDeque
import java.util.Deque

/**
 * Deterministic fixed-timestep WOLF game state machine (milestone 5).
 *
 * Pure JVM logic: advances player movement in sub-tile steps, resolves tile
 * passability collisions, detects event page triggers, and queues map
 * transfers. Presentation (frame composition) is driven separately; native
 * code only displays the latest composed frame.
 *
 * Time base: one tick = one logical frame at [GameDat.fps]. Movement speed
 * follows the editor's multiplier table around a 1x baseline of 1/8 tile per
 * tick (tunable once verified against real games on device).
 */
class WolfGameEngine(
    private val project: GameDat,
    private var map: MapFile,
    private val tilesets: TileSetData = TileSetData(v3 = true, tilesets = emptyList()),
    initialX: Int = 0,
    initialY: Int = 0,
) {
    companion object {
        /**
         * WOLF stores the hero move speed as a raw setting whose value maps
         * directly to pixels per logical frame: value * 0.25 px (per the
         * editor's own speed table, e.g. setting 8 moves 2 px/frame).
         */
        const val PIXELS_PER_FRAME_PER_SPEED_UNIT = 0.25
    }

    enum class Direction { UP, DOWN, LEFT, RIGHT }
    enum class Trigger { CONFIRM_KEY, AUTORUN, PARALLEL, PLAYER_TOUCH, EVENT_TOUCH }

    data class Position(val tileX: Int, val tileY: Int, val offsetX: Double = 0.0, val offsetY: Double = 0.0)

    /** A trigger whose conditions are satisfied and awaits interpreter handling. */
    data class FiredTrigger(
        val eventId: Int,
        val page: MapFile.Page,
        val trigger: Trigger,
    )

    var tickCount: Long = 0L
        private set

    // Player position as continuous pixel coordinates within the map.
    var playerPixelX: Double = initialX * project.tileSize.toDouble()
        private set
    var playerPixelY: Double = initialY * project.tileSize.toDouble()
        private set
    var facing: Direction = Direction.DOWN
        private set

    /** Set when the player walks off the map edge or an event transfers; consumed by the host. */
    var pendingTransfer: Pair<Int, Pair<Int, Int>>? = null // mapId to (tileX, tileY)
        private set

    private val firedTriggers: Deque<FiredTrigger> = ArrayDeque()
    private val heldDirections = mutableSetOf<Direction>()
    private var confirmPressedThisTick = false

    /** Tile passability cache for the current map's tileset. */
    private var passability: List<TileSetData.Passability>? =
        tilesets.tilesets.getOrNull(map.tilesetId)?.tilePassability

    /** Applies a successful map transfer and discards triggers from the prior map. */
    fun replaceMap(nextMap: MapFile, tileX: Int, tileY: Int) {
        map = nextMap
        passability = tilesets.tilesets.getOrNull(nextMap.tilesetId)?.tilePassability
        playerPixelX = tileX.coerceAtLeast(0) * project.tileSize.toDouble()
        playerPixelY = tileY.coerceAtLeast(0) * project.tileSize.toDouble()
        pendingTransfer = null
        firedTriggers.clear()
        heldDirections.clear()
    }

    fun position(): Position {
        val ts = project.tileSize.toDouble()
        return Position(
            tileX = (playerPixelX / ts).toInt(),
            tileY = (playerPixelY / ts).toInt(),
            offsetX = (playerPixelX % ts) / ts,
            offsetY = (playerPixelY % ts) / ts,
        )
    }

    /** Current input state; called once per tick before [tick]. */
    fun setInput(directions: Set<Direction>, confirmPressed: Boolean) {
        heldDirections.clear()
        heldDirections.addAll(directions)
        confirmPressedThisTick = confirmPressed
    }

    /** Advances exactly one logical frame. */
    fun tick() {
        if (pendingTransfer != null) return // frozen during transition until applied
        if (heldDirections.isNotEmpty()) {
            // Single direction per tick; diagonal movement comes later.
            val direction = orderedDirection(heldDirections)
            facing = direction
            step(direction)
        } else {
            checkActionTrigger()
        }
        detectTouchTriggersOnAdjacentTiles()
        detectStandingTriggers(Trigger.PLAYER_TOUCH)
        tickCount++
    }

    /** Drains queued triggers for the interpreter (milestone 6). */
    fun drainFiredTriggers(): List<FiredTrigger> {
        val out = mutableListOf<FiredTrigger>()
        while (firedTriggers.poll()?.let(out::add) == true) { /* drain */ }
        return out
    }

    private fun orderedDirection(directions: Set<Direction>): Direction =
        when {
            Direction.UP in directions -> Direction.UP
            Direction.DOWN in directions -> Direction.DOWN
            Direction.LEFT in directions -> Direction.LEFT
            else -> Direction.RIGHT
        }

    private fun step(direction: Direction) {
        // Movement is expressed directly in pixels per logical frame.
        val ts = project.tileSize.toDouble()
        val speed = project.heroMoveSpeed * PIXELS_PER_FRAME_PER_SPEED_UNIT
        val dx = when (direction) {
            Direction.LEFT -> -speed
            Direction.RIGHT -> speed
            else -> 0.0
        }
        val dy = when (direction) {
            Direction.UP -> -speed
            Direction.DOWN -> speed
            else -> 0.0
        }
        val targetX = playerPixelX + dx
        val targetY = playerPixelY + dy

        // Collision: WOLF resolves movement per destination tile, so the
        // player may rest flush against a blocking tile but never enter it.
        val destTileX = ((targetX + if (dx > 0) ts - 0.01 else 0.0) / ts).toInt()
        val destTileY = ((targetY + if (dy > 0) ts - 0.01 else 0.0) / ts).toInt()
        if (!walkable(destTileX, destTileY, direction)) return

        playerPixelX = targetX.coerceIn(0.0, (map.width * ts) - ts)
        playerPixelY = targetY.coerceIn(0.0, (map.height * ts) - ts)

        // Crossing into a new tile may fire touch triggers on that event.
        detectTouchTriggersOnAdjacentTiles()
    }

    internal fun walkable(tileX: Int, tileY: Int, direction: Direction): Boolean {
        if (tileX < 0 || tileY < 0 || tileX >= map.width || tileY >= map.height) return false
        val passability = this.passability ?: return true
        val layer0 = map.layers.firstOrNull() ?: return true
        val raw = layer0[tileY * map.width + tileX]
        if (raw >= 100000) return true // autotiles treated as walkable for now
        val entry = passability.getOrNull(raw) ?: return true
        return !when (direction) {
            Direction.UP -> entry.upwardsNotPassable
            Direction.DOWN -> entry.downwardsNotPassable
            Direction.LEFT -> entry.leftwardsNotPassable
            Direction.RIGHT -> entry.rightwardsNotPassable
        }
    }

    /** Events occupying a tile adjacent to the player in the faced direction. */
    internal fun eventAt(tileX: Int, tileY: Int): MapFile.MapEvent? =
        map.events.firstOrNull { it.x == tileX && it.y == tileY }

    internal fun activePage(event: MapFile.MapEvent): MapFile.Page? =
        event.pages.lastOrNull { page -> pageConditionsMet(page) }

    /**
     * Page availability requires all four packed switch conditions to hold.
     * Condition bytes with only high bits set (e.g. 0x20, the engine's default
     * "no condition" marker observed in shipped titles) are satisfied by
     * default; pages whose switch bytes are all zero are unconditional.
     */
    internal fun pageConditionsMet(page: MapFile.Page): Boolean =
        page.triggerSwitchesRaw.all { it == 0 || (it and 0x1F) == 0 }

    private fun checkActionTrigger() {
        if (!confirmPressedThisTick) return
        val ts = project.tileSize.toDouble()
        val tx = (playerPixelX / ts).toInt() + when (facing) {
            Direction.LEFT -> -1
            Direction.RIGHT -> 1
            else -> 0
        }
        val ty = (playerPixelY / ts).toInt() + when (facing) {
            Direction.UP -> -1
            Direction.DOWN -> 1
            else -> 0
        }
        fireTriggerAt(tx, ty, Trigger.CONFIRM_KEY)
    }

    private fun detectTouchTriggersOnAdjacentTiles() {
        val ts = project.tileSize.toDouble()
        val tx = (playerPixelX / ts).toInt()
        val ty = (playerPixelY / ts).toInt()
        for ((dx, dy, trigger) in listOf(
            Triple(-1, 0, Trigger.EVENT_TOUCH),
            Triple(1, 0, Trigger.EVENT_TOUCH),
            Triple(0, -1, Trigger.EVENT_TOUCH),
            Triple(0, 1, Trigger.EVENT_TOUCH),
            Triple(0, 0, Trigger.PLAYER_TOUCH),
        )) {
            fireTriggerAt(tx + dx, ty + dy, trigger)
        }
    }

    private fun detectStandingTriggers(@Suppress("UNUSED_PARAMETER") ignored: Trigger) {
        // Autorun/parallel pages fire every tick while their conditions hold.
        for (event in map.events) {
            val page = activePage(event) ?: continue
            val condition = page.triggerCondition
            if (condition == 1 || condition == 2) {
                firedTriggers.add(
                    FiredTrigger(
                        event.eventId, page,
                        if (condition == 1) Trigger.AUTORUN else Trigger.PARALLEL,
                    ),
                )
            }
        }
    }

    private fun fireTriggerAt(tileX: Int, tileY: Int, trigger: Trigger) {
        if (tileX < 0 || tileY < 0 || tileX >= map.width || tileY >= map.height) return
        val event = eventAt(tileX, tileY) ?: return
        val page = activePage(event) ?: return
        val matches = when (trigger) {
            Trigger.CONFIRM_KEY -> page.triggerCondition == 0
            Trigger.PLAYER_TOUCH -> page.triggerCondition == 3
            Trigger.EVENT_TOUCH -> page.triggerCondition == 4
            else -> false
        }
        if (matches) firedTriggers.add(FiredTrigger(event.eventId, page, trigger))
    }

    /** Applies a queued transfer result; used by tests and the future interpreter. */
    internal fun queueTransfer(mapId: Int, tileX: Int, tileY: Int) {
        pendingTransfer = mapId to (tileX to tileY)
    }
}
