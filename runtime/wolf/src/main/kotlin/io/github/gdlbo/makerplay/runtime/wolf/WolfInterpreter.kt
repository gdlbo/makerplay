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

        /** Called for opcodes this interpreter does not implement yet. */
        fun onUnhandled(command: EventCommand) {}
    }

    sealed class Blocking {
        data class Message(val text: String) : Blocking()
        data class Choices(val options: List<String>) : Blocking()
        data class Wait(val remainingTicks: Int) : Blocking()
    }

    // Machine state exposed for saves and cheat surfaces.
    val variables = HashMap<Int, Int>()
    val strings = HashMap<Int, String>()

    private val frames = ArrayDeque<Frame>()
    private var blocking: Blocking? = null
    var finished: Boolean = true
        private set

    private var pendingChoiceTargets: List<Pair<Int, Boolean>> = emptyList() // (jumpOffset, isCancel)
    private var pendingChoiceCancel = false

    private class Frame(
        val commands: List<EventCommand>,
        var pc: Int = 0,
    )

    /** Starts executing an event's command list, resetting any prior run. */
    fun start(commands: List<EventCommand>) {
        frames.clear()
        blocking = null
        finished = commands.isEmpty()
        if (commands.isNotEmpty()) frames.addLast(Frame(commands))
    }

    /**
     * Advances one logical frame: executes commands until a blocking state is
     * reached, decrements active waits, and finishes cleanly when all frames
     * complete.
     */
    fun tick() {
        if (finished) return
        val current = blocking
        if (current is Blocking.Wait) {
            val remaining = current.remainingTicks - 1
            if (remaining <= 0) {
                blocking = null
            } else {
                blocking = current.copy(remainingTicks = remaining)
                return
            }
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
        val bodies = extractChoiceBodies()
        val body = bodies.cases[index]
            ?: bodies.cancel
            ?: emptyList()
        if (body.isNotEmpty()) frames.addLast(Frame(body))
        else if (bodies.resumedFromPc >= 0) frames.lastOrNull()?.let { it.pc = bodies.resumedFromPc }
    }

    /** True while a message/choice/wait is unresolved. */
    fun isBlocked(): Boolean = blocking != null || !finished && frames.isEmpty()

    fun currentBlocking(): Blocking? = blocking

    private fun runUntilBlocked() {
        while (!finished && blocking == null) {
            if (frames.isEmpty()) {
                finished = true
                return
            }
            val frame = frames.last()
            if (frame.pc >= frame.commands.size) {
                frames.removeLast()
                continue
            }
            execute(frame.commands[frame.pc++])
        }
    }

    private fun execute(command: EventCommand) {
        when (command.commandType) {
            0 -> Unit // blank padding line
            99 -> Unit // checkpoint
            101 -> showMessage(command)
            102 -> showChoices(command)
            103 -> Unit // comment
            111 -> evaluateNumberCondition(command)
            112 -> evaluateStringCondition(command)
            121 -> setVariable(command)
            122 -> setString(command)
            124 -> setVariablePlus(command)
            130 -> teleport(command)
            140 -> host.onSound(command)
            150 -> host.onPicture(command)
            151, 160, 161, 162 -> host.onScreenEffect(command)
            172 -> { frames.clear(); finished = true }
            180 -> beginWait(command)
            210, 211 -> callCommonEventById(command)
            220 -> host.onSave(slotFromParams(command))
            221 -> host.onLoad(slotFromParams(command))
            222 -> host.onSave(slotFromParams(command))
            300 -> callCommonEventByName(command)
            499 -> Unit // branch end: natural flow
            498 -> Unit // loop end marker
            170, 171, 179 -> host.onUnhandled(command) // loops: milestone 8
            else -> host.onUnhandled(command)
        }
    }

    private fun showMessage(command: EventCommand) {
        val text = command.strings.joinToString("\n").ifBlank { "…" }
        blocking = Blocking.Message(text)
        host.onMessage(text)
    }

    private fun showChoices(command: EventCommand) {
        val options = command.strings.ifEmpty { listOf("…") }
        blocking = Blocking.Choices(options)
        pendingChoiceTargets = emptyList()
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
        val openers = setOf(111, 112, 170, 179)

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
        // params layout (documented subset): [0]=useElse, [1]=conditionCount,
        // then per condition: {variableRef, value, operator}. First satisfied
        // condition enters its case; otherwise jump to branch end.
        val p = command.params
        val count = p.getOrNull(1)?.coerceAtLeast(0) ?: 0
        var satisfied = false
        var matched = 0
        var i = 2
        while (matched < count && i + 2 < p.size) {
            val ref = p[i]
            val expected = p[i + 1]
            val operatorCode = p[i + 2]
            val actual = variables[ref] ?: 0
            if (compare(actual, operatorCode, expected)) {
                satisfied = true
                break
            }
            i += 3
            matched++
        }
        if (!satisfied && p.isNotEmpty()) {
            // Skip this conditional's body up to its matching branch end.
            skipCurrentBranch()
        }
    }

    private fun evaluateStringCondition(command: EventCommand) {
        // Documented subset: compares strings[0] equality against variable key
        // in params[0]; falls through to else otherwise.
        val key = command.params.firstOrNull() ?: 0
        val expected = command.strings.firstOrNull().orEmpty()
        if ((strings[key] ?: "") != expected) skipCurrentBranch()
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
        // Documented subset: params {targetRef, operation, operand}; operations
        // mirror the comparison table (0 assign-as-is handled via op 2 style).
        val p = command.params
        if (p.size < 3) return
        val target = p[0]
        val operation = p[1]
        val operand = p[2]
        val current = variables[target] ?: 0
        variables[target] = applyOperation(current, operation, operand)
    }

    private fun setVariablePlus(command: EventCommand) {
        // Advanced operations collapse to assignment of the second operand in
        // this subset; full operator matrix arrives with compatibility work.
        val p = command.params
        if (p.size >= 2) variables[p[0]] = p[1]
    }

    private fun setString(command: EventCommand) {
        val p = command.params
        if (p.isEmpty()) return
        val value = command.strings.firstOrNull().orEmpty()
        strings[p[0]] = value
    }

    private fun teleport(command: EventCommand) {
        // Documented subset: params {mapId, tileX, tileY}.
        val p = command.params
        if (p.size >= 3) host.onTeleport(p[0], p[1], p[2])
    }

    private fun beginWait(command: EventCommand) {
        val frames_ = command.params.firstOrNull()?.coerceAtLeast(0) ?: 0
        if (frames_ > 0) blocking = Blocking.Wait(frames_)
    }

    private fun callCommonEventById(command: EventCommand) {
        val id = command.params.firstOrNull() ?: return
        val body = commonEvents[id] ?: return
        frames.addLast(Frame(body))
    }

    private fun callCommonEventByName(command: EventCommand) {
        val name = command.strings.firstOrNull() ?: return
        val body = commonEventsByName[name] ?: return
        frames.addLast(Frame(body))
    }

    private fun slotFromParams(command: EventCommand): Int =
        command.params.firstOrNull() ?: 0

    /**
     * Skips the remainder of the current conditional body. Branch structure is
     * expressed with the command stream's own depth markers: we scan forward
     * for the matching 499 using the branch depth recorded per command.
     */
    private fun skipCurrentBranch() {
        if (frames.isEmpty()) return
        val frame = frames.last()
        // Skip to the matching branch end, counting nested conditionals so an
        // inner 499 cannot terminate an outer body prematurely.
        var pending = 1
        val openers = setOf(111, 112, 170, 179)
        while (frame.pc < frame.commands.size) {
            val command = frame.commands[frame.pc]
            frame.pc++
            if (command.commandType in openers) pending++
            else if (command.commandType == 499) {
                pending--
                if (pending == 0) break
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
