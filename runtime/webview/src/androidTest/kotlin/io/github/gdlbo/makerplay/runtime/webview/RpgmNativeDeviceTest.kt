package io.github.gdlbo.makerplay.runtime.webview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RpgmNativeDeviceTest {
    @Test
    fun nativeLibraryLoadsAndReadsFilesAsync() {
        RpgmNative.setForceDisabled(false)
        assertTrue("librpgm_native should load on device", RpgmNative.isAvailable())

        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "rpgm-native-read.bin")
        val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        file.writeBytes(payload)

        val sync = RpgmNative.nativeReadFile(file.absolutePath)
        assertArrayEquals(payload, sync)

        val latch = CountDownLatch(1)
        val asyncBytes = AtomicReference<ByteArray?>(null)
        val asyncError = AtomicReference<String?>(null)
        RpgmNative.nativeReadFileAsync(
            file.absolutePath,
            object : RpgmNative.BytesCallback {
                override fun onSuccess(bytes: ByteArray) {
                    asyncBytes.set(bytes)
                    latch.countDown()
                }

                override fun onError(message: String) {
                    asyncError.set(message)
                    latch.countDown()
                }
            },
        )
        assertTrue("async read timed out", latch.await(5, TimeUnit.SECONDS))
        assertTrue(asyncError.get().isNullOrEmpty())
        assertArrayEquals(payload, asyncBytes.get())
    }

    @Test
    fun nativeDecodeAsyncMatchesSyncXor() {
        RpgmNative.setForceDisabled(false)
        assertTrue(RpgmNative.isAvailable())
        val key = "00112233445566778899aabbccddeeff"
        val header = byteArrayOf(
            0x52, 0x50, 0x47, 0x4d, 0x56, 0x00, 0x00, 0x00,
            0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val plainHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        )
        val keyBytes = ByteArray(16) { i -> key.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val body = ByteArray(48) { it.toByte() }
        repeat(16) { body[it] = (plainHeader[it].toInt() xor keyBytes[it].toInt()).toByte() }
        val stored = header + body

        val sync = RpgmNative.nativeDecodeAsset(key, stored)
        val latch = CountDownLatch(1)
        val asyncBytes = AtomicReference<ByteArray?>(null)
        RpgmNative.nativeDecodeAssetAsync(
            key,
            stored,
            object : RpgmNative.BytesCallback {
                override fun onSuccess(bytes: ByteArray) {
                    asyncBytes.set(bytes)
                    latch.countDown()
                }

                override fun onError(message: String) {
                    latch.countDown()
                }
            },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertArrayEquals(sync, asyncBytes.get())
        assertArrayEquals(plainHeader, asyncBytes.get()!!.copyOfRange(0, 16))
    }
}
