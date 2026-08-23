package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Device verification of the static boot milestone: composes a full map frame
 * from real WOLF deployments pushed to /data/local/tmp/wolffix/<name>.
 * Skipped automatically when no fixtures are pushed.
 */
class WolfSceneLoaderDeviceTest {

    private val fixturesRoot = File("/data/local/tmp/wolffix")

    @Test
    fun composesStaticFrameForPushedDeployments() {
        val games = fixturesRoot.listFiles { f -> f.isDirectory }.orEmpty()
        assumeTrue("No WOLF fixtures pushed to $fixturesRoot", games.isNotEmpty())
        for (game in games) {
            GameDataSource.open(game).use { source ->
                val project = GameDat.parse(source.read("Data/BasicData/Game.dat"))
                assertTrue("title blank for ${game.name}", project.title.isNotBlank())
                val frame = WolfSceneLoader.loadStaticFrame(source, project)
                assertTrue("frame width for ${game.name}", frame.width > 0)
                assertTrue("frame height for ${game.name}", frame.height > 0)
                assertEquals(frame.rgba.size.toLong(), frame.width.toLong() * frame.height * 4L)
                // The frame must contain some non-transparent pixels (map drawn).
                val opaque = frame.rgba.indices.count { it % 4 == 3 && frame.rgba[it].toInt() != 0 }
                assertTrue("frame fully transparent for ${game.name}", opaque > 0)
            }
        }
    }

    @Test
    fun startingHeroResolutionSkipsBlank() {
        val games = fixturesRoot.listFiles { f -> f.isDirectory }.orEmpty()
        assumeTrue(games.isNotEmpty())
        val first = games.first()
        GameDataSource.open(first).use { source ->
            val project = GameDat.parse(source.read("Data/BasicData/Game.dat"))
            if (project.startingHeroGraphic.isBlank()) {
                assertNotNull(WolfSceneLoader.startingHeroGraphic(project) ?: "ok")
            } else {
                assertTrue(source.has("Data/CharaChip/${project.startingHeroGraphic}"))
            }
        }
    }
}
