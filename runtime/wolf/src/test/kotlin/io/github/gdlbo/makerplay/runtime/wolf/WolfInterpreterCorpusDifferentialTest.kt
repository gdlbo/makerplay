package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.EventCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Differential test: the Kotlin interpreter and the Rust interpreter must agree
 * on the same command corpus.
 *
 * The corpus lives in `runtime/wolf/src/test/resources/wolf/interpreter-corpus.txt`
 * and is shared verbatim with the Rust side, which runs it through
 * `interpreter::corpus::run_corpus`. Both sides emit the same canonical trace;
 * this test compares them line for line.
 *
 * Without this, "the Rust port matches the Kotlin behaviour" would be a claim
 * resting on two separately written test suites, each free to be wrong in its own
 * way. With it, a disagreement about any opcode in the corpus fails here.
 */
class WolfInterpreterCorpusDifferentialTest {

    private fun corpusText(): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("wolf/interpreter-corpus.txt")
            ?: error("missing wolf/interpreter-corpus.txt on the test classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun cmd(type: Int, params: List<Int>, strings: List<String>) = EventCommand(
        paramCount = if (type == 0) 0 else params.size + 1,
        commandType = type,
        params = params.toIntArray(),
        branchDepth = 0,
        strings = strings,
        route = null,
    )

    /** Parses one corpus line into a command, mirroring the Rust parser. */
    private fun parseLine(line: String): EventCommand {
        val parts = line.split("|", limit = 3)
        val type = parts[0].trim().toInt()
        val paramsText = parts.getOrNull(1).orEmpty()
        val stringsText = parts.getOrNull(2).orEmpty()
        val params = if (paramsText.isBlank()) {
            emptyList()
        } else {
            paramsText.split(',').map { parseNumber(it.trim()) }
        }
        val strings = if (stringsText.isEmpty()) emptyList() else stringsText.split(',')
        return cmd(type, params, strings)
    }

