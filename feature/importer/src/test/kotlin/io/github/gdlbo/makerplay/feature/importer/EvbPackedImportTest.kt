package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies that a deployment whose game files live only inside a packed
 * launcher exe is expanded during the scan and detected as a standard RPG Maker
 * deployment.
 */
class EvbPackedImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val systemJson = """{"gameTitle":"Evb Packed Sample"}"""

    private fun packedDeployment(): Pair<File, ImportSource> {
        val dir = tmp.newFolder()
        val exe = File(dir, "Game.exe")
        writeEvbImage(
            exe,
            listOf(
                EvbFixtureFile("www/index.html", "<html></html>".toByteArray()),
                EvbFixtureFile("www/js/rpg_core.js", "// rpg core".toByteArray()),
                EvbFixtureFile("www/js/plugins.js", "[]".toByteArray()),
                EvbFixtureFile("www/data/System.json", systemJson.toByteArray()),
            ),
        )
        val entry = ImportEntry(
            relativePath = "Game.exe",
            size = exe.length(),
            open = exe::inputStream,
        )
        return dir to ImportSource { listOf(entry) }
    }

    @Test
    fun `packed exe expands and detects as standard deployment`() {
        val (dir, raw) = packedDeployment()
        val source = EvbExpandingImportSource(raw, tmp.newFolder("spool"))

        val entries = source.entries()
        val detected = GameDetector().detect(entries, fallbackTitle = dir.name)

        assertEquals(GameEngine.MV, detected.engine)
        assertEquals("www/", detected.sourcePrefix)
        assertEquals("Evb Packed Sample", detected.title)

        // The exe itself is replaced by its extracted content
        assertEquals(
            setOf("www/index.html", "www/js/rpg_core.js", "www/js/plugins.js", "www/data/System.json"),
            entries.map { it.relativePath }.toSet(),
        )
        val coreEntry = entries.first { it.relativePath == "www/js/rpg_core.js" }
        val bytes = coreEntry.open().use { it.readBytes() }
        assertEquals("// rpg core", bytes.decodeToString())
    }

    @Test
    fun `loose files take precedence over packed copies`() {
        val deployDir = tmp.newFolder("deploy2")
        File(deployDir, "www/js").mkdirs()
        File(deployDir, "www/data").mkdirs()
        File(deployDir, "www/index.html").writeText("<html>loose</html>")
        File(deployDir, "www/js/rpg_core.js").writeText("// loose core")
        File(deployDir, "www/data/System.json").writeText("""{"gameTitle":"Loose"}""")
        val exe = File(deployDir, "Game.exe")
        writeEvbImage(
            exe,
            listOf(
                EvbFixtureFile("www/index.html", "<html>packed</html>".toByteArray()),
                EvbFixtureFile("www/js/rpg_core.js", "// packed core".toByteArray()),
            ),
        )
        val raw = ImportSource {
            listOf(
                ImportEntry("www/index.html", 17) { File(deployDir, "www/index.html").inputStream() },
                ImportEntry("www/js/rpg_core.js", 13) { File(deployDir, "www/js/rpg_core.js").inputStream() },
                ImportEntry("www/data/System.json", 22) { File(deployDir, "www/data/System.json").inputStream() },
                ImportEntry("Game.exe", exe.length()) { exe.inputStream() },
            )
        }
        val entries = EvbExpandingImportSource(raw, tmp.newFolder("spool2")).entries()

        val index = entries.filter { it.relativePath == "www/index.html" }
        assertEquals(1, index.size)
        assertEquals("<html>loose</html>", index[0].open().use { it.readBytes().decodeToString() })
    }

    @Test
    fun `non-evb exe is left untouched`() {
        val dir = tmp.newFolder("deploy3")
        val exe = File(dir, "plain.exe").apply { writeBytes(ByteArray(1024 * 1024 + 64)) }
        val raw = ImportSource { listOf(ImportEntry("plain.exe", exe.length()) { exe.inputStream() }) }
        val entries = EvbExpandingImportSource(raw, tmp.newFolder("spool3")).entries()
        assertEquals(listOf("plain.exe"), entries.map { it.relativePath })
    }

    @Test
    fun `small exes are never scanned`() {
        val dir = tmp.newFolder("deploy4")
        val exe = File(dir, "tiny.exe").apply { writeBytes(ByteArray(128)) }
        val raw = ImportSource { listOf(ImportEntry("tiny.exe", exe.length()) { exe.inputStream() }) }
        val entries = EvbExpandingImportSource(raw, tmp.newFolder("spool4")).entries()
        assertEquals(listOf("tiny.exe"), entries.map { it.relativePath })
    }

    // --- Synthetic EVB image builder (stored entries only) -------------------

    private data class EvbFixtureFile(val path: String, val payload: ByteArray)

    /** Layout: %DEFAULT FOLDER%(1) > www(n) > js / data > files */
    private fun writeEvbImage(target: File, files: List<EvbFixtureFile>) {
        val records = mutableListOf<List<Byte>>()

        fun u32(out: MutableList<Byte>, v: Int) {
            out.add((v and 0xFF).toByte()); out.add(((v shr 8) and 0xFF).toByte())
            out.add(((v shr 16) and 0xFF).toByte()); out.add(((v ushr 24) and 0xFF).toByte())
        }

        fun record(name: String, type: Int, count: Int, original: Int = 0, stored: Int = 0): List<Byte> {
            val out = mutableListOf<Byte>()
            u32(out, 0); repeat(8) { out.add(0) }; u32(out, count)
            name.forEach { c -> val ch = c.code; out.add((ch and 0xFF).toByte()); out.add(((ch shr 8) and 0xFF).toByte()) }
            out.add(0); out.add(0); out.add(type.toByte())
            if (type == NODE_FOLDER) {
                repeat(25) { out.add(0) }
            } else {
                out.add(0); out.add(0); u32(out, original); repeat(4) { out.add(0) }
                repeat(24) { out.add(0) }; repeat(15) { out.add(0) }; u32(out, stored)
            }
            return out
        }

        fun fileRecord(f: EvbFixtureFile) =
            record(f.path.substringAfterLast('/'), NODE_FILE, 0, f.payload.size, f.payload.size)

        records.add(record("%DEFAULT FOLDER%", NODE_FOLDER, 1))
        val jsFiles = files.filter { it.path.startsWith("www/js/") }
        val dataFiles = files.filter { it.path.startsWith("www/data/") }
        val rootFiles = files.filterNot { it.path.startsWith("www/js/") || it.path.startsWith("www/data/") }
        val wwwChildren = (if (jsFiles.isEmpty()) 0 else 1) + (if (dataFiles.isEmpty()) 0 else 1) + rootFiles.size
        records.add(record("www", NODE_FOLDER, wwwChildren))
        if (jsFiles.isNotEmpty()) records.add(record("js", NODE_FOLDER, jsFiles.size))
        jsFiles.forEach { records.add(fileRecord(it)) }
        if (dataFiles.isNotEmpty()) records.add(record("data", NODE_FOLDER, dataFiles.size))
        dataFiles.forEach { records.add(fileRecord(it)) }
        rootFiles.forEach { records.add(fileRecord(it)) }

        val rl = records.sumOf { it.size }
        val prefix = ByteArray(1024)
        val magicPos = prefix.size
        val mainPos = magicPos + PACK_HEADER
        val first = mainPos + 15 // off-by-one quirk after the main node
        val dataStart = first + rl
        val mainSize = dataStart - (mainPos + 4)

        val buf = ByteArray(maxOf(dataStart + files.sumOf { it.payload.size }, MIN_IMAGE_BYTES))
        prefix.copyInto(buf)
        byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 0).copyInto(buf, magicPos)
        writeU32(buf, mainPos, mainSize.toLong())
        writeU32(buf, mainPos + 12, 1L)
        var w = first
        for (rec in records) {
            rec.forEachIndexed { i, b -> buf[w + i] = b }; w += rec.size
        }
        var d = dataStart
        val ordered = jsFiles + dataFiles + rootFiles
        for (f in ordered) {
            f.payload.copyInto(buf, d); d += f.payload.size
        }
        target.writeBytes(buf)
    }

    private fun writeU32(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private companion object {
        const val NODE_FILE = 2
        const val NODE_FOLDER = 3
        const val PACK_HEADER = 64
        const val MIN_IMAGE_BYTES = 1024 * 1024 + 64
    }
}
