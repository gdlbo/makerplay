package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHooksScriptTest {
    @Test
    fun `scene changes clear image and pixi caches`() {
        val script = runtimeAsset("memory-hooks.js")
        assertTrue(script.contains("Scene_Base.prototype"))
        assertTrue(script.contains("\"terminate\""))
        assertTrue(script.contains("SceneManager"))
        for (name in listOf("goto", "push", "pop")) {
            assertTrue(script.contains("\"$name\""))
        }
        assertTrue(script.contains("ImageManager.clear"))
        assertTrue(script.contains("clearTextureCache"))
        assertTrue(script.contains("__makerplayResetPerfCaches"))
        assertTrue(script.contains("__makerplayTrimSceneCaches"))
        assertTrue(script.contains("FontManager"))
    }
}
