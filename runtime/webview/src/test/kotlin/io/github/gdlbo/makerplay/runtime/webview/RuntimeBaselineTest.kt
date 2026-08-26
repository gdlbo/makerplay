package io.github.gdlbo.makerplay.runtime.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBaselineTest {
    @Test
    fun `baseline inventory records current document start order and module defaults`() {
        val text = checkNotNull(javaClass.classLoader?.getResource("runtime-baseline.json"))
            .readText()
        val root = Json.parseToJsonElement(text).jsonObject
        val order = root["documentStartOrder"]!!.jsonArray.map { it.jsonPrimitive.content }
        val defaults = root["defaultModules"]!!.jsonObject

        assertEquals(
            listOf(
                "common-js", "layout", "steam-compatibility", "legacy-compatibility",
                "frame-rate", "frame-resilience", "performance-optimization", "input-bridge",
                "diagnostics-bridge", "cheat-bridge", "save-bridge",
            ),
            order,
        )
        assertTrue(defaults["steamCompatibility"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(defaults["performanceOptimization"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(defaults["cheatBridge"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(defaults["diagnosticsBridge"]!!.jsonPrimitive.content.toBoolean())
    }
}
