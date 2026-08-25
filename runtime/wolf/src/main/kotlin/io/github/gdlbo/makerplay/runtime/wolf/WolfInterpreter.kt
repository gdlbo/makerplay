package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.EventCommand

/**
 * Vertical-slice event interpreter (milestone 6).
 *
 * Executes [EventCommand] lists from map pages and common events against a
 * small machine state: numeric/string variable maps plus presentation hooks.
 * Blocking states (messages, choices, waits) suspend execution until the host
 * resolves them, mirroring the original engine's flow.
 *
 * Command codes follow the documented WolfRPG set (Sinflower/WolfTL table,
 * cross-checked with djytw/wolf-rpg-formats): 101 message, 102 choices,
 * 111/112 conditions, 121/122/124 assignments, 130 teleport, 140 sound,
 * 150 picture, 151 screen color, 160-162 transitions, 170/171/179/498 loops,
 * 180 wait, 210/211/300 common events, 220/221/222 save/load, 401/402/420/421
 * choice cases, 499 branch end, 103 comments, 172 break event. Unimplemented
 * opcodes are skipped and reported through [Host.onUnhandled] so real games
 * degrade gracefully instead of stalling.
 */
class WolfInterpreter(
    private val host: Host,
    private val commonEvents: Map<Int, List<EventCommand>> = emptyMap(),
    private val commonEventsByName: Map<String, List<EventCommand>> = emptyMap(),
    initialVariables: Map<Int, Int> = emptyMap(),
    initialStrings: Map<Int, String> = emptyMap(),
) {
    interface Host {
        /** Shows a message window; call [advance] to resume once dismissed. */
        fun onMessage(text: String)

        /** Shows a choice window; call [choose] with the selected option index. */
        fun onChoices(options: List<String>)

        /** Sound hook (BGM/SE); [opcode] is the raw command type (140). */
        fun onSound(command: EventCommand) {}

        /** Picture lifecycle hook (show/move/erase), opcode 150. */
        fun onPicture(command: EventCommand) {}

        /** Screen color/effect hook, opcodes 151/160/161/162. */
        fun onScreenEffect(command: EventCommand) {}

        /** Player transfer request; the engine applies it after the event ends. */
        fun onTeleport(mapId: Int, tileX: Int, tileY: Int) {}

        /** Save/load hooks; return true when the operation succeeded. */
        fun onSave(slot: Int): Boolean = true
        fun onLoad(slot: Int): Boolean = false

        /** Key-wait poll (123); return true once the awaited key is pressed. */
        fun onKeyWait(command: EventCommand): Boolean = true

        /** InputKey poll (123): currently-pressed WOLF key id, 0 = none.
         *  WOLF ids: 1 up, 2 down, 3 left, 4 right, 5 decide, 6 cancel, 7 shift. */
        fun onKeyPoll(): Int = 0

        /** Diagnostic hook for version-specific condition layouts. */
        fun onCondition(command: EventCommand, satisfied: Boolean) {}

        /** Narrow runtime trace hook for difficult v3.5 event flows. */
        fun onCommand(command: EventCommand) {}

        /** Input gating (125 auto input, 126 ban/unban). */
        fun onAutoInput(command: EventCommand) {}
        fun onBanInput(banned: Boolean) {}

        /** Event movement (201 move, 202 wait-for-move poll). */
        fun onMove(command: EventCommand) {}
        fun onMoveFinished(): Boolean = true

        /** Database operations (250 read/write, 251/252/255 import family). */
        fun onDatabase(command: EventCommand) {}

        /** Party changes (270). */
        fun onParty(command: EventCommand) {}

        /** Map-level effects: 280 map effect, 281 scroll, 290 effect. */
        fun onMapEffect(command: EventCommand) {}
        fun onScroll(command: EventCommand) {}
        fun onEffect(command: EventCommand) {}

        /** Tile changes (240 chip, 242 chipset). */
        fun onChipChange(command: EventCommand) {}

        /** Flow exits: 174 return to title, 175 end game. */
        fun onReturnToTitle() {}
        fun onEndGame() {}

        /** Called for opcodes this interpreter does not implement yet. */
        fun onUnhandled(command: EventCommand) {}
    }

    sealed class Blocking {
        data class Message(val text: String) : Blocking()
        data class Choices(val options: List<String>) : Blocking()
        data class Wait(val remainingTicks: Int) : Blocking()
        data class KeyWait(val command: EventCommand) : Blocking()
        data class MoveWait(val command: EventCommand) : Blocking()
    }

    // Machine state exposed for saves and cheat surfaces.
    val variables = HashMap<Int, Int>(initialVariables)
    val strings = HashMap<Int, String>(initialStrings)

    private val frames = ArrayDeque<Frame>()
    private var blocking: Blocking? = null
    var finished: Boolean = true
        private set

    /** Active loop: where to jump back to and how many iterations remain (null = infinite). */
    private class LoopMarker(
        val frame: Frame,
        val startPc: Int,
        var remaining: Int?,
        /** Loop2 condition params re-evaluated at the 498 marker. */
        val conditionParams: IntArray?,
    )

    private val loops = ArrayDeque<LoopMarker>()

    private companion object {
        /** Generous ceiling on commands per tick; real events finish far below this. */
        const val MAX_COMMANDS_PER_TICK = 1_000_000

        /** Commands that open a skippable body closed by 499. */
        val BRANCH_OPENERS = setOf(111, 112, 170, 176, 179, 401, 402, 420, 421)

        /** Case headers of a condition/choice branch (401 family). */
        val CASE_HEADERS = setOf(401, 402, 420, 421)
    }

    private var pendingChoiceTargets: List<Pair<Int, Boolean>> = emptyList() // (jumpOffset, isCancel)
    private var pendingChoiceCancel = false

    /** Result of the most recent 102 choice, for inline 401/402/420/421 cases. */
    private var lastChoiceIndex = -1
    private var lastChoiceCancel = false
    private var lastBranchWasChoice = false
    private var lastConditionSatisfied = false

    private class Frame(
        val commands: List<EventCommand>,
        var pc: Int = 0,
        /** True for map-event pages and extracted case bodies: BreakEvent (172)
         *  in such a frame ends the entire run; called common events (300/210)
         *  break back to their caller. */
        val root: Boolean = false,
    )

    /** Starts executing an event's command list, resetting any prior run. */
    fun start(commands: List<EventCommand>) {
        frames.clear()
        loops.clear()
        blocking = null
        finished = commands.isEmpty()
        if (commands.isNotEmpty()) frames.addLast(Frame(commands, root = true))
    }

    /**
     * Advances one logical frame: executes commands until a blocking state is
     * reached, decrements active waits, and finishes cleanly when all frames
     * complete.
     */
    fun tick() {
        if (finished) return
        val current = blocking
        when (current) {
            is Blocking.Wait -> {
                val remaining = current.remainingTicks - 1
                if (remaining <= 0) {
                    blocking = null
                } else {
                    blocking = current.copy(remainingTicks = remaining)
                    return
                }
            }
            is Blocking.KeyWait -> {
                val key = host.onKeyPoll()
                if (key != 0) {
                    val target = current.command.params.getOrNull(0)
                    if (target != null) variables[decodeVariableRef(target)] = key
                    blocking = null
                } else return
            }
            is Blocking.MoveWait -> {
                if (host.onMoveFinished()) blocking = null else return
            }
            is Blocking.Message, is Blocking.Choices -> return
            null -> Unit
        }
        if (blocking != null) return // message/choice still unresolved
        runUntilBlocked()
    }

    /** Dismisses the current message window. */
    fun advance() {
        if (blocking is Blocking.Message) blocking = null
    }

    /** Selects a choice option (0-based); out-of-range selects the cancel case. */
    fun choose(index: Int) {
        val choices = blocking as? Blocking.Choices ?: return
        blocking = null
        lastChoiceIndex = index
        lastChoiceCancel = index < 0 || index >= choices.options.size
        lastBranchWasChoice = true
        val bodies = extractChoiceBodies()
        val body = bodies.cases[index]
            ?: bodies.cancel
            ?: emptyList()
        if (body.isNotEmpty()) frames.addLast(Frame(body))
        else if (bodies.resumedFromPc >= 0) frames.lastOrNull()?.let { it.pc = bodies.resumedFromPc }
    }

    /** True while a message/choice/wait is unresolved. */
    fun isBlocked(): Boolean = blocking != null || !finished && frames.isEmpty()

    /** PC of the innermost frame, for diagnostics. */
    fun currentPc(): Int = frames.lastOrNull()?.pc ?: -1

    fun currentBlocking(): Blocking? = blocking

    private fun runUntilBlocked() {
        // Safety valve: a malformed event whose loop never blocks would
        // otherwise hang the render loop; cap work per tick and bail out.
        var executed = 0
        while (!finished && blocking == null) {
            if (frames.isEmpty()) {
                finished = true
                return
            }
            val frame = frames.last()
            if (frame.pc >= frame.commands.size) {
                frames.removeLast()
                loops.removeAll { it.frame === frame }
                continue
            }
            execute(frame.commands[frame.pc++])
            if (++executed > MAX_COMMANDS_PER_TICK) {
                frames.clear()
                loops.clear()
                finished = true
                return
            }
        }
    }

    private fun execute(command: EventCommand) {
        if (command.commandType in setOf(111, 123, 130, 170, 172, 210, 121)) {
            host.onCommand(command)
        }
        when (command.commandType) {
            0 -> Unit // blank padding line
            99 -> Unit // checkpoint
            101 -> showMessage(command)
            102 -> showChoices(command)
            103 -> Unit // comment
            105, 106, 107 -> Unit // force-stop-message / debug message / clear debug text
            111 -> evaluateNumberCondition(command)
            112 -> evaluateStringCondition(command)
            121 -> setVariable(command)
            122 -> setString(command)
            123 -> {
                // InputKey returns the currently pressed key, but its wait mode
                // blocks the event when no accepted key is available. This is
                // required by menu loops that have no explicit 180 wait.
                val key = host.onKeyPoll()
                val target = command.params.getOrNull(0)
                if (key == 0) {
                    blocking = Blocking.KeyWait(command)
                } else if (target != null) {
                    variables[decodeVariableRef(target)] = key
                }
            }
            124 -> setVariablePlus(command)
            125 -> host.onAutoInput(command)
            126 -> host.onBanInput(command.params.firstOrNull() != 0)
            130 -> teleport(command)
            140 -> host.onSound(command)
            150 -> host.onPicture(resolveCommandRefs(command))
            151, 160, 161, 162 -> host.onScreenEffect(command)
            170 -> beginLoop(command, remaining = null)
            171 -> breakLoop()
            172 -> {
                // BreakEvent ends the current EVENT expansion: the top-level
                // page (or an extracted case body) ends the whole run; a
                // called common event (300/210) breaks back to its caller
                // (voice CEs end with 172 in real data).
                val frame = frames.lastOrNull()
                if (frame == null) return
                if (frame.root) {
                    frames.clear()
                    loops.clear()
                    finished = true
                } else {
                    loops.removeAll { it.frame === frame }
                    frames.removeLast()
                    if (frames.isEmpty()) finished = true
                }
            }
            174 -> host.onReturnToTitle()
            175 -> host.onEndGame()
            176 -> beginLoop2(command)
            179 -> beginLoop(command, remaining = loopCount(command))
            180 -> beginWait(command)
            201 -> host.onMove(command)
            202 -> { blocking = Blocking.MoveWait(command) }
            212 -> Unit // label marker: resolved by 213 scans
            213 -> jumpToLabel(command)
            210, 211 -> callCommonEventById(command)
            220 -> host.onSave(saveSlot(command))
            221 -> host.onLoad(saveSlot(command))
            222 -> host.onSave(saveSlot(command))
            230, 231 -> Unit // move-during-event flag: engine-level default
            240, 242 -> host.onChipChange(command)
            250, 251, 252, 255 -> host.onDatabase(command)
            270 -> host.onParty(command)
            280 -> host.onMapEffect(command)
            281 -> host.onScroll(command)
            290 -> host.onEffect(command)
            300 -> callCommonEventByName(command)
            401, 402 -> {
                // Case body marker: follows either a 102 choice or a 111/112
                // condition (true-case). Enter the body only when the source
                // branch matches. When the source is a condition whose multi-
                // case bodies are delimited by sibling headers, a matched case
                // must NOT fall through into its siblings: jumping to the end
                // of the branch and running the body as a sub-frame keeps the
                // parent pc past the branch.
                val enter = if (lastBranchWasChoice) {
                    (command.params.firstOrNull() ?: -1) == lastChoiceIndex && !lastChoiceCancel
                } else {
                    lastConditionSatisfied
                }
                if (enter) Unit else skipCurrentBranch()
            }
            420, 421 -> {
                val enter = if (lastBranchWasChoice) lastChoiceCancel else !lastConditionSatisfied
                if (enter) Unit else skipCurrentBranch()
            }
            498 -> loopEnd()
            499 -> Unit // branch end: natural flow
            else -> host.onUnhandled(command)
        }
    }

    private fun loopCount(command: EventCommand): Int? {
        // LoopTimes operators may reference a variable (>=1M); resolve it.
        // 0 runs the body zero times (skip the loop entirely).
        return operandValue(command.params.firstOrNull() ?: 0)
    }

    private fun beginLoop(command: EventCommand, remaining: Int?) {
        val frame = frames.lastOrNull() ?: return
        if (remaining != null && remaining <= 0) {
            // Zero iterations: skip the body up to the matching loop end.
            var pending = 1
            while (frame.pc < frame.commands.size) {
                val c = frame.commands[frame.pc]
                frame.pc++
                if (c.commandType in setOf(170, 176, 179)) pending++
                else if (c.commandType == 498) {
                    pending--
                    if (pending == 0) break
                }
            }
            return
        }
        loops.addLast(LoopMarker(frame, frame.pc, remaining, conditionParams = null))
    }

    private fun beginLoop2(command: EventCommand) {
        val frame = frames.lastOrNull() ?: return
        // Loop2 re-checks its condition params at the closing 498.
        loops.addLast(LoopMarker(frame, frame.pc, remaining = null, conditionParams = command.params))
    }

    private fun loopEnd() {
        val frame = frames.lastOrNull() ?: return
        // Discard markers from already-popped frames.
        loops.removeAll { it.frame !== frame }
        val marker = loops.lastOrNull() ?: return
        if (marker.conditionParams != null) {
            // Loop2: continue while the first condition pair still holds.
            val p = marker.conditionParams
            val continueLoop = if (p.size >= 3) {
                compare(variables[decodeVariableRef(p[0])] ?: 0, p[2], operandValue(p[1]))
            } else {
                false
            }
            if (continueLoop) {
                frame.pc = marker.startPc
            } else {
                loops.removeLast()
            }
        } else if (marker.remaining != null) {
            val remaining = marker.remaining!! - 1
            if (remaining > 0) {
                marker.remaining = remaining
                frame.pc = marker.startPc
            } else {
                loops.removeLast()
            }
        } else {
            frame.pc = marker.startPc
        }
    }

    private fun breakLoop() {
        val frame = frames.lastOrNull() ?: return
        loops.removeAll { it.frame !== frame }
        loops.removeLastOrNull()
        // Skip forward past the matching loop-end marker (498), accounting for
        // nested loop constructs so an inner 498 cannot end an outer loop early.
        var pending = 1
        val openers = setOf(170, 176, 179)
        while (frame.pc < frame.commands.size) {
            val command = frame.commands[frame.pc]
            frame.pc++
            if (command.commandType in openers) pending++
            else if (command.commandType == 498) {
                pending--
                if (pending == 0) break
            }
        }
    }

    private fun jumpToLabel(command: EventCommand) {
        val frame = frames.lastOrNull() ?: return
        val target = command.params.firstOrNull() ?: return
        // Scan forward from the current pc, then wrap to the frame start.
        for (i in frame.pc until frame.commands.size) {
            val candidate = frame.commands[i]
            if (candidate.commandType == 212 && candidate.params.firstOrNull() == target) {
                frame.pc = i + 1
                return
            }
        }
        for (i in 0 until frame.pc) {
            val candidate = frame.commands[i]
            if (candidate.commandType == 212 && candidate.params.firstOrNull() == target) {
                frame.pc = i + 1
                return
            }
        }
    }

    private fun beginKeyWait(command: EventCommand) {
        blocking = Blocking.KeyWait(command)
    }

    private fun showMessage(command: EventCommand) {
        val text = command.strings.joinToString("\n").ifBlank { "…" }
        blocking = Blocking.Message(text)
        host.onMessage(text)
    }

    private fun showChoices(command: EventCommand) {
        val options = command.strings.ifEmpty { listOf("…") }
        blocking = Blocking.Choices(options)
        host.onChoices(options)
    }

    private class ChoiceBodies(
        val cases: MutableMap<Int, List<EventCommand>> = mutableMapOf(),
        var cancel: List<EventCommand>? = null,
        /** Position after the full choices region; -1 while unknown. */
        var resumedFromPc: Int = -1,
    )

    /**
     * Consumes the choice-case region following a 102 command: sequences of
     * `[401 index] body� [499]`, an optional `[402|420|421] cancel body [499]`,
     * terminated by any non-case command. Returns extracted bodies and moves
     * the enclosing frame past the region.
     */
    private fun extractChoiceBodies(): ChoiceBodies {
        val result = ChoiceBodies()
        val frame = frames.lastOrNull() ?: return result
        val openers = BRANCH_OPENERS

        var activeIndex: Int? = null
        var isCancel = false
        var segment: MutableList<EventCommand>? = null
        var nesting = 0

        while (frame.pc < frame.commands.size) {
            val command = frame.commands[frame.pc]
            if (nesting == 0) {
                val startsCase = command.commandType == 401 || command.commandType == 402 ||
                    command.commandType == 420 || command.commandType == 421
                if (!startsCase) break
                activeIndex = if (command.commandType == 401) {
                    command.params.firstOrNull() ?: -1
                } else {
                    null // cancel/special case
                }
                isCancel = command.commandType != 401
                segment = mutableListOf()
                nesting = 1
                frame.pc++
                continue
            }
            // Inside a case body.
            frame.pc++
            if (command.commandType == 499) {
                nesting--
                if (nesting == 0) {
                    val collected = requireNotNull(segment)
                    if (isCancel) result.cancel = collected
                    else activeIndex?.let { result.cases[it] = collected }
                    segment = mutableListOf()
                    activeIndex = null
                    isCancel = false
                    continue
                }
            } else if (command.commandType in openers) {
                nesting++
            }
            requireNotNull(segment).add(command)
        }
        result.resumedFromPc = frame.pc
        return result
    }

    private fun evaluateNumberCondition(command: EventCommand) {
        // Real-data layout: params are packed from the leading
        // mode value ([0]) + condition triplets {variableRef, value, operator};
        // the loop is bounded by the real param count. The first satisfied
        // condition enters its case; otherwise the whole branch is skipped.
        val p = command.params
        val wantCount = p.getOrNull(0)?.coerceAtLeast(0) ?: 0
        var satisfied = false
        var matchedCases = 0
        var i = 1
        while (matchedCases < wantCount && i + 2 < p.size) {
            val ref = decodeVariableRef(p[i])
            val expected = operandValue(p[i + 1])
            val operatorCode = p[i + 2]
            val actual = variables[ref] ?: 0
            if (compare(actual, operatorCode, expected)) {
                satisfied = true
                break
            }
            i += 3
            matchedCases++
        }
        lastConditionSatisfied = satisfied
        lastBranchWasChoice = false
        if (!satisfied) {
            // Skip this conditional's whole case region to its branch end.
            skipCurrentBranch()
        } else {
            // Run ONLY the matched case body as a sub-frame and continue the
            // parent after the branch: sibling case bodies are demarcated by
            // the 401-family headers, so linear flow would fall through into
            // the next case otherwise.
            val frame = frames.lastOrNull()
            if (frame == null || p.size <= 4) return
            val headerPc = frame.pc
            var caseIndex = 0
            var cursor = headerPc
            // Walk sibling case headers: the k-th satisfied case body ends at
            // the next case header / balancing 499 / list end.
            while (cursor < frame.commands.size) {
                val head = frame.commands[cursor]
                if (head.commandType !in CASE_HEADERS) break
                val endPc = caseBodyEnd(frame, cursor)
                if (caseIndex == matchedCases) {
                    val body = frame.commands.subList(cursor, endPc).toList()
                    frame.pc = branchEndAfter(frame, endPc)
                    if (body.isNotEmpty()) frames.addLast(Frame(body, root = true))
                    return
                }
                caseIndex++
                cursor = endPc
            }
        }
        host.onCondition(command, satisfied)
    }

    /** Pure walk: pc of the first command after the case body at [headerPc]. */
    private fun caseBodyEnd(frame: Frame, headerPc: Int): Int {
        var pc = headerPc + 1
        while (pc < frame.commands.size) {
            val type = frame.commands[pc].commandType
            when (type) {
                499 -> return pc + 1
                in CASE_HEADERS -> return pc
                111, 112 -> pc = conditionClusterEnd(frame, pc)
                170, 176, 179 -> pc = loopBodyEnd(frame, pc)
                else -> pc++
            }
        }
        return pc
    }

    /** Pure walk: pc after a 111/112 and its whole case-cluster. */
    private fun conditionClusterEnd(frame: Frame, condPc: Int): Int {
        var pc = condPc + 1
        while (pc < frame.commands.size) {
            val c = frame.commands[pc]
            if (c.commandType !in CASE_HEADERS) return pc
            pc = caseBodyEnd(frame, pc)
        }
        return pc
    }

    /** Pure walk: pc after an open loop body (to its balancing 498). */
    private fun loopBodyEnd(frame: Frame, loopPc: Int): Int {
        var pc = loopPc + 1
        var depth = 1
        while (pc < frame.commands.size && depth > 0) {
            val type = frame.commands[pc].commandType
            when (type) {
                170, 176, 179 -> {
                    depth++
                    pc++
                }
                498 -> {
                    depth--
                    pc++
                }
                111, 112 -> pc = conditionClusterEnd(frame, pc)
                in CASE_HEADERS -> pc = caseBodyEnd(frame, pc)
                else -> pc++
            }
        }
        return pc
    }

    /** Pure walk: skip remaining sibling cases after [afterBodyPc]. */
    private fun branchEndAfter(frame: Frame, afterBodyPc: Int): Int {
        var pc = afterBodyPc
        while (pc < frame.commands.size) {
            val c = frame.commands[pc]
            if (c.commandType !in CASE_HEADERS) return pc
            pc = caseBodyEnd(frame, pc)
        }
        return pc
    }

    private fun evaluateStringCondition(command: EventCommand) {
        // Documented subset: compares strings[0] equality against variable key
        // in params[0]; falls through to else otherwise.
        val key = command.params.firstOrNull() ?: 0
        val expected = command.strings.firstOrNull().orEmpty()
        lastConditionSatisfied = (strings[key] ?: "") == expected
        lastBranchWasChoice = false
        if (!lastConditionSatisfied) skipCurrentBranch()
    }

    internal fun compare(actual: Int, operatorCode: Int, expected: Int): Boolean = when (operatorCode) {
        0 -> actual > expected
        1 -> actual >= expected
        2 -> actual == expected
        3 -> actual <= expected
        4 -> actual < expected
        5 -> actual != expected
        6 -> (actual and expected) != 0
        else -> false
    }

    private fun setVariable(command: EventCommand) {
        // WolfTL SetVariable: arg[3] packs the modify-op for arg[0] in the low
        // byte-pair (0x000 =, 0x100 +=, 0x200 -=, 0x300 *=, 0x400 /=, 0x500 %=,
        // 0x600 floor, 0x700 ceil, 0x800 abs) and the operator between arg[1]
        // and arg[2] in the high nibble (0x0000 +, 0x1000 -, 0x2000 *,
        // 0x3000 %, 0x5000 &, 0x7000 |, 0x8000 ^, 0x9000 <<).
        val p = command.params
        if (p.isEmpty()) return
        val target = decodeVariableRef(p[0])
        val a = operandValue(p.getOrElse(1) { 0 })
        val b = operandValue(p.getOrElse(2) { 0 })
        val packed = p.getOrElse(3) { 0 }
        val value = when (packed and 0xF000) {
            0x0000 -> a + b
            0x1000 -> a - b
            0x2000 -> a * b
            0x3000 -> if (b != 0) a % b else 0
            0x5000 -> a and b
            0x7000 -> a or b
            0x8000 -> a xor b
            0x9000 -> if (b in 0..31) a shl b else a
            else -> a + b
        }
        val current = variables[target] ?: 0
        variables[target] = when (packed and 0x0F00) {
            0x0000 -> value
            0x0100 -> current + value
            0x0200 -> current - value
            0x0300 -> current * value
            0x0400 -> if (value != 0) current / value else 0
            0x0500 -> if (value != 0) current % value else 0
            0x0600 -> kotlin.math.floor(value.toDouble()).toInt()
            0x0700 -> kotlin.math.ceil(value.toDouble()).toInt()
            0x0800 -> kotlin.math.abs(value)
            else -> value
        }
    }

    /** Resolves a 121 operand: values in variable-reference ranges are refs. */
    private fun operandValue(raw: Int): Int =
        if (raw >= 1_000_000) varValue(raw) else raw

    private fun varValue(ref: Int): Int = variables[decodeVariableRef(ref)] ?: 0

    private fun setVariablePlus(command: EventCommand) {
        // Advanced operations collapse to assignment of the second operand in
        // this subset; full operator matrix arrives with compatibility work.
        val p = command.params
        if (p.size >= 2) variables[decodeVariableRef(p[0])] = operandValue(p[1])
    }

    private fun setString(command: EventCommand) {
        val p = command.params
        if (p.isEmpty()) return
        val value = command.strings.firstOrNull().orEmpty()
        strings[decodeStringRef(p[0])] = value
    }

    /**
     * WolfRPG variable references pack their namespace in the millions digit:
     * 2000000-2999999 are normal variables, 8000000+ system variables. Both
     * map into the numeric variable space (system vars offset to negative ids
     * until engine state lands).
     */
    private fun decodeVariableRef(raw: Int): Int = when (raw) {
        in 2_000_000..2_999_999 -> raw - 2_000_000
        in 8_000_000..8_999_999 -> -(raw - 8_000_000) - 1 // system vars: negative ids
        else -> raw
    }

    private fun decodeStringRef(raw: Int): Int = when (raw) {
        in 3_000_000..3_999_999 -> raw - 3_000_000
        else -> raw
    }

    private fun teleport(command: EventCommand) {
        // WOLF location-move command: {target, x, y, mapId, flags}. The target
        // is -1 for the calling event and -2 for the protagonist. Map/position
        // operands may be variable references. Preserve the old three-field
        // subset for legacy fixtures.
        val p = command.params
        val mapIndex = if (p.size >= 4) 3 else 0
        val xIndex = if (p.size >= 4) 1 else 1
        val yIndex = if (p.size >= 4) 2 else 2
        val mapId = p.getOrNull(mapIndex)?.let(::operandValue) ?: return
        val tileX = p.getOrNull(xIndex)?.let(::operandValue) ?: return
        val tileY = p.getOrNull(yIndex)?.let(::operandValue) ?: return
        if (mapId >= 0) host.onTeleport(mapId, tileX, tileY)
    }

    private fun beginWait(command: EventCommand) {
        val frames_ = command.params.firstOrNull()?.coerceAtLeast(0) ?: 0
        if (frames_ > 0) blocking = Blocking.Wait(frames_)
    }

    private fun callCommonEventById(command: EventCommand) {
        val rawId = command.params.firstOrNull() ?: return
        // v3.5 namespaced indexed CE references: 600100 -> id 100 and
        // 500005 -> id 5 (init/party CE calls).
        val id = when {
            rawId in 600_000..699_999 -> rawId - 600_000
            rawId in 500_000..599_999 -> rawId - 500_000
            else -> rawId
        }
        val body = commonEvents[id] ?: return
        frames.addLast(Frame(body, root = false))
    }

    private fun callCommonEventByName(command: EventCommand) {
        val name = command.strings.firstOrNull() ?: return
        val body = commonEventsByName[name] ?: return
        frames.addLast(Frame(body, root = false))
    }

    private fun saveSlot(command: EventCommand): Int {
        val raw = command.params.firstOrNull() ?: 0
        return if (raw >= 1_000_000) varValue(raw) else raw
    }

    /**
     * Picture/sound parameters carry variable references (e.g. picture slot
     * stored in a system variable); resolve them against machine state before
     * handing the command to presentation hooks.
     */
    private fun resolveCommandRefs(command: EventCommand): EventCommand {
        val resolved = IntArray(command.params.size) { i ->
            val raw = command.params[i]
            if (raw >= 1_000_000 && i > 0) varValue(raw) else raw
        }
        return EventCommand(
            paramCount = command.paramCount,
            commandType = command.commandType,
            params = resolved,
            branchDepth = command.branchDepth,
            strings = command.strings,
            route = command.route,
        )
    }

    /**
     * Skips the remainder of the current conditional body. Branch structure is
     * expressed with the command stream's own depth markers: we scan forward
     * for the matching 499 using the branch depth recorded per command.
     */
    private fun skipCurrentBranch() {
        if (frames.isEmpty()) return
        val frame = frames.last()
        // v3.5 structure: a condition (111/112) is followed by its case
        // headers (401/402/420/421). Each case body extends until the next
        // SIBLING case header, its own balancing 499 (nested conditions
        // inside the body are consumed as clusters), or the list end. A false
        // condition therefore advances to the first non-case command. Bodies
        // without case headers (legacy shape) use a balanced 499 walk.
        val next = frame.commands.getOrNull(frame.pc) ?: return
        if (next.commandType !in CASE_HEADERS) {
            // Legacy header-less body: skip to the balancing branch end.
            var pending = 1
            while (frame.pc < frame.commands.size) {
                val c = frame.commands[frame.pc]
                frame.pc++
                if (c.commandType in BRANCH_OPENERS) pending++
                else if (c.commandType == 499) {
                    pending--
                    if (pending == 0) break
                }
            }
            return
        }
        while (true) {
            val head = frame.commands.getOrNull(frame.pc) ?: return
            if (head.commandType !in CASE_HEADERS) return
            skipOneCase(frame)
        }
    }

    /** frame.pc is at a case header; consumes the header and its body. */
    private fun skipOneCase(frame: Frame) {
        frame.pc++ // consume the case header
        while (frame.pc < frame.commands.size) {
            val type = frame.commands[frame.pc].commandType
            when (type) {
                499 -> {
                    frame.pc++
                    return
                }
                in CASE_HEADERS -> return // sibling case header ends this body
                111, 112 -> {
                    skipConditionCluster(frame)
                    // A nested cluster whose last consumed command is the case's
                    // own shared terminator (499) ends the enclosing case too
                    // when no further case header follows.
                    val prevType = frame.commands.getOrNull(frame.pc - 1)?.commandType
                    val nextType = frame.commands.getOrNull(frame.pc)?.commandType
                    if (prevType == 499 && nextType != null && nextType !in CASE_HEADERS) return
                    continue
                }
                170, 176, 179 -> {
                    skipLoopBody(frame)
                    continue
                }
                else -> frame.pc++
            }
        }
    }

    /** frame.pc is at a 111/112; consumes it plus its whole case cluster. */
    private fun skipConditionCluster(frame: Frame) {
        frame.pc++ // consume the condition
        while (true) {
            val next = frame.commands.getOrNull(frame.pc) ?: return
            if (next.commandType !in CASE_HEADERS) return
            skipOneCase(frame)
        }
    }

    /** frame.pc is at a loop opener; consumes the open-loop body to its 498. */
    private fun skipLoopBody(frame: Frame) {
        frame.pc++ // consume the loop opener
        var depth = 1
        while (frame.pc < frame.commands.size && depth > 0) {
            val type = frame.commands[frame.pc].commandType
            when (type) {
                170, 176, 179 -> {
                    frame.pc++
                    depth++
                }
                498 -> {
                    frame.pc++
                    depth--
                }
                111, 112 -> {
                    skipConditionCluster(frame)
                    continue
                }
                in CASE_HEADERS -> {
                    skipOneCase(frame)
                    continue
                }
                else -> frame.pc++
            }
        }
    }

    private fun applyOperation(current: Int, operation: Int, operand: Int): Int = when (operation) {
        0 -> operand // assign
        1 -> current + operand
        2 -> current - operand
        3 -> current * operand
        4 -> if (operand != 0) current / operand else 0
        5 -> if (operand != 0) current % operand else 0
        else -> operand
    }
}
