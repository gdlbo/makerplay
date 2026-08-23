package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.MapFile
import io.github.gdlbo.makerplay.wolfformat.MoveRoute
import io.github.gdlbo.makerplay.wolfformat.TileSetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the fixed-timestep game state machine (milestone 5). */
class WolfGameEngineTest {

    private fun project(tileSize: Int = 40, speed: Int = 4) = GameDat(
        v3 = true,
        wolfVersionWord = 0x0300,
        title = "T",
        serial = "",
        encryptionKey = "K",
        startingHeroGraphic = "",
        screenWidth = 800,
        screenHeight = 600,
        tileSize = tileSize,
        fps = 60,
        heroMoveSpeed = speed,
    )

    private fun passability(vararg notPassable: String): TileSetData.Passability {
        var raw = 0
        if ("up" in notPassable) raw = raw or (1 shl 3)
        if ("right" in notPassable) raw = raw or (1 shl 2)
        if ("left" in notPassable) raw = raw or (1 shl 1)
        if ("down" in notPassable) raw = raw or 1
        return TileSetData.Passability(
            counterEnabled = false, square = false, quarterTile = false, star = false,
            topLeftPassable = false, topRightPassable = false,
            bottomLeftPassable = false, bottomRightPassable = false,
            upwardsNotPassable = "up" in notPassable,
            rightwardsNotPassable = "right" in notPassable,
            leftwardsNotPassable = "left" in notPassable,
            downwardsNotPassable = "down" in notPassable,
            downArrow = false, triangle = false, raw = raw,
        )
    }

    private fun tileset(passabilityByTile: List<TileSetData.Passability>) = TileSetData(
        v3 = true,
        tilesets = listOf(
            TileSetData.Tileset(
                title = "ts", baseTilesetFile = "MapChip/t.png",
                autoTileFiles = List(31) { "" },
                tagNumbers = emptyList(),
                tilePassability = passabilityByTile,
            ),
        ),
    )

    private fun page(
        triggerCondition: Int,
        x: Int = 0,
    ) = MapFile.Page(
        graphicChipId = 0, graphicFile = "", graphicRow = 0, graphicCol = 0, graphicOpacity = 255,
        triggerCondition = triggerCondition,
        triggerSwitchesRaw = IntArray(4),
        triggerVariables = IntArray(4), triggerValues = IntArray(4),
        route = MoveRoute(0, 0, 0, 0, 0, 0, emptyList()),
        commands = emptyList(), shadowGraphicId = 0, rangeExtensionX = 0, rangeExtensionY = 0,
    ).let { if (x > 0) it else it }

    private fun event(id: Int, x: Int, y: Int, pages: List<MapFile.Page>) =
        MapFile.MapEvent(eventId = id, title = "e$id", x = x, y = y, pages = pages)

    private fun map(
        width: Int = 4,
        height: Int = 4,
        layer0: List<Int>,
        events: List<MapFile.MapEvent> = emptyList(),
    ) = MapFile(
        v3 = true, revision = 0x66, title = "m", tilesetId = 0, width = width, height = height,
        layers = listOf(layer0.toIntArray(), IntArray(width * height), IntArray(width * height)),
        events = events,
    )

    private fun flatMapLayer(width: Int = 4, height: Int = 4, value: () -> Int) =
        List(width * height) { value() }

