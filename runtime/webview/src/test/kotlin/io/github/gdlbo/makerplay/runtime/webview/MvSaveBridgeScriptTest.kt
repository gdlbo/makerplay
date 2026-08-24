package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.save.MvSaveBridgeScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvSaveBridgeScriptTest {
    @Test
    fun scriptPatchesTheSynchronousPublicStorageSurface() {
        val source =
            MvSaveBridgeScript.source(runtimeAsset("bridges/mv-save-bridge.js"), "session-token")

        listOf(
            "storage.save = function",
            "storage.load = function",
            "storage.exists = function",
            "storage.remove = function",
            "storage.backup = function",
            "storage.backupExists = function",
            "storage.cleanBackup = function",
            "storage.restoreBackup = function",
        ).forEach { declaration -> assertTrue(source.contains(declaration)) }
        assertTrue(source.contains("var token = \"session-token\""))
        assertTrue(source.contains("return \"config\""))
        assertTrue(source.contains("return \"global\""))
        assertTrue(source.contains("return \"file\" + id"))
        assertTrue(source.contains("return \"plugin-\" + savefileId"))
        assertTrue(source.contains("[A-Za-z0-9_][A-Za-z0-9._-]{0,106}"))
        assertTrue(source.contains("+ \"-engine-backup\""))
        assertTrue(source.contains("refreshKnownKeys()"))
        assertTrue(source.contains("knownKeys.has(keyFor(savefileId))"))
        assertTrue(source.contains("maxCachedChars = 4 * 1024 * 1024"))
        assertTrue(source.contains("function maintainStoragePatch()"))
        assertTrue(source.contains("storage[name] = installedStorageFunctions[name]"))
        assertTrue(source.contains("installGuardChecks < 400"))
        assertTrue(source.contains("stableInstallChecks < 20"))
        assertTrue(source.contains("source.indexOf(\"_globaInfoCache\")"))
        assertTrue(source.contains("StorageManager.load(0)"))
        assertTrue(source.contains("return original.call(this)"))
        assertTrue(source.contains("installAttempts < 1000"))
    }

    @Test
    fun tokenIsEncodedAsAJavaScriptStringLiteral() {
        val source =
            MvSaveBridgeScript.source(runtimeAsset("bridges/mv-save-bridge.js"), "token\"\\\nvalue")

        assertTrue(source.contains("var token = \"token\\\"\\\\\\nvalue\""))
        assertFalse(source.contains("var token = \"token\"\\\nvalue\""))
    }
}
