package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.EventCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone-6 contract: the interpreter covers each vertical slice, exercised
 * here against synthetic fixture events built from EventCommand's model.
 */
class WolfInterpreterTest {

    // --- fixture helpers ----------------------------------------------------

    private fun cmd(
        type: Int,
        params: List<Int> = emptyList(),
        strings: List<String> = emptyList(),
        depth: Int = 0,
    ) = EventCommand(
        paramCount = if (type == 0) 0 else params.size + 1,
        commandType = type,
        params = params.toIntArray(),
        branchDepth = depth,
        strings = strings,
        route = null,
    )

    private class RecordingHost : WolfInterpreter.Host {
        val messages = mutableListOf<String>()
        val choices = mutableListOf<List<String>>()
        val sounds = mutableListOf<EventCommand>()
        val pictures = mutableListOf<EventCommand>()
        val screenEffects = mutableListOf<EventCommand>()
        val teleports = mutableListOf<Triple<Int, Int, Int>>()
        val saves = mutableListOf<Int>()
        val loads = mutableListOf<Int>()
        val unhandled = mutableListOf<EventCommand>()

        override fun onMessage(text: String) { messages.add(text) }
        override fun onChoices(options: List<String>) { choices.add(options) }
        override fun onSound(command: EventCommand) { sounds.add(command) }
        override fun onPicture(command: EventCommand) { pictures.add(command) }
        override fun onScreenEffect(command: EventCommand) { screenEffects.add(command) }
        override fun onTeleport(mapId: Int, tileX: Int, tileY: Int) {
            teleports.add(Triple(mapId, tileX, tileY))
        }
        override fun onSave(slot: Int): Boolean { saves.add(slot); return true }
        override fun onLoad(slot: Int): Boolean { loads.add(slot); return true }
        override fun onUnhandled(command: EventCommand) { unhandled.add(command) }
    }

    private fun host() = RecordingHost()

    // --- message slice ------------------------------------------------------

