package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Integration smoke tests against real WOLF RPG deployments. They are skipped
 * automatically when no deployment is present, keeping CI hermetic while
 * verifying parser correctness on actual shipped data locally.
 */
class WolfRealDeploymentTest {

    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        repeat(4) { if (dir.resolve("example").isDirectory) return@run dir; dir = dir.parentFile ?: dir }
        dir
    }

    private fun exampleRoots(): List<File> =
        repoRoot.resolve("example").takeIf { it.isDirectory }
            ?.listFiles { f -> f.isDirectory }
            ?.filter { root -> root.resolve("Game.exe").isFile && findGameDat(root) != null }
            .orEmpty()

    private fun findGameDat(root: File): File? =
        sequenceOf(root.resolve("Game.dat"), root.resolve("Data/BasicData/Game.dat"),
            root.resolve("Data/BasicData/game.dat"))
            .firstOrNull { it.isFile }

    private fun dataDir(root: File): File =
        listOf("Data", "data").map { root.resolve(it) }.firstOrNull { it.isDirectory } ?: root

    @Test
    fun parsesEveryExampleDeployment() {
        val roots = exampleRoots()
        assumeTrue("No WOLF deployments under example/", roots.isNotEmpty())
        for (root in roots) {
            val source = GameDataSource.open(root)
            source.use {
                val gameDat = GameDat.parse(it.read(relativeGameDat(root)))
                assertTrue(gameDat.title.isNotBlank())
                assertTrue(gameDat.screenWidth in 1..4096)
                assertTrue(gameDat.tileSize in listOf(16, 32, 40, 48))
            }
        }
    }

    @Test
    fun parsesStandardDatabasesOfFirstDeployment() {
        val root = exampleRoots().firstOrNull() ?: return
        val data = dataDir(root)
        val sysDb = data.walkTopDown().firstOrNull {
            it.name.equals("SysDatabase.dat", ignoreCase = true)
        } ?: return
        val parsed = DataBaseDat.parse(sysDb.readBytes())
        assertTrue(parsed.types.isNotEmpty())
    }

    @Test
    fun parsesCommonEventsOfFirstDeployment() {
        val root = exampleRoots().firstOrNull() ?: return
        val data = dataDir(root)
        val commonEvent = data.walkTopDown().firstOrNull {
            it.name.equals("CommonEvent.dat", ignoreCase = true)
        } ?: return
        val parsed = CommonEventDat.parse(commonEvent.readBytes())
        assertTrue(parsed.events.isNotEmpty())
    }

    @Test
    fun parsesTileSetsOfFirstDeployment() {
        val root = exampleRoots().firstOrNull() ?: return
        val data = dataDir(root)
        val tileSetData = data.walkTopDown().firstOrNull {
            it.name.equals("TileSetData.dat", ignoreCase = true)
        } ?: return
        val parsed = TileSetData.parse(tileSetData.readBytes())
        assertTrue(parsed.tilesets.isNotEmpty())
    }

    @Test
    fun parsesMapsOfFirstDeployment() {
        val root = exampleRoots().firstOrNull() ?: return
        val mapFiles = dataDir(root).walkTopDown()
            .filter { it.isFile && it.extension.equals("mps", ignoreCase = true) }
            .toList()
        assumeTrue(mapFiles.isNotEmpty())
        // Parse up to five maps to bound test runtime.
        var parsedCount = 0
        for (map in mapFiles.take(5)) {
            val parsed = MapFile.parse(map.readBytes())
            assertEquals(parsed.layers.size, 3)
            assertTrue(parsed.width > 0 && parsed.height > 0)
            parsedCount++
        }
        assertTrue(parsedCount > 0)
    }

    private fun relativeGameDat(root: File): String =
        if (root.resolve("Game.dat").isFile) "Game.dat" else "Data/BasicData/Game.dat"
}
