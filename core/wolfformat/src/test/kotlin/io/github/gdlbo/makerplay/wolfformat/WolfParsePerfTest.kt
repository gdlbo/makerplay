package io.github.gdlbo.makerplay.wolfformat

import org.junit.Test
import java.io.File

/**
 * Measures parse throughput for the heaviest WOLF data files across all three
 * example deployments. Numbers feed the load-performance notes in
 * docs/wolf-parity-checklist.md (import/boot budget for 30-50k file games).
 */
class WolfParsePerfTest {
    @Test fun measure() {
        var d = File(System.getProperty("user.dir"))
        repeat(4) { if (d.resolve("example").isDirectory) return@repeat; d = d.parentFile ?: d }
        val games = d.resolve("example").listFiles().orEmpty()
            .filter { it.resolve("Data/BasicData/CommonEvent.dat").isFile }
            .map { it.name }
            .sorted()
        for (game in games) {
            val basic = d.resolve("example/$game/Data/BasicData")
            val ce = basic.resolve("CommonEvent.dat")
            if (!ce.isFile) continue
            val bytes = ce.readBytes()

            // Warm-up + measured runs.
            repeat(2) { CommonEventDat.parse(bytes) }
            val t0 = System.nanoTime()
            val parsed = CommonEventDat.parse(bytes)
            val ceMs = (System.nanoTime() - t0) / 1_000_000

            val mapDir = d.resolve("example/$game/Data/MapData")
            val maps = mapDir.listFiles { f -> f.extension.equals("mps", true) }.orEmpty()
            val t1 = System.nanoTime()
            var count = 0
            for (m in maps) {
                runCatching { MapFile.parse(m.readBytes()) }.getOrNull()?.let { count++ }
            }
            val mapsMs = (System.nanoTime() - t1) / 1_000_000

            val sizeMb = bytes.size / 1_048_576.0
            println("$game: CommonEvent ${"%.1f".format(sizeMb)}MB -> ${parsed.events.size} events in ${ceMs}ms; " +
                "$count/${maps.size} maps in ${mapsMs}ms")
        }
    }
}
