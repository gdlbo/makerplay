package io.github.gdlbo.makerplay.vfs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MountExampleGamesTest {
    @Test
    fun mountsLightAndShadowWww() {
        val root = example("Light_and_Shadow-Doppelganger Uncen/www")
        val indexRoot = File.createTempFile("idx-ls", null).apply { delete(); mkdirs() }
        try {
            val vfs = RpgMakerGameMount.open(root, indexRoot)
            val index = vfs.open("index.html")
            assertTrue("index.html missing: $index", index is VfsOpenResult.Found)
            (index as VfsOpenResult.Found).stream.close()
            val system = vfs.open("data/System.json")
            assertTrue("System.json missing: $system", system is VfsOpenResult.Found)
            (system as VfsOpenResult.Found).stream.close()
            val img = vfs.open("img/system/Window.png")
            assertTrue("Window.png missing: $img", img is VfsOpenResult.Found || img is VfsOpenResult.Missing)
            if (img is VfsOpenResult.Found) img.stream.close()
        } finally {
            indexRoot.deleteRecursively()
        }
    }

    @Test
    fun mountsMallowEncrypted() {
        val root = example("Mallow & The Street of the Fallen/www")
        val indexRoot = File.createTempFile("idx-mallow", null).apply { delete(); mkdirs() }
        try {
            val vfs = RpgMakerGameMount.open(root, indexRoot)
            val open = vfs.open("img/animations/Absorb.png")
            assertTrue("encrypted absorb should open decrypted: $open", open is VfsOpenResult.Found)
            val bytes = (open as VfsOpenResult.Found).stream.use { it.readBytes() }
            assertTrue("PNG magic", bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte())
            // Exact encrypted path should still be readable as stored bytes without requiring codec failure
            val raw = vfs.open("img/animations/Absorb.rpgmvp")
            assertTrue("raw rpgmvp: $raw", raw is VfsOpenResult.Found)
            (raw as VfsOpenResult.Found).stream.close()
        } finally {
            indexRoot.deleteRecursively()
        }
    }

    private fun example(relative: String): File {
        var dir = File("").absoluteFile
        repeat(8) {
            val candidate = File(dir, "example/rpgm/$relative")
            if (candidate.isDirectory) return candidate.canonicalFile
            dir = dir.parentFile ?: dir
        }
        // gradle test cwd is module dir core/vfs
        val fromModule = File("../..", "example/rpgm/$relative").canonicalFile
        require(fromModule.isDirectory) { "missing $relative under ${File("").absoluteFile}" }
        return fromModule
    }
}
