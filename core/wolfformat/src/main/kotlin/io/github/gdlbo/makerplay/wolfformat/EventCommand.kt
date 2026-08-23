package io.github.gdlbo.makerplay.wolfformat

/**
 * Event command decoding shared by map events and common events.
 *
 * A command is: u1 param count, [u4 command type, raw parameter words,
 * u1 branch depth, u1 string count + strings, u1 route flag + route] — the
 * bracketed part exists only when param count > 0. Parameters are kept raw
 * here; the interpreter (milestone 6) decodes them per command type.
 */
data class EventCommand(
    /** 0 means a blank line; otherwise the command opcode word follows. */
    val paramCount: Int,
    val commandType: Int,
    /** Raw parameter words excluding the command type itself. */
    val params: IntArray,
    val branchDepth: Int,
    val strings: List<String>,
    /** Custom move route attached to this command, if any. */
    val route: MoveRoute?,
) {
    override fun equals(other: Any?): Boolean = other is EventCommand &&
        paramCount == other.paramCount &&
        commandType == other.commandType &&
        params.contentEquals(other.params) &&
        branchDepth == other.branchDepth &&
        strings == other.strings

    override fun hashCode(): Int = paramCount * 31 + commandType

    companion object {
        fun parse(reader: BoundedReader, v3: Boolean): EventCommand {
            val paramCount = reader.readU1()
            if (paramCount == 0) {
                return EventCommand(0, 0, IntArray(0), 0, emptyList(), null)
            }
            val commandType = reader.readS4()
            // param_count includes the command word itself.
            val remainingWords = paramCount - 1
            if (remainingWords < 0 || remainingWords > BoundedReader.Limits.DEFAULT.maxCount / 4) {
                throw WolfFormatException("Event command param count $paramCount out of range")
            }
            val params = IntArray(remainingWords) { reader.readS4() }
            val branchDepth = reader.readU1()
            val stringCount = reader.readU1()
            val strings = ArrayList<String>(stringCount)
            repeat(stringCount) { strings.add(reader.readString(v3)) }
            val haveRoute = reader.readU1()
            val route = if (haveRoute != 0) MoveRoute.parse(reader) else null
            return EventCommand(paramCount, commandType, params, branchDepth, strings, route)
        }

        fun parseList(reader: BoundedReader, v3: Boolean): List<EventCommand> {
            val count = reader.readCount("event command")
            val commands = ArrayList<EventCommand>(count)
            repeat(count) { commands.add(parse(reader, v3)) }
            return commands
        }
    }
}

/**
 * Custom move route: mode/options header followed by typed route steps. Steps
 * are stored raw (type byte plus argument words); the interpreter consumes them.
 */
data class MoveRoute(
    val animationFrequency: Int,
    val moveSpeed: Int,
    val moveFrequency: Int,
    val moveRouteMode: Int,
    val behaviorOptionsRaw: Int,
    val routeOptionsRaw: Int,
    val steps: List<Step>,
) {
    data class Step(val type: Int, val argsU4: IntArray, val argsU1: IntArray) {
        override fun equals(other: Any?): Boolean = other is Step &&
            type == other.type && argsU4.contentEquals(other.argsU4) && argsU1.contentEquals(other.argsU1)

        override fun hashCode(): Int = type * 31 + argsU4.size
    }

    companion object {
        fun parse(reader: BoundedReader): MoveRoute {
            val animationFrequency = reader.readU1()
            val moveSpeed = reader.readU1()
            val moveFrequency = reader.readU1()
            val moveRouteMode = reader.readU1()
            val behaviorOptions = reader.readU1()
            val routeOptions = reader.readU1()
            val stepCount = reader.readCount("route step")
            val steps = ArrayList<Step>(stepCount)
            repeat(stepCount) {
                val type = reader.readU1()
                val u4Len = reader.readU1()
                if (u4Len > 8) throw WolfFormatException("Route step with $u4Len u4 args")
                val argsU4 = IntArray(u4Len) { reader.readS4() }
                val u1Len = reader.readU1()
                if (u1Len > 8) throw WolfFormatException("Route step with $u1Len u1 args")
                val argsU1 = IntArray(u1Len) { reader.readU1() }
                steps.add(Step(type, argsU4, argsU1))
            }
            return MoveRoute(
                animationFrequency, moveSpeed, moveFrequency, moveRouteMode,
                behaviorOptions, routeOptions, steps,
            )
        }
    }
}
