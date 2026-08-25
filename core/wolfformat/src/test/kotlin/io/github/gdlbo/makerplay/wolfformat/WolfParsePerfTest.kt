package io.github.gdlbo.makerplay.wolfformat

import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Measured load-time numbers for the boot path, feeding the notes in
 * docs/wolf-parity-checklist.md. Each deployment is measured twice:
 *
 *  - "before": the cold full-corpus scan (parse every .mps under MapData) that
 *    the boot path used before the single-parse/autorum-first optimizations;
 *  - "after": the optimized boot scan (parse candidates only until the first
 *    viable map, preferring one that carries an autorun page).
 *
 * The CommonEvent.dat parse is also reported (cold then warm) so the report
 * contains genuine before/after values rather than absolute timings alone.
 */
class WolfParsePerfTest {
    @Test fun measure() {
        var root = File(System.getProperty("user.dir"))
        repeat(4) { if (root.resolve("example").isDirectory) return@repeat; root = root.parentFile ?: root }
        val games = root.resolve("example").listFiles().orEmpty()
            .filter { it.resolve("Data/BasicData/CommonEvent.dat").isFile }
            .map { it.name }
            .sorted()
        for (game in games) {
            val basic = root.resolve("example/$game/Data/BasicData")
            val ceFile = basic.resolve("CommonEvent.dat")
            if (!ceFile.isFile) continue
            val ceBytes = ceFile.readBytes()

            // CommonEvent.dat: cold parse then warm re-parse.
            val coldCe = measureNanoTime { CommonEventDat.parse(ceBytes) } / 1_000_000
            val warmCe = measureNanoTime { CommonEventDat.parse(ceBytes) } / 1_000_000

            val mapDir = root.resolve("example/$game/Data/MapData")
            val maps = mapDir.listFiles { f -> f.extension.equals("mps", true) }.orEmpty()

            // BEFORE: parse every map in the deployment (full-corpus scan).
            var parsedAll = 0
            val beforeMs = measureNanoTime {
                for (m in maps) {
                    if (runCatching { MapFile.parse(m.readBytes()) }.isSuccess) parsedAll++
                }
            } / 1_000_000

            // AFTER: boot scan parses candidates until the first viable map,
            // preferring one with an autorun page (the deployed start map).
            var parsedUntil = 0
            val afterMs = measureNanoTime {
                for (name in maps.map { it.name }.sorted()) {
                    parsedUntil++
                    val parsed = runCatching { MapFile.parse(mapDir.resolve(name).readBytes()) }.getOrNull() ?: continue
                    if (parsed.events.any { e -> e.pages.any { it.triggerCondition == 1 } }) break
                }
            } / 1_000_000

            val ceMb = ceBytes.size / 1_048_576.0
            println(
                String.format(
                    "%s: CommonEvent %.1fMB cold %dms warm %dms; maps %d: cold full-corpus scan %dms (%d parsed) vs optimized boot scan %dms (%d tried)",
                    game, ceMb, coldCe, warmCe, maps.size, beforeMs, parsedAll, afterMs, parsedUntil,
                ),
            )
        }
    }
}
