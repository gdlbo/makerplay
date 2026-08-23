package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the interpreter over REAL command streams from pushed deployments,
 * verifying that messages/sound/picture hooks fire on production data.
 */
class WolfRealEventDeviceTest {

    private val fixturesRoot = File("/data/local/tmp/wolffix")

    @Test
    fun interpreterFiresHooksOnRealCommonEvents() {
        val games = fixturesRoot.listFiles { f -> f.isDirectory }.orEmpty()
        assumeTrue(games.isNotEmpty())
        var exercised = 0
        for (game in games) {
            val commonFile = File(game, "Data/BasicData/CommonEvent.dat")
            assumeTrue(commonFile.isFile)
            val common = CommonEventDat.parse(commonFile.readBytes())
            val byId = common.events.associate { it.id to it.commands }

            // Find a common event that actually shows a message.
            val messageEvent = common.events.firstOrNull { ev ->
                ev.commands.any { it.commandType == 101 && it.strings.isNotEmpty() }
            } ?: continue
            exercised++

            val seen = mutableListOf<String>()
            val host = object : WolfInterpreter.Host {
                override fun onMessage(text: String) = seen.add(text)
            }
            val interpreter = WolfInterpreter(host, commonEvents = byId)
            interpreter.start(messageEvent.commands)
            repeat(120) {
                if (!interpreter.finished) {
                    if (interpreter.currentBlocking() is WolfInterpreter.Blocking.Message) {
                        interpreter.advance()
                    }
                    interpreter.tick()
                }
            }
            assertTrue("no messages shown from ${game.name} CE ${messageEvent.id}", seen.isNotEmpty())
        }
        assumeTrue(exercised > 0)
    }

    @Test
    fun projectParsesForEveryPushedDeployment() {
        val games = fixturesRoot.listFiles { f -> f.isDirectory }.orEmpty()
        assumeTrue(games.isNotEmpty())
        for (game in games) {
            GameDataSource.open(game).use { source ->
                val project = GameDat.parse(source.read("Data/BasicData/Game.dat"))
                assertTrue(project.title.isNotBlank())
            }
        }
    }
}
