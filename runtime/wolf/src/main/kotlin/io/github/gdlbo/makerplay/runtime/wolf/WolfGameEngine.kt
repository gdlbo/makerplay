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
    /** Optional shared variable lookup for page appearance conditions. */
    private val readVariable: (Int) -> Int = { 0 },
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
    /** Autorun pages fire once per activation; cleared on map replace. */
    private val completedAutoruns = HashSet<Int>()
    private val heldDirections = mutableSetOf<Direction>()
    private var confirmPressedThisTick = false
    private var slipThrough = false
    private var heroRoute: RoutePlayback? = null
    private var scrollLocked = false
    private var scrollPixelX = 0
    private var scrollPixelY = 0
    private var shakeRemaining = 0
    private var shakePower = 0

    /** Tile passability cache for the current map's tileset. */
    private var passability: List<TileSetData.Passability>? =
        tilesets.tilesets.getOrNull(map.tilesetId)?.tilePassability

    private data class RoutePlayback(
        val steps: List<io.github.gdlbo.makerplay.wolfformat.MoveRoute.Step>,
        val waitUntilDone: Boolean,
        val skipImpossible: Boolean,
        var index: Int = 0,
        var waitFrames: Int = 0,
        var pixelsRemaining: Double = 0.0,
        var stepDirection: Direction? = null,
        var moveSpeed: Double,
        var halfTileMovement: Boolean = false,
        var blockedTries: Int = 0,
        var ageFrames: Int = 0,
    )

    /** Applies a successful map transfer and discards triggers from the prior map. */
    fun replaceMap(nextMap: MapFile, tileX: Int, tileY: Int) {
        map = nextMap
        passability = tilesets.tilesets.getOrNull(nextMap.tilesetId)?.tilePassability
        val ts = project.tileSize.toDouble()
        val clampedX = tileX.coerceIn(0, (nextMap.width - 1).coerceAtLeast(0))
        val clampedY = tileY.coerceIn(0, (nextMap.height - 1).coerceAtLeast(0))
        playerPixelX = clampedX * ts
        playerPixelY = clampedY * ts
        pendingTransfer = null
        firedTriggers.clear()
        completedAutoruns.clear()
        heldDirections.clear()
        heroRoute = null
        slipThrough = false
        scrollLocked = false
        shakeRemaining = 0
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
        advanceRouteAndEffects()
        val routeBlocksInput = heroRoute?.waitUntilDone == true && heroRoute != null
        if (!routeBlocksInput && heldDirections.isNotEmpty()) {
            // Single direction per tick; diagonal movement comes later.
            val direction = orderedDirection(heldDirections)
            facing = direction
            step(direction)
        } else if (!routeBlocksInput) {
            checkActionTrigger()
        }
        detectTouchTriggersOnAdjacentTiles()
        detectStandingTriggers(Trigger.PLAYER_TOUCH)
    }

    /** Advances non-input presentation state while an event interpreter is active. */
    fun advanceRouteAndEffects() {
        if (pendingTransfer != null) return
        tickRoutes()
        if (shakeRemaining > 0) shakeRemaining--
        tickCount++
    }

    /** Queues a custom move route on the hero (target -1/-2). */
    fun queueHeroRoute(
        steps: List<io.github.gdlbo.makerplay.wolfformat.MoveRoute.Step>,
        waitUntilDone: Boolean,
        skipImpossible: Boolean,
    ) {
        heroRoute = RoutePlayback(
            steps = steps,
            waitUntilDone = waitUntilDone,
            skipImpossible = skipImpossible,
            moveSpeed = project.heroMoveSpeed * PIXELS_PER_FRAME_PER_SPEED_UNIT,
        )
    }

    fun routesIdle(): Boolean = heroRoute == null

    fun startShake(power: Int, durationFrames: Int) {
        shakePower = power.coerceIn(0, 16)
        shakeRemaining = durationFrames.coerceIn(0, 600)
    }

    fun setScrollLock(locked: Boolean) {
        if (locked && !scrollLocked) {
            // Capture the current hero-follow camera so locking doesn't jump.
            val ts = project.tileSize
            val mapPixelW = map.width * ts
            val mapPixelH = map.height * ts
            val screenW = project.screenWidth.takeIf { it > 0 } ?: mapPixelW
            val screenH = project.screenHeight.takeIf { it > 0 } ?: mapPixelH
            scrollPixelX = (playerPixelX.toInt() + ts / 2 - screenW / 2)
                .coerceIn(0, (mapPixelW - screenW).coerceAtLeast(0))
            scrollPixelY = (playerPixelY.toInt() + ts / 2 - screenH / 2)
                .coerceIn(0, (mapPixelH - screenH).coerceAtLeast(0))
        }
        scrollLocked = locked
    }

    fun scrollBy(dx: Int, dy: Int) {
        if (!scrollLocked) setScrollLock(true)
        val maxX = (map.width * project.tileSize - project.screenWidth).coerceAtLeast(0)
        val maxY = (map.height * project.tileSize - project.screenHeight).coerceAtLeast(0)
        scrollPixelX = (scrollPixelX + dx).coerceIn(0, maxX)
        scrollPixelY = (scrollPixelY + dy).coerceIn(0, maxY)
    }

    fun unlockScroll() {
        scrollLocked = false
    }

    /** Camera pixel offset applied on top of hero-follow (shake + scroll lock). */
    fun cameraOffset(): Pair<Int, Int> {
        val shakeX = if (shakeRemaining > 0) ((tickCount % 2L) * 2L - 1L).toInt() * shakePower else 0
        val shakeY = if (shakeRemaining > 0) (((tickCount / 2) % 2L) * 2L - 1L).toInt() * shakePower else 0
        return if (scrollLocked) {
            scrollPixelX + shakeX to scrollPixelY + shakeY
        } else {
            shakeX to shakeY
        }
    }

    fun isScrollLocked(): Boolean = scrollLocked

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

    private fun tickRoutes() {
        val route = heroRoute ?: return
        route.ageFrames++
        // Hard stop so WaitForMove cannot hang the event forever on a bad step.
        if (route.ageFrames > project.fps.coerceAtLeast(1) * 8) {
            heroRoute = null
            return
        }
        if (route.waitFrames > 0) {
            route.waitFrames--
            return
        }
        // Continue a route movement until its exact requested distance is covered.
        val ongoing = route.stepDirection
        if (ongoing != null && route.pixelsRemaining > 0.0) {
            val beforeX = playerPixelX
            val beforeY = playerPixelY
            step(ongoing, route.moveSpeed.coerceAtMost(route.pixelsRemaining))
            val moved = kotlin.math.abs(playerPixelX - beforeX) + kotlin.math.abs(playerPixelY - beforeY)
            if (moved <= 0.001) {
                route.blockedTries++
                if (route.skipImpossible || route.blockedTries > 8) {
                    route.blockedTries = 0
                    route.stepDirection = null
                    route.pixelsRemaining = 0.0
                    route.index++
                }
            } else {
                route.blockedTries = 0
                route.pixelsRemaining -= moved
                if (route.pixelsRemaining <= 0.001) {
                    route.stepDirection = null
                    route.pixelsRemaining = 0.0
                    route.index++
                }
            }
            if (route.index >= route.steps.size) heroRoute = null
            return
        }
        while (route.index < route.steps.size) {
            val step = route.steps[route.index]
            when (step.type) {
                0 -> startTileStep(route, Direction.DOWN)
                1 -> startTileStep(route, Direction.LEFT)
                2 -> startTileStep(route, Direction.RIGHT)
                3 -> startTileStep(route, Direction.UP)
                8 -> { facing = Direction.DOWN; route.index++ }
                9 -> { facing = Direction.LEFT; route.index++ }
                10 -> { facing = Direction.RIGHT; route.index++ }
                11 -> { facing = Direction.UP; route.index++ }
                0x13 -> startTileStep(route, facing) // step forward
                0x14 -> startTileStep(route, opposite(facing)) // step backward
                0x1d -> {
                    route.moveSpeed = ((step.argsU4.firstOrNull() ?: project.heroMoveSpeed) *
                        PIXELS_PER_FRAME_PER_SPEED_UNIT).coerceAtLeast(0.25)
                    route.index++
                }
                0x1e, 0x1f -> route.index++
                0x20, 0x21, 0x22, 0x23, 0x24, 0x25 -> route.index++
                0x26 -> { slipThrough = true; route.index++ }
                0x27 -> { slipThrough = false; route.index++ }
                0x28, 0x29, 0x2c -> route.index++
                0x2d -> route.index++ // opacity (presentation)
                0x2f -> { // wait N frames
                    route.waitFrames = step.argsU4.firstOrNull()?.coerceIn(0, 600) ?: 0
                    route.index++
                    return
                }
                0x30 -> { route.halfTileMovement = true; route.index++ }
                0x31 -> { route.halfTileMovement = false; route.index++ }
                else -> route.index++ // unsupported step: skip
            }
            if (route.stepDirection != null) return // tile step started
        }
        heroRoute = null
    }

    private fun startTileStep(route: RoutePlayback, direction: Direction) {
        facing = direction
        route.blockedTries = 0
        route.stepDirection = direction
        route.pixelsRemaining = project.tileSize * if (route.halfTileMovement) 0.5 else 1.0
    }

    private fun opposite(direction: Direction): Direction = when (direction) {
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
    }

    private fun step(
        direction: Direction,
        speedOverride: Double = project.heroMoveSpeed * PIXELS_PER_FRAME_PER_SPEED_UNIT,
    ) {
        // Movement is expressed directly in pixels per logical frame.
        val ts = project.tileSize.toDouble()
        val speed = speedOverride.coerceAtLeast(0.0)
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
        if (!slipThrough && !walkable(destTileX, destTileY, direction)) return

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
     * Page availability: slots with clear low flags (including the 0x20 "none"
     * marker) always pass. Enabled slots compare [triggerVariables] against
     * [triggerValues] using the shared variable store when present.
     */
    internal fun pageConditionsMet(page: MapFile.Page): Boolean {
        for (i in page.triggerSwitchesRaw.indices) {
            val sw = page.triggerSwitchesRaw[i]
            // wolfrpg-map-parser: low nibble enables the slot (0 = unused,
            // including the common 0x20 filler); high nibble is CompareOperator.
            if ((sw and 0x0F) == 0) continue
            val rawVar = page.triggerVariables.getOrNull(i) ?: continue
            val expected = page.triggerValues.getOrNull(i) ?: 0
            val actual = readVariable(decodePageVarRef(rawVar))
            val ok = when ((sw ushr 4) and 0x0F) {
                0 -> actual > expected
                1 -> actual >= expected
                2 -> actual == expected
                3 -> actual <= expected
                4 -> actual < expected
                5 -> actual != expected
                6 -> (actual and expected) != 0
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private fun decodePageVarRef(raw: Int): Int = when (raw) {
        in 2_000_000..2_999_999 -> raw - 2_000_000
        in 1_000_000..1_999_999 -> raw // system/self ids used as-is
        else -> raw
    }

    private fun checkActionTrigger() {
        if (!confirmPressedThisTick) return
        val ts = project.tileSize.toDouble()
        val standX = (playerPixelX / ts).toInt()
        val standY = (playerPixelY / ts).toInt()
        // Title menus place options under the cursor/hero; field events are
        // usually faced. Try standing tile first, then the faced neighbor.
        fireTriggerAt(standX, standY, Trigger.CONFIRM_KEY)
        val tx = standX + when (facing) {
            Direction.LEFT -> -1
            Direction.RIGHT -> 1
            else -> 0
        }
        val ty = standY + when (facing) {
            Direction.UP -> -1
            Direction.DOWN -> 1
            else -> 0
        }
        if (tx != standX || ty != standY) {
            fireTriggerAt(tx, ty, Trigger.CONFIRM_KEY)
        }
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
        // Parallel pages may re-fire while active. Autorun pages run one at a
        // time and only once; marking happens when the host starts them so a
        // later autorun is not discarded by draining siblings early.
        var autorunQueued = firedTriggers.any { it.trigger == Trigger.AUTORUN }
        for (event in map.events) {
            val page = activePage(event) ?: continue
            when (page.triggerCondition) {
                1 -> {
                    if (!autorunQueued && event.eventId !in completedAutoruns) {
                        firedTriggers.add(FiredTrigger(event.eventId, page, Trigger.AUTORUN))
                        autorunQueued = true
                    }
                }
                2 -> firedTriggers.add(FiredTrigger(event.eventId, page, Trigger.PARALLEL))
            }
        }
    }

    /** Records that an autorun event has been started by the host. */
    fun markAutorunStarted(eventId: Int) {
        completedAutoruns.add(eventId)
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

    /** Drops a transfer whose destination map could not be resolved. */
    internal fun clearPendingTransfer() {
        pendingTransfer = null
    }
}
