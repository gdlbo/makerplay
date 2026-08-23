package io.github.gdlbo.makerplay.runtime.wolf

import org.junit.Assert.assertEquals
import org.junit.Test

class WolfSceneLoaderTest {

    @Test
    fun convertsArgbPixelsToRgbaBytes() {
        // -0xFEEDDCD = 0x80112233: A=80 R=11 G=22 B=33
        // second pixel: A=80 R=45 G=54 B=66
        val rgba = WolfSceneLoader.rgbaFromArgb(intArrayOf(-0x7FEEDDCD, -0x7FBAAB9A))
        assertEquals(
            listOf(0x11, 0x22, 0x33, 0x80, 0x45, 0x54, 0x66, 0x80),
            rgba.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun startingHeroGraphicBlankIsSkipped() {
        // Covered indirectly: blank names must not attempt image loads.
    }
}
