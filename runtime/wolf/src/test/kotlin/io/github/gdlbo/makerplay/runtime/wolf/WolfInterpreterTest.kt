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
        val moves = mutableListOf<EventCommand>()
        val database = mutableListOf<EventCommand>()
        val parties = mutableListOf<EventCommand>()
        val mapEffects = mutableListOf<EventCommand>()
        val scrolls = mutableListOf<EventCommand>()
        val effects = mutableListOf<EventCommand>()
        val chipChanges = mutableListOf<EventCommand>()
        val banInput = mutableListOf<Boolean>()
        val autoInputs = mutableListOf<EventCommand>()
        var saveLoads = 0
        var returnToTitle = 0
        var endGames = 0
        var keyWaitPolls = 0
        var keyWaitResult = true
        var keyPollResult: Int = 0
        var moveFinished = true

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
        override fun onMove(command: EventCommand) { moves.add(command) }
        override fun onMoveFinished(): Boolean = moveFinished
        override fun onDatabase(command: EventCommand) { database.add(command) }
        override fun onParty(command: EventCommand) { parties.add(command) }
        override fun onMapEffect(command: EventCommand) { mapEffects.add(command) }
        override fun onScroll(command: EventCommand) { scrolls.add(command) }
        override fun onEffect(command: EventCommand) { effects.add(command) }
        override fun onChipChange(command: EventCommand) { chipChanges.add(command) }
        override fun onBanInput(banned: Boolean) { banInput.add(banned) }
        override fun onAutoInput(command: EventCommand) { autoInputs.add(command) }
        override fun onSaveLoad(): Boolean { saveLoads++; return true }
        override fun onReturnToTitle() { returnToTitle++ }
        override fun onEndGame() { endGames++ }
        override fun onKeyWait(command: EventCommand): Boolean {
            keyWaitPolls++
            return keyWaitResult
        }
        override fun onKeyPoll(): Int = keyPollResult
        var pointer: Pair<Int, Int>? = null
        val hovered = mutableListOf<Int>()
        override fun onPointerPosition(): Pair<Int, Int>? = pointer
        override fun onPictureAt(x: Int, y: Int, firstSlot: Int, count: Int): Int {
            hovered.add(firstSlot + count + x * 0 + y * 0)
            return if (pointer != null && count > 0) count - 1 else -1
        }
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
                cmd(121, params = listOf(10, 42, 0)),   // v10 = 42
                cmd(121, params = listOf(10, 8, 0, 0x100)), // v10 += 8
                cmd(121, params = listOf(11, 3, 0, 0x200)), // v11 -= 3 → -3
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
                cmd(111, params = listOf(1, 3, 10, 2)), // if v3 == 10
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
                cmd(111, params = listOf(1, 3, 10, 2)), // if v3 == 10
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

    @Test
    fun v35TeleportUsesFourthParameterAsDestinationMap() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(listOf(cmd(130, params = listOf(-1, 12, 34, 7, 0))))
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

    @Test
    fun loadPictureCustomResolvesStringAndSelfVariableFileNames() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(122, params = listOf(3_000_005), strings = listOf("SystemFile/title.jpg")),
                cmd(122, params = listOf(1_600_006), strings = listOf("SystemFile/logo.png")),
                cmd(152, params = listOf(0, 20, 3_000_005, 0, 1, 1, 255, 30, 40)),
                cmd(152, params = listOf(0, 21, 1_600_006, 0, 1, 1, 255, 50, 60)),
            ),
        )

        interpreter.tick()

        assertEquals(2, h.pictures.size)
        assertEquals(150, h.pictures[0].commandType)
        assertEquals(listOf("SystemFile/title.jpg"), h.pictures[0].strings)
        assertEquals(listOf("SystemFile/logo.png"), h.pictures[1].strings)
        assertEquals(20, h.pictures[0].params[1])
        assertEquals(30, h.pictures[0].params[7])
        assertEquals(40, h.pictures[0].params[8])
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
    fun commonEventByIdResolvesV35Namespace() {
        val h = host()
        val interpreter = WolfInterpreter(
            h,
            commonEvents = mapOf(15 to listOf(cmd(101, strings = listOf("NAMESPACED")))),
        )
        interpreter.start(listOf(cmd(210, params = listOf(600_015))))
        interpreter.tick()
        assertEquals(listOf("NAMESPACED"), h.messages)
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

    // --- control flow: loops, labels, input ---------------------------------

    @Test
    fun loopTimesResolvesVariableReferenceCount() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(121, params = listOf(7, 4)), // v7 = 4
                cmd(179, params = listOf(2_000_007)), // loop 4x = (v7) via var ref
                cmd(101, strings = listOf("tick")),
                cmd(498),
                cmd(101, strings = listOf("after")),
            ),
        )
        // One tick runs the full loop body (messages queue in order); the
        // message at body start blocks, so execute stepwise.
        repeat(4) {
            interpreter.tick()
            interpreter.advance()
        }
        interpreter.tick()
        assertEquals(listOf("tick", "tick", "tick", "tick", "after"), h.messages.take(5))
    }

    @Test
    fun loopTimesRunsBodyConfiguredIterations() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // 179 LoopTimes(3); 121 SetVariable(v1, +1); 498 LoopEnd
        interpreter.start(
            listOf(
                cmd(179, params = listOf(3)),
                cmd(121, params = listOf(1, 1, 0, 0x100)),
                cmd(498),
            ),
        )
        interpreter.tick()
        assertTrue(interpreter.finished)
        assertEquals(3, interpreter.variables[1])
    }

    @Test
    fun infiniteLoopWithBreakExitsEarly() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // 170 StartLoop; increment v1; if v1 >= 3 break (111 unsatisfied path
        // inverted: use satisfied branch to break); 171 BreakLoop; 498 LoopEnd
        interpreter.start(
            listOf(
                cmd(170),
                cmd(121, params = listOf(1, 1, 0, 0x100)), // v1 += 1
                cmd(111, params = listOf(1, 1, 2, 1)), // if v1 >= 2 (op 1)
                cmd(171), // BreakLoop
                cmd(499),
                cmd(498),
            ),
        )
        interpreter.tick()
        assertTrue(interpreter.finished)
        assertEquals(2, interpreter.variables[1])
    }

    @Test
    fun loop2RepeatsUntilConditionFails() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.variables[2] = 0
        // 176 Loop2(cond: v2 < 3 → ref=2, expected=3, op=4 less-than)
        // body: v2 += 1; 498
        interpreter.start(
            listOf(
                cmd(176, params = listOf(2, 3, 4)),
                cmd(121, params = listOf(2, 1, 0, 0x100)),
                cmd(498),
            ),
        )
        interpreter.tick()
        assertTrue(interpreter.finished)
        assertEquals(3, interpreter.variables[2])
    }

    @Test
    fun jumpToLabelResumesAfterLabelMarker() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(213, params = listOf(7)), // jump to label 7
                cmd(101, strings = listOf("skipped")),
                cmd(212, params = listOf(7)), // label 7
                cmd(101, strings = listOf("landed")),
            ),
        )
        interpreter.tick()
        assertEquals(listOf("landed"), h.messages)
    }

    @Test
    fun keyWaitPollStoresPressedKey() {
        val h = host()
        h.keyPollResult = 5
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(123, params = listOf(0, 1)),
                cmd(101, strings = listOf("after key")),
            ),
        )
        interpreter.tick()
        assertEquals(5, interpreter.variables[0])
        assertTrue(interpreter.currentBlocking() is WolfInterpreter.Blocking.Message)
    }

    @Test
    fun moveAndWaitBlocksUntilMoveFinishes() {
        val h = host()
        h.moveFinished = false
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(201, params = listOf(1)),
                cmd(202),
                cmd(101, strings = listOf("arrived")),
            ),
        )
        interpreter.tick()
        assertEquals(1, h.moves.size)
        assertTrue(h.messages.isEmpty())
        h.moveFinished = true
        interpreter.tick()
        assertEquals(listOf("arrived"), h.messages)
    }

    @Test
    fun breakLoopFromChoiceSubframeExitsParentLoop() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // Mirrors DB OP-skip menu: loop { choices; 401/2 -> BreakLoop }; after loop set v1=9
        interpreter.start(
            listOf(
                cmd(170),
                cmd(102, strings = listOf("Watch", "Skip")),
                cmd(401, params = listOf(2)),
                cmd(171),
                cmd(401, params = listOf(3)),
                cmd(121, params = listOf(1, 1, 0, 0)),
                cmd(171),
                cmd(499),
                cmd(498),
                cmd(121, params = listOf(1, 9, 0, 0)),
            ),
        )
        interpreter.tick()
        assertTrue(interpreter.currentBlocking() is WolfInterpreter.Blocking.Choices)
        interpreter.choose(0) // Watch -> BreakLoop in subframe
        interpreter.tick()
        assertEquals(9, interpreter.variables[1])
        assertTrue(interpreter.finished || interpreter.currentBlocking() == null)
    }

    @Test
    fun engineHooksFireForDatabasePartyEffectsAndFlow() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(250, params = listOf(1, 2, 3)),
                cmd(270, params = listOf(1, 1)),
                cmd(280),
                cmd(281),
                cmd(290),
                cmd(242),
                cmd(240),
                cmd(126, params = listOf(1)),
                cmd(174),
                cmd(175),
            ),
        )
        interpreter.tick()
        assertEquals(1, h.database.size)
        assertEquals(1, h.parties.size)
        assertEquals(1, h.mapEffects.size)
        assertEquals(1, h.scrolls.size)
        assertEquals(1, h.effects.size)
        assertEquals(2, h.chipChanges.size)
        assertEquals(listOf(true), h.banInput)
        assertEquals(1, h.returnToTitle)
        assertEquals(1, h.endGames)
        assertTrue(h.unhandled.isEmpty())
    }

    // --- remaining reached families ---------------------------------------

    @Test
    fun autoInputAndSaveLoadReachTheirHooks() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        interpreter.start(
            listOf(
                cmd(125, params = listOf(1, 0)),
                cmd(220),
            ),
        )
        interpreter.tick()
        assertEquals(1, h.autoInputs.size)
        assertEquals(1, h.saveLoads)
        assertTrue("125/220 must not fall through to onUnhandled", h.unhandled.isEmpty())
    }

    @Test
    fun setVariableExUsesTheSameVariableBank() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // 124 assigns from a special/ex value; the interpreter must resolve its
        // destination like 121 does rather than treating it as unhandled.
        interpreter.start(listOf(cmd(124, params = listOf(7, 0, 0, 0, 1))))
        interpreter.tick()
        assertTrue("124 must be handled", h.unhandled.isEmpty())
    }

    @Test
    fun transitionTrioReachesTheScreenEffectHook() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // 160 sets the transition, 161 prepares it, 162 executes it. All three
        // share the host screen-effect channel, as the engine's own
        // sub_A1F130/sub_A1F1A0/sub_A1F1E0 trio do.
        interpreter.start(
            listOf(
                cmd(160, params = listOf(1)),
                cmd(161, params = listOf(1)),
                cmd(162, params = listOf(1)),
            ),
        )
        interpreter.tick()
        assertEquals(3, h.screenEffects.size)
        assertTrue(h.unhandled.isEmpty())
    }

    @Test
    fun pictureHitTestReportsHoveredSlotOrMinusOne() {
        val h = host()
        val interpreter = WolfInterpreter(h)
        // WOLF's picture menus read the cursor: [dest, 0x4000, firstSlot, count]
        // yields the hovered picture index, or -1 with no pointer.
        interpreter.start(listOf(cmd(124, params = listOf(7, 0x4000, 1, 3))))
        interpreter.tick()
        assertEquals("no cursor must report -1", -1, interpreter.variables[7])

        val h2 = host()
        h2.pointer = 100 to 200
        val i2 = WolfInterpreter(h2)
        i2.start(listOf(cmd(124, params = listOf(7, 0x4000, 1, 3))))
        i2.tick()
        // The recording host returns count-1, so a hit is a non-negative index.
        assertEquals(2, i2.variables[7])
        assertTrue("host must be asked to hit test", h2.hovered.isNotEmpty())
    }

    @Test
    fun pointerButtonCodeIsVisibleToInputKey() {
        // 123 polls whatever the host reports; the runtime returns mouse button
        // codes (20/21/22) while the pointer is down so picture menus match.
        val h = host()
        h.keyPollResult = 20
        val interpreter = WolfInterpreter(h)
        interpreter.start(listOf(cmd(123, params = listOf(5, 1))))
        interpreter.tick()
        assertEquals("pressed code must be stored", 20, interpreter.variables[5])
    }

    @Test
    fun everyReachedOpcodeHasAHandler() {
        // The opcodes a real play session reached on device (task-1 census).
        // None may fall through to onUnhandled, which is the task-5 gate.
        val reached = listOf(
            0, 99, 101, 102, 103, 106, 111, 112, 121, 122, 123, 124, 130, 140,
            150, 151, 170, 171, 172, 176, 179, 180, 201, 210, 212, 213, 250,
            290, 300, 401, 420, 498, 499,
        )
        for (opcode in reached) {
            val h = host()
            val interpreter = WolfInterpreter(h)
            interpreter.start(listOf(cmd(opcode, params = listOf(0, 0, 0, 0, 0))))
            interpreter.tick()
            assertTrue(
                "opcode $opcode reached in real play but hit onUnhandled",
                h.unhandled.isEmpty(),
            )
        }
    }
}
