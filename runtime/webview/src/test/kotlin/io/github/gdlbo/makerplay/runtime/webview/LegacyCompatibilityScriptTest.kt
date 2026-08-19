package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class LegacyCompatibilityScriptTest {
    @Test
    fun `normalizes invalid canvas text alignment before Bitmap exists`() {
        val compatibilityScript = runtimeAsset("legacy-compatibility.js")
        val harness = """
            const assert = require("node:assert/strict");
            const alignments = new WeakMap();
            globalThis.Element = function Element() {};
            globalThis.Element.prototype = {};
            globalThis.CanvasRenderingContext2D = function CanvasRenderingContext2D() {};
            Object.defineProperty(CanvasRenderingContext2D.prototype, "textAlign", {
              configurable: true,
              enumerable: true,
              get() { return alignments.get(this) || "start"; },
              set(value) { alignments.set(this, value); },
            });

            $compatibilityScript

            const context = new CanvasRenderingContext2D();
            globalThis.Bitmap = function Bitmap() { this.context = context; };
            Bitmap.prototype.drawText = function drawText(text, x, y, maxWidth, lineHeight, align) {
              this.context.textAlign = align;
            };

            const bitmap = new Bitmap();
            bitmap.drawText("missing alignment");
            assert.equal(context.textAlign, "left");
            bitmap.drawText("valid alignment", 0, 0, 100, 24, "center");
            assert.equal(context.textAlign, "center");
        """.trimIndent()
        val scriptFile = Files.createTempFile("legacy-compatibility", ".js")
        try {
            Files.write(scriptFile, harness.toByteArray(Charsets.UTF_8))
            val process = ProcessBuilder("node", scriptFile.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        } finally {
            Files.deleteIfExists(scriptFile)
        }
    }
}
