package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualBoostsScriptTest {
    @Test
    fun `visual boosts keep filters but do not inject the green VB badge`() {
        val source = runtimeAsset("visual-boosts.js")

        assertTrue(source.contains("makerplay-visual-boosts"))
        assertTrue(source.contains("contrast("))
        assertTrue(source.contains("SCALE_MODES.NEAREST"))
        // Must not mutate Graphics._realScale (breaks Options volume/opacity/borders).
        assertFalse(source.contains("__makerplayIntegerScale"))
        assertFalse(source.contains("Math.floor(this._realScale"))
        assertFalse(source.contains("Math.floor(graphics._realScale"))
        assertFalse(source.contains("makerplay-visual-boost-badge"))
        assertFalse(source.contains("textContent = \"VB\""))
        assertFalse(source.contains("data-makerplay-visual-boosts"))
        assertFalse(source.contains("#00c853"))
    }
}
