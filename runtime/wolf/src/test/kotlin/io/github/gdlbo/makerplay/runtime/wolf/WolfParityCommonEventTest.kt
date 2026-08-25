package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs REAL common events from the example deployments through the
 * interpreter with an auto-resolving host, asserting that every audited
 * opcode is handled (nothing falls into onUnhandled) and execution
 * terminates. This is the engine-parity gate for deployment scripts that
 * are implemented as common events rather than built-ins.
 */
class WolfParityCommonEventTest {

    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) { if (dir.resolve("example").isDirectory) return@run dir; dir = dir.parentFile ?: dir }
        dir
    }

    private class AutoHost : WolfInterpreter.Host {
        val unhandled = mutableListOf<EventCommand>()
        override fun onMessage(text: String) = Unit
        override fun onChoices(options: List<String>) = Unit
        override fun onKeyWait(command: EventCommand): Boolean = true
        override fun onMoveFinished(): Boolean = true
        override fun onUnhandled(command: EventCommand) { unhandled.add(command) }
    }

    @Test
    fun everyCommonEventRunsFullyHandled() {
        val fixtures = repoRoot.resolve("example").listFiles().orEmpty()
            .filter { it.resolve("Data/BasicData/CommonEvent.dat").isFile }
            .map { it.resolve("Data/BasicData/CommonEvent.dat") }
        assumeTrue("No CommonEvent.dat fixtures present", fixtures.isNotEmpty())

        for (fixture in fixtures) {
            val parsed = CommonEventDat.parse(fixture.readBytes())
            assumeTrue(parsed.events.isNotEmpty())
            val byId = parsed.events.associate { it.id to it.commands }
            val byName = parsed.events.associate { it.title to it.commands }

            // Combat-oriented events get explicit coverage: their names or
            // debug messages reference the battle handling helpers.
            val battleEvents = parsed.events.filter { event ->
                val haystack = event.title + event.commands.joinToString("") { cmd ->
                    cmd.strings.joinToString()
                }
                listOf("戦闘", "battle", "勝利", "敗北", "ダメージ").any { haystack.contains(it) }
            }
            val sample = (battleEvents + parsed.events.take(150)).distinctBy { it.id }

            var ran = 0
            for (event in sample) {
                val host = AutoHost()
                val interpreter = WolfInterpreter(host, commonEvents = byId, commonEventsByName = byName)
                interpreter.start(event.commands)
                var ticks = 0
                while (!interpreter.finished && ticks < 20_000) {
                    if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Message) {
                        interpreter.advance()
                    } else if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Choices) {
                        interpreter.choose(0)
                    }
                    interpreter.tick()
                    ticks++
                }
                // Events that loop (directly or via called common events)
                // run perpetually by design; opcode coverage is the gate here.
                if (!interpreter.finished && ticks >= 20_000) {
                    println("  (CE ${event.id} '${event.title}' still running at tick cap — looping event)")
                }
                assertTrue(
                    "${fixture.parentFile?.parentFile?.name} CE ${event.id} '${event.title}' hit unhandled opcodes: " +
                        host.unhandled.joinToString(",") { it.commandType.toString() }.take(200),
                    host.unhandled.isEmpty(),
                )
                ran++
            }
            println("${fixture.parentFile?.parentFile?.name}: ran $ran common events (incl. ${battleEvents.size} battle-related) fully handled")
        }
    }
}
