package io.github.gdlbo.makerplay.runtime.webview

import android.webkit.JavascriptInterface
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MvSynchronousSaveBridgeTest {
    @Test
    fun exposesOneTokenGatedTransactionForOneGame() {
        val store = MemoryStore()
        val bridge = MvSynchronousSaveBridge("secret", SaveBridgeProtocol("game-a", store))
        val payload = Base64.getEncoder().encodeToString("save".toByteArray())
        val request = """{"v":1,"id":"mv-1","op":"write","key":"file1","data":"$payload"}"""

        val rejected = bridge.transact("wrong", request)
        assertTrue(rejected.contains("\"ok\":false"))
        assertFalse(store.values.containsKey("game-a" to "file1"))

        val accepted = bridge.transact("secret", request)
        assertTrue(accepted.contains("\"ok\":true"))
        assertEquals("save", store.values.getValue("game-a" to "file1").decodeToString())
        assertFalse(store.values.keys.any { it.first != "game-a" })

        val exposed = MvSynchronousSaveBridge::class.java.declaredMethods.filter {
            it.getAnnotation(JavascriptInterface::class.java) != null
        }
        assertEquals(listOf("transact"), exposed.map { it.name })
    }

    private class MemoryStore : GameSaveStore {
        val values = mutableMapOf<Pair<String, String>, ByteArray>()

        override fun read(gameId: String, key: String): ByteArray? = values[gameId to key]?.copyOf()

        override fun write(gameId: String, key: String, payload: ByteArray) {
            values[gameId to key] = payload.copyOf()
        }

        override fun delete(gameId: String, key: String): Boolean =
            values.remove(gameId to key) != null

        override fun keys(gameId: String): Set<String> = values.keys
            .filter { it.first == gameId }
            .mapTo(mutableSetOf()) { it.second }
    }
}
