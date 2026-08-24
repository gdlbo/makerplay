package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixture-gated integration test: discovers packed launcher executables under
 * the (gitignored) example/ deployments and verifies the reader against real
 * images. Fails only if no fixture exists.
 */
class EvbRealImageTest {

    private fun repoRoot(): File? =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }

    private fun candidateExes(): List<File> {
        val root = repoRoot() ?: return emptyList()
        val examples = File(root, "example")
        if (!examples.isDirectory) return emptyList()
        return examples.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.extension == "exe" && it.length() > 1_000_000 }
            .toList()
    }

    @Test
    fun `reads every EVB-packed fixture image`() {
        var verifiedImages = 0
        for (exe in candidateExes()) {
            val vfs = EvbVirtualFileSystem.open(exe)
            val entries = try {
                vfs.entries()
            } catch (e: WolfFormatException) {
                continue // not EVB-packed
            }
            assertTrue("expected virtual files in $exe.name", entries.isNotEmpty())

            // Every extracted file must decode to its declared original size.
            // Uses the streaming API so verification is memory-bounded.
            for (entry in entries) {
                val counting = object : java.io.OutputStream() {
                    var count = 0
                    override fun write(b: Int) { count++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { count += len }
                }
                vfs.extractTo(entry, counting)
                assertEquals(entry.path, entry.originalSize, counting.count)
            }
            vfs.close()
            verifiedImages++
        }
        assertTrue("no EVB-packed fixture found under example/", verifiedImages > 0)
    }
}