    /** Accepts both decimal and `0x` hex, matching the Rust parser. */
    private fun parseNumber(text: String): Int =
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text.substring(2).toInt(16)
        } else {
            text.toInt()
        }

    private class TraceHost : WolfInterpreter.Host {
        val lines = mutableListOf<String>()

        /** Set when a database is loaded, so 250-family commands execute here. */
        var database: WolfDatabase? = null
        var interpreter: WolfInterpreter? = null
        var machineNumbers: MutableMap<Int, Int> = mutableMapOf()
        var machineStrings: MutableMap<Int, String> = mutableMapOf()

        override fun onMessage(text: String) { lines += "MSG:$text" }
        override fun onChoices(options: List<String>) { lines += "CHOICE:${options.joinToString("/")}" }
        override fun onSound(command: EventCommand) { lines += "SOUND:${command.commandType}" }
        override fun onPicture(command: EventCommand) {
            lines += "PICTURE:${command.commandType}:slot=${command.params.getOrNull(1) ?: -1}"
        }
        override fun onScreenEffect(command: EventCommand) { lines += "EFFECT:${command.commandType}" }
        override fun onTeleport(mapId: Int, tileX: Int, tileY: Int) {
            lines += "TELEPORT:$mapId,$tileX,$tileY"
        }
        override fun onSave(slot: Int): Boolean { lines += "SAVE:$slot"; return true }
        override fun onLoad(slot: Int): Boolean { lines += "LOAD:$slot"; return true }
        override fun onSaveLoad(): Boolean { lines += "SAVELOAD"; return true }
        override fun onMove(command: EventCommand) { lines += "MOVE:${command.commandType}" }
        override fun onMoveFinished(): Boolean = true
        override fun onDatabase(command: EventCommand) {
            val db = database
            if (db == null) {
                lines += "DATABASE:${command.commandType}"
                return
            }
            val active = interpreter
            db.execute(
                command,
                readNumber = { raw ->
                    active?.variables?.get(decodeNum(raw)) ?: machineNumbers[decodeNum(raw)] ?: 0
                },
                writeNumber = { raw, value ->
                    val key = decodeNum(raw)
                    active?.variables?.set(key, value)
                    machineNumbers[key] = value
                },
                readString = { raw ->
                    active?.strings?.get(decodeStr(raw)) ?: machineStrings[decodeStr(raw)] ?: ""
                },
                writeString = { raw, value ->
                    val key = decodeStr(raw)
                    active?.strings?.set(key, value)
                    machineStrings[key] = value
                },
            )
        }

        private fun decodeNum(raw: Int): Int = when (raw) {
            in 2_000_000..2_999_999 -> raw - 2_000_000
            in 8_000_000..8_999_999 -> -(raw - 8_000_000) - 1
            in 9_000_000..9_999_999 -> -(raw - 9_000_000) - 1_000_000
            in 1_600_000..1_699_999 -> -1_000_000 - (raw - 1_600_000)
            else -> raw
        }

        private fun decodeStr(raw: Int): Int = when (raw) {
            in 3_000_000..3_999_999 -> raw - 3_000_000
            in 1_600_000..1_699_999 -> -1_000_000 - (raw - 1_600_000)
            else -> raw
        }
        override fun onParty(command: EventCommand) { lines += "PARTY:${command.commandType}" }
        override fun onChipChange(command: EventCommand) { lines += "CHIP:${command.commandType}" }
        override fun onBanInput(banned: Boolean) { lines += "BANINPUT:$banned" }
        override fun onReturnToTitle() { lines += "TITLERETURN" }
        override fun onEndGame() { lines += "ENDGAME" }
        override fun onUnhandled(command: EventCommand) { lines += "UNHANDLED:${command.commandType}" }
    }

    /**
     * Runs the corpus through the Kotlin interpreter, producing the same trace
     * format the Rust `run_corpus` emits.
     */
    private fun runCorpus(text: String, dataDir: java.io.File? = null): String {
        val commonByName = mutableMapOf<String, MutableList<EventCommand>>()
        val commonById = mutableMapOf<Int, MutableList<EventCommand>>()
        // Ordered steps: commands and actions interleaved, so segmentation can
        // run the commands that precede an action before that action observes.
        val steps = mutableListOf<Any>()
        var currentName: String? = null
        var currentId: Int? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("@")) {
                val body = line.substring(1)
                val name = body.substringBefore(' ')
                val arg = body.substringAfter(' ', "").trim()
                when (name) {
                    "common" -> {
                        currentName = arg
                        currentId = null
                        commonByName.getOrPut(arg) { mutableListOf() }
                    }
                    "common-id" -> {
                        currentId = arg.toInt()
                        currentName = null
                        commonById.getOrPut(arg.toInt()) { mutableListOf() }
                    }
                    "end" -> {
                        currentName = null
                        currentId = null
                    }
                    "choose", "advance", "vars", "strings", "ticks" ->
                        steps += if (arg.isEmpty()) name else "$name $arg"
                    else -> error("unknown meta-command @$name")
                }
                continue
            }
            val command = parseLine(line)
            val name = currentName
            val id = currentId
            when {
                name != null -> commonByName.getOrPut(name) { mutableListOf() } += command
                id != null -> commonById.getOrPut(id) { mutableListOf() } += command
                else -> steps += command
            }
        }

        val host = TraceHost()
        if (dataDir != null) {
            // WolfDatabase.load expects the *game* root and resolves
            // "Data/BasicData/<bank>.dat" itself, so a Data directory must be
            // handed over as its parent.
            val root = if (dataDir.name == "Data") dataDir.parentFile ?: dataDir else dataDir
            host.database = WolfDatabase.load(
                io.github.gdlbo.makerplay.wolfformat.GameDataSource.open(root),
            )
        }
        val interpreter = WolfInterpreter(
            host,
            commonEvents = commonById,
            commonEventsByName = commonByName,
        )
        host.interpreter = interpreter

        fun runToStop() {
            var guard = 0
            while (guard++ < 200 && !interpreter.finished) {
                interpreter.tick()
                if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Message) {
                    interpreter.advance()
                    continue
                }
                if (interpreter.currentBlocking() != null) return
            }
        }

        // The corpus is an ordered script, so it runs in segments: the commands
        // before an action execute first, then the action observes the result.
        // Running the whole program up front would let the first `@vars` consume
        // every later command. `start` resets frames but keeps the variable and
        // string banks, so segments share machine state as one run would.
        var segment = mutableListOf<EventCommand>()
        fun flush() {
            if (segment.isEmpty()) return
            interpreter.start(segment)
            segment = mutableListOf()
            runToStop()
        }

        for (step in steps) {
            if (step is EventCommand) {
                segment += step
                continue
            }
            flush()
            val action = step as String
            val name = action.substringBefore(' ')
            val arg = action.substringAfter(' ', "").trim()
            when (name) {
                "choose" -> {
                    if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Choices) {
                        interpreter.choose(arg.toInt())
                        // Choosing pushes the case body as a new frame; run it now
                        // so a corpus does not need a redundant segment after
                        // every @choose. Mirrors the Rust runner.
                        runToStop()
                    }
                }
                "advance" -> interpreter.advance()
                "vars" -> {
                    for (key in interpreter.variables.keys.sorted()) {
                        host.lines += "VAR:$key=${interpreter.variables[key]}"
                    }
                }
                "strings" -> {
                    for (key in interpreter.strings.keys.sorted()) {
                        host.lines += "STR:$key=${interpreter.strings[key]}"
                    }
                }
                "ticks" -> repeat(arg.toInt()) {
                    interpreter.tick()
                    if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Message) {
                        interpreter.advance()
                    }
                }
            }
        }
        flush()
        host.lines += "DONE"
        return host.lines.joinToString("\n")
    }

    /**
     * The Kotlin trace for the shared corpus, pinned as a golden file.
     *
     * This is the other half of the differential test: the Rust side asserts
     * against this same golden trace in
     * `interpreter::corpus::tests::the_shared_corpus_matches_the_golden_trace`,
     * so both implementations are compared to one recorded answer rather than to
     * each other at test time (which would need a JVM from the Rust test runner).
     */
    @Test
    fun kotlinProducesTheRecordedGoldenTrace() {
        val golden = javaClass.classLoader
            ?.getResourceAsStream("wolf/interpreter-corpus.golden.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing wolf/interpreter-corpus.golden.txt on the test classpath")

        val actual = runCorpus(corpusText())
        val expected = golden.trim().lines()
        val actualLines = actual.trim().lines()

        // Report the first divergence precisely; a full diff of a long trace is
        // unreadable and hides which opcode actually disagrees.
        val limit = minOf(expected.size, actualLines.size)
        for (i in 0 until limit) {
            assertEquals("trace diverges at line ${i + 1}", expected[i], actualLines[i])
        }
        assertEquals("trace length differs", expected.size, actualLines.size)
    }

    /**
     * The database / XY-array half of the differential test.
     *
     * Needs the deployment, because these commands address real types by name
     * (`BTL_member`, `unit`). Skipped when the game data is absent so the suite
     * still runs in a bare checkout.
     */


    @Test
    fun theCorpusCoversTheOpcodesReachedInRealPlay() {
        // A corpus that quietly stopped exercising an opcode would make the
        // differential test pass while proving less each time.
        val covered = corpusText().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("@") }
            .mapNotNull { it.substringBefore('|').trim().toIntOrNull() }
            .toSet()

        val required = setOf(
            101, 102, 111, 112, 121, 122, 123, 124, 130, 140, 150, 151,
            170, 171, 176, 179, 180, 201, 210, 212, 213, 250, 290, 300,
            401, 402, 420, 498, 499,
        )
        val missing = required - covered
        assertTrue("corpus does not cover opcodes $missing", missing.isEmpty())
    }
}
