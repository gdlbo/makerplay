package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * Milestone contract: the Steam-compat shim must satisfy plugin gates such as
 * MadeWithMv's `isSubscribedApp` check so locally imported games reach the
 * title screen instead of a "please restart Steam" error.
 */
class SteamCompatibilityScriptTest {
    @Test
    fun `greenworks shim reports subscription and installation`() {
        val compatibilityScript = runtimeAsset("steam-compatibility.js")
        val harness = """
            const assert = require("node:assert/strict");
            const storage = {};
            globalThis.localStorage = {
              getItem: (k) => Object.prototype.hasOwnProperty.call(storage, k) ? storage[k] : null,
              setItem: (k, v) => { storage[k] = String(v); },
              removeItem: (k) => { delete storage[k]; },
            };
            globalThis.navigator = { language: "en" };
            // Node keeps `require` module-scoped; nw.js exposes it globally,
            // which is what the shim expects to intercept.
            globalThis.require = (id) => { throw new Error("unexpected require: " + id); };

            $compatibilityScript

            const gw = globalThis.require("greenworks");
            assert.equal(gw.initAPI(), true);
            assert.equal(gw.isSteamRunning(), true);
            // The MadeWithMv gate: locally imported games are entitled.
            assert.equal(gw.isSubscribedApp(4050550), true);
            assert.equal(gw.isAppInstalled(4050550), true);
        """.trimIndent()
        val scriptFile = Files.createTempFile("steam-compatibility", ".js")
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