    @Test
    fun holdingRightMovesPlayerAndUpdatesFacing() {
        val engine = WolfGameEngine(project(), map(layer0 = flatMapLayer { 0 }))
        val startX = engine.playerPixelX
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), confirmPressed = false)
        repeat(8) { engine.tick() }
        assertEquals(WolfGameEngine.Direction.RIGHT, engine.facing)
        // 8 ticks * 0.125 tiles * 40px * 1.0x = 40px = one tile.
        assertEquals(startX + 40.0, engine.playerPixelX, 0.001)
    }

    @Test
    fun fasterSpeedMultiplierCoversMoreGround() {
        val fast = WolfGameEngine(project(speed = 8), map(layer0 = flatMapLayer { 0 }))
        fast.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        repeat(8) { fast.tick() }
        assertEquals(80.0, fast.playerPixelX, 0.001) // 2.0x
    }

    /** Tileset where chip 0 is fully open and chip 1 blocks left/right. */
    private fun twoChipTileset() = tileset(listOf(passability(), passability("left", "right")))

    @Test
    fun impassableTileBlocksMovementInThatDirection() {
        // Tile (1,y) uses chip 1 which blocks crossing leftwards/rightwards.
        val layer = flatMapLayer { 0 }.toMutableList()
        layer[1] = 1
        val engine = WolfGameEngine(project(), map(layer0 = layer), tilesets = twoChipTileset())
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        repeat(20) { engine.tick() }
        // Tile-step collision: the player never enters the blocking tile.
        assertEquals(0.0, engine.playerPixelX, 0.0)
    }

    @Test
    fun passabilityIsDirectional() {
        val layer = flatMapLayer { 0 }.toMutableList()
        layer[1] = 1 // blocks left/right but not up/down
        val ts = tileset(listOf(passability(), passability("left", "right")))
        val engine = WolfGameEngine(project(), map(layer0 = layer), tilesets = ts)
        assertTrue(engine.walkable(1, 0, WolfGameEngine.Direction.UP))
        assertTrue(!engine.walkable(1, 0, WolfGameEngine.Direction.RIGHT))
        assertTrue(!engine.walkable(1, 0, WolfGameEngine.Direction.LEFT))
    }

    @Test
    fun mapEdgesClampPosition() {
        val engine = WolfGameEngine(project(), map(layer0 = flatMapLayer { 0 }))
        engine.setInput(setOf(WolfGameEngine.Direction.UP, WolfGameEngine.Direction.LEFT), false)
        repeat(30) { engine.tick() }
        assertEquals(0.0, engine.playerPixelX, 0.001)
        assertEquals(0.0, engine.playerPixelY, 0.001)
    }

    @Test
    fun steppingOntoPlayerTouchEventFiresIt() {
        val layer = flatMapLayer { 0 }.toMutableList()
        val touchMap = map(layer0 = layer, events = listOf(event(7, x = 1, y = 0, pages = listOf(page(3)))))
        val engine = WolfGameEngine(project(), touchMap)
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        repeat(12) { engine.tick() } // cross into tile (1,0)
        val fired = engine.drainFiredTriggers()
        assertTrue(fired.any { it.eventId == 7 && it.trigger == WolfGameEngine.Trigger.PLAYER_TOUCH })
    }

    @Test
    fun confirmKeyTriggersAdjacentFacingEvent() {
        val touchMap = map(layer0 = flatMapLayer { 0 }, events = listOf(event(9, x = 1, y = 0, pages = listOf(page(0)))))
        val engine = WolfGameEngine(project(), touchMap)
        // Face right toward the adjacent event first.
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        engine.tick()
        engine.drainFiredTriggers()
        engine.setInput(emptySet(), confirmPressed = true)
        engine.tick()
        val fired = engine.drainFiredTriggers()
        assertTrue(fired.any { it.eventId == 9 && it.trigger == WolfGameEngine.Trigger.CONFIRM_KEY })
    }

    @Test
    fun bumpingIntoEventTouchEventFiresIt() {
        val layer = flatMapLayer { 0 }.toMutableList()
        layer[1] = 1 // wall so the player cannot enter the tile
        val ev = event(3, x = 1, y = 0, pages = listOf(page(4))) // event touch
        val engine = WolfGameEngine(project(), map(layer0 = layer, events = listOf(ev)), tilesets = twoChipTileset())
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        repeat(10) {
            engine.tick()
            engine.drainFiredTriggers()
        }
        // Player is pressed against (never enters) the event tile.
        assertEquals(0.0, engine.playerPixelX, 0.0)
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        engine.tick()
        val fired = engine.drainFiredTriggers()
        assertTrue(fired.any { it.eventId == 3 && it.trigger == WolfGameEngine.Trigger.EVENT_TOUCH })
    }

    @Test
    fun autorunPagesFireEveryTickWhileActive() {
        val autoMap = map(layer0 = flatMapLayer { 0 }, events = listOf(event(5, x = 99, y = 99, pages = listOf(page(1)))))
        val engine = WolfGameEngine(project(), autoMap)
        engine.setInput(emptySet(), false)
        engine.tick()
        assertEquals(1, engine.drainFiredTriggers().count { it.trigger == WolfGameEngine.Trigger.AUTORUN })
        engine.tick()
        assertEquals(1, engine.drainFiredTriggers().count { it.trigger == WolfGameEngine.Trigger.AUTORUN })
    }

    @Test
    fun conditionalPagesStayInactiveWithoutInterpreterState() {
        val conditional = MapFile.Page(
            graphicChipId = 0, graphicFile = "", graphicRow = 0, graphicCol = 0, graphicOpacity = 255,
            triggerCondition = 1,
            triggerSwitchesRaw = intArrayOf(1, 0, 0, 0), // switch condition present
            triggerVariables = IntArray(4), triggerValues = IntArray(4),
            route = MoveRoute(0, 0, 0, 0, 0, 0, emptyList()),
            commands = emptyList(), shadowGraphicId = 0, rangeExtensionX = 0, rangeExtensionY = 0,
        )
        val autoMap = map(layer0 = flatMapLayer { 0 }, events = listOf(event(6, x = 0, y = 0, pages = listOf(conditional))))
        val engine = WolfGameEngine(project(), autoMap)
        engine.tick()
        assertNull(engine.drainFiredTriggers().firstOrNull { it.eventId == 6 })
    }

    @Test
    fun transferQueueIsExposedForTheInterpreter() {
        val engine = WolfGameEngine(project(), map(layer0 = flatMapLayer { 0 }))
        engine.queueTransfer(mapId = 3, tileX = 5, tileY = 6)
        assertNotNull(engine.pendingTransfer)
        assertEquals(3 to (5 to 6), engine.pendingTransfer)
        // Frozen while a transfer is pending.
        val before = engine.playerPixelX
        engine.setInput(setOf(WolfGameEngine.Direction.RIGHT), false)
        engine.tick()
        assertEquals(before, engine.playerPixelX, 0.0)
    }
}
