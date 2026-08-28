package io.github.gdlbo.makerplay.vfs

import io.github.gdlbo.makerplay.codec.RpgMakerAssetCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

class RpgMakerPerfBaselineTest {
    @Test
    fun profileExampleGamesBaseline() {
        val report = StringBuilder()
        report.appendLine("# RPGM before-state profile (JVM)")
        report.appendLine()
        val games = listOf(
            GameSpec("Light and Shadow", "Light_and_Shadow-Doppelganger Uncen/www", encryptedSample = null),
            GameSpec("Lico", "Lico's Mysterious Errand_Full", encryptedSample = "img/animations/Absorb.png"),
            GameSpec("Mallow", "Mallow & The Street of the Fallen/www", encryptedSample = "img/animations/Absorb.png"),
        )
        games.forEach { spec ->
            val root = example(spec.relativeRoot)
            assertTrue("${spec.name} missing at $root", root.isDirectory)
            val indexRoot = File.createTempFile("perf-idx-", null).apply { delete(); mkdirs() }
            try {
                val buildNs = measureNanoTime {
                    GameFileIndex.build(root).write(indexRoot)
                }
                lateinit var vfs: GameFileSystem
                val mountNs = measureNanoTime {
                    vfs = RpgMakerGameMount.open(root, indexRoot)
                }
                val systemNs = measureNanoTime {
                    (vfs.open("data/System.json") as VfsOpenResult.Found).stream.use { it.readBytes() }
                }
                val indexHtmlNs = measureNanoTime {
                    (vfs.open("index.html") as VfsOpenResult.Found).stream.use { it.readBytes() }
                }
                var decryptNs = -1L
                var decryptBytes = -1
                var rawEncNs = -1L
                spec.encryptedSample?.let { logical ->
                    decryptNs = measureNanoTime {
                        val opened = vfs.open(logical) as VfsOpenResult.Found
                        decryptBytes = opened.stream.use { it.readBytes().size }
                    }
                    val stored = when {
                        File(root, logical + "_").isFile -> logical + "_"
                        File(root, logical.removeSuffix(".png") + ".rpgmvp").isFile ->
                            logical.removeSuffix(".png") + ".rpgmvp"
                        else -> null
                    }
                    if (stored != null) {
                        rawEncNs = measureNanoTime {
                            (vfs.open(stored) as VfsOpenResult.Found).stream.use { it.readBytes() }
                        }
                    }
                }
                // Warm + median of 20 small opens if encrypted sample exists, else Window.png-ish
                val probe = spec.encryptedSample ?: listOf(
                    "img/system/Window.png",
                    "img/system/IconSet.png",
                    "js/rpg_core.js",
                    "js/rmmz_core.js",
                ).firstOrNull { vfs.resolve(it) != null } ?: "index.html"
                val samples = LongArray(20) {
                    measureNanoTime {
                        (vfs.open(probe) as VfsOpenResult.Found).stream.use { it.readBytes() }
                    }
                }.sorted()
                val entryCount = GameFileIndex.build(root).entries.size
                report.appendLine("## ${spec.name}")
                report.appendLine("- root: `${root.path}`")
                report.appendLine("- indexed files: $entryCount")
                report.appendLine("- index build: ${ms(buildNs)} ms")
                report.appendLine("- mount (System.json + codec): ${ms(mountNs)} ms")
                report.appendLine("- open data/System.json: ${ms(systemNs)} ms")
                report.appendLine("- open index.html: ${ms(indexHtmlNs)} ms")
                report.appendLine("- open probe `$probe` median/p90 (n=20): ${ms(samples[9])} / ${ms(samples[17])} ms")
                if (decryptNs >= 0) {
                    report.appendLine("- decrypt logical `${spec.encryptedSample}`: ${ms(decryptNs)} ms ($decryptBytes bytes)")
                }
                if (rawEncNs >= 0) {
                    report.appendLine("- raw encrypted open: ${ms(rawEncNs)} ms")
                }
                report.appendLine()
            } finally {
                indexRoot.deleteRecursively()
            }
        }
        val out = File("build/reports/rpgm-perf-baseline.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
        assertTrue(out.isFile)
    }

    private data class GameSpec(val name: String, val relativeRoot: String, val encryptedSample: String?)

    private fun ms(ns: Long) = "%.2f".format(ns / 1_000_000.0)

    private fun example(relative: String): File {
        var dir = File("").absoluteFile
        repeat(8) {
            val candidate = File(dir, "example/rpgm/$relative")
            if (candidate.isDirectory) return candidate.canonicalFile
            dir = dir.parentFile ?: dir
        }
        return File("../..", "example/rpgm/$relative").canonicalFile
    }
}