    @Test
    fun messageShowsTextAndBlocksUntilAdvanced() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(listOf(cmd(101, strings = listOf("こんにちは")), cmd(101, strings = listOf("次"))))
        interpreter.tick()
        assertEquals(listOf("こんにちは"), h.messages)
        assertTrue(interpreter.isBlocked())
        // Second message does not run until advance().
        interpreter.tick()
        assertEquals(1, h.messages.size)
        interpreter.advance()
        interpreter.tick()
        assertEquals(2, h.messages.size)
        interpreter.advance()
        interpreter.tick()
        assertTrue(interpreter.finished)
    }

    // --- choices slice ------------------------------------------------------

    @Test
    fun choicesPresentOptionsAndRouteToSelectedCase() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(102, strings = listOf("はい", "いいえ")),
                cmd(401, params = listOf(0), depth = 1),          // case "はい"
                cmd(101, strings = listOf("YES"), depth = 1),
                cmd(499, depth = 1),
                cmd(401, params = listOf(1), depth = 1),          // case "いいえ"
                cmd(101, strings = listOf("NO"), depth = 1),
                cmd(499, depth = 1),
            ),
        )
        interpreter.tick()
        assertEquals(listOf(listOf("はい", "いいえ")), h.choices)
        interpreter.choose(1)
        interpreter.tick()
        assertEquals(listOf("NO"), h.messages.filter { it == "NO" })
        assertFalse(h.messages.contains("YES"))
    }

    @Test
    fun choiceCancelCaseRoutesWhenCancelledFlagSet() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(102, strings = listOf("A", "B")),
                cmd(402, params = listOf(-1), depth = 1),         // cancel case
                cmd(101, strings = listOf("CANCELLED"), depth = 1),
                cmd(499, depth = 1),
            ),
        )
        interpreter.tick()
        interpreter.choose(index = 5) // out of range → cancel routing
        interpreter.tick()
        assertEquals(listOf("CANCELLED"), h.messages)
    }

    // --- variables / switches slice ------------------------------------------

    @Test
    fun setVariableAssignsAndOperates() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(121, params = listOf(10, 0, 42)),   // v10 = 42
                cmd(121, params = listOf(10, 1, 8)),    // v10 += 8
                cmd(121, params = listOf(11, 2, 3)),    // v11 -= 3 → -3 default 0
            ),
        )
        interpreter.tick()
        assertEquals(50, interpreter.variables[10])
        assertEquals(-3, interpreter.variables[11])
        assertEquals(0, h.unhandled.size)
    }

    @Test
    fun setStringStoresValue() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(listOf(cmd(122, params = listOf(5), strings = listOf("モノクロ"))))
        interpreter.tick()
        assertEquals("モノクロ", interpreter.strings[5])
    }

    // --- condition branch slice ----------------------------------------------

    @Test
    fun numberConditionTakesBranchWhenSatisfied() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.variables[3] = 10
        interpreter.start(
            listOf(
                cmd(111, params = listOf(0, 1, 3, 10, 2)), // if v3 == 10
                cmd(101, strings = listOf("EQUAL"), depth = 1),
                cmd(499, depth = 1),
                cmd(101, strings = listOf("AFTER")),
            ),
        )
        interpreter.tick()
        interpreter.advance()
        interpreter.tick()
        assertEquals(listOf("EQUAL", "AFTER"), h.messages)
    }

    @Test
    fun numberConditionSkipsBodyWhenNotSatisfied() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.variables[3] = 5
        interpreter.start(
            listOf(
                cmd(111, params = listOf(0, 1, 3, 10, 2)), // if v3 == 10
                cmd(101, strings = listOf("EQUAL"), depth = 1),
                cmd(499, depth = 1),
                cmd(101, strings = listOf("AFTER")),
            ),
        )
        interpreter.tick()
        interpreter.advance()
        interpreter.tick()
        assertEquals(listOf("AFTER"), h.messages)
    }

    @Test
    fun stringConditionComparesStoredStrings() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.strings[2] = "モノクロ"
        interpreter.start(
            listOf(
                cmd(112, params = listOf(2), strings = listOf("モノクロ"), depth = 1),
                cmd(101, strings = listOf("MATCH"), depth = 1),
                cmd(499, depth = 1),
            ),
        )
        interpreter.tick()
        interpreter.advance()
        interpreter.tick()
        assertEquals(listOf("MATCH"), h.messages)
    }

    // --- wait slice ------------------------------------------------------------

    @Test
    fun waitBlocksForConfiguredFramesThenResumes() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(180, params = listOf(3)),
                cmd(101, strings = listOf("done")),
            ),
        )
        interpreter.tick()
        val blocking = interpreter.currentBlocking()
        assertTrue(blocking is WolfInterpreter.Blocking.Wait)
        interpreter.tick() // remaining 2
        interpreter.tick() // remaining 1
        assertTrue(interpreter.isBlocked())
        interpreter.tick() // resumes and immediately hits the message
        val message = interpreter.currentBlocking()
        assertTrue(message is WolfInterpreter.Blocking.Message)
        interpreter.advance()
        interpreter.tick()
        assertEquals(listOf("done"), h.messages)
        assertFalse(interpreter.isBlocked())
    }

    // --- teleport slice ---------------------------------------------------------

    @Test
    fun teleportNotifiesHostWithMapAndPosition() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(listOf(cmd(130, params = listOf(7, 12, 34))))
        interpreter.tick()
        assertEquals(listOf(Triple(7, 12, 34)), h.teleports)
    }

    // --- sound / picture / screen effect slices ---------------------------------

    @Test
    fun soundPictureAndScreenEffectHooksFire() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(140, params = listOf(1)),
                cmd(150, params = listOf(3)),
                cmd(151, params = listOf(9)),
            ),
        )
        interpreter.tick()
        assertEquals(1, h.sounds.size)
        assertEquals(1, h.pictures.size)
        assertEquals(1, h.screenEffects.size)
        assertEquals(0, h.unhandled.size)
    }

    // --- save / load slices -------------------------------------------------------

    @Test
    fun saveAndLoadCommandsReachHostSlots() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(222, params = listOf(2)),
                cmd(221, params = listOf(2)),
            ),
        )
        interpreter.tick()
        assertEquals(listOf(2), h.saves)
        assertEquals(listOf(2), h.loads)
    }

    // --- common event slices --------------------------------------------------------

    @Test
    fun commonEventByIdRunsItsCommands() {
        val h = host()
        val common = mapOf(15 to listOf(cmd(101, strings = listOf("COMMON!"))))
        val interpreter = WolfInterpreter(h, commonEvents = common)
        interpreter.start(
            listOf(
                cmd(210, params = listOf(15)),
                cmd(101, strings = listOf("after")),
            ),
        )
        interpreter.tick()
        interpreter.advance()
        interpreter.tick()
        interpreter.advance()
        assertEquals(listOf("COMMON!", "after"), h.messages)
    }

    @Test
    fun commonEventByNameResolvesThroughNameTable() {
        val h = host()
        val byName = mapOf("オープニング" to listOf(cmd(101, strings = listOf("OP"))))
        val interpreter = WolfInterpreter(h, commonEventsByName = byName)
        interpreter.start(listOf(cmd(300, strings = listOf("オープニング"))))
        interpreter.tick()
        interpreter.advance()
        assertEquals(listOf("OP"), h.messages)
    }

    // --- robustness -------------------------------------------------------------------

    @Test
    fun unknownOpcodesAreReportedAndSkipped() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(9999, params = listOf(1)),
                cmd(101, strings = listOf("still works")),
            ),
        )
        interpreter.tick()
        interpreter.advance()
        assertEquals(1, h.unhandled.size)
        assertEquals(listOf("still works"), h.messages)
    }

    @Test
    fun breakEventStopsExecutionImmediately() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(172),
                cmd(101, strings = listOf("never")),
            ),
        )
        interpreter.tick()
        assertTrue(interpreter.finished)
        assertTrue(h.messages.isEmpty())
    }

    // --- operator semantics ---------------------------------------------------------------

    @Test
    fun comparisonOperatorTableMatchesWolfCodes() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        assertTrue(interpreter.compare(5, 0, 4)) // greater than
        assertTrue(interpreter.compare(4, 1, 4)) // greater or equal
        assertTrue(interpreter.compare(4, 2, 4)) // equal
        assertTrue(interpreter.compare(4, 3, 4)) // less or equal
        assertTrue(interpreter.compare(3, 4, 4)) // less than
        assertTrue(interpreter.compare(5, 5, 4)) // not equal
        assertTrue(interpreter.compare(12, 6, 4)) // bitwise and
        assertFalse(interpreter.compare(4, 0, 4))
    }
}
