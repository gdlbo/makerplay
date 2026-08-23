package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for WOLF RPG `Game.dat` (project settings).
 *
 * Layout per docs/wolf-rpg-runtime.md references (djytw/wolf-rpg-formats,
 * MIT): magic + version byte, a u8 settings block, a string settings block,
 * sizes, a u16 settings block holding the screen dimensions, static randoms,
 * and the version footer.
 */
data class GameDat(
    val v3: Boolean,
    /** Editor revision word from the u16 settings (e.g. 0x0202 / 0x030x). */
    val wolfVersionWord: Int,
    val title: String,
    val serial: String,
    /** Key string used to derive `.wolf` archive encryption keys. */
    val encryptionKey: String,
    /** File name of the starting hero character chip (string slot 7). */
    val startingHeroGraphic: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Tile edge in pixels: 16, 32, 40 or 48. */
    val tileSize: Int,
    /** Target frame rate: 30 or 60. */
    val fps: Int,
    /** Raw hero/allies move speed byte (4 = 1x, up to 8 = 2x, 9 = custom). */
    val heroMoveSpeed: Int,
) {
    companion object {
        fun parse(data: ByteArray): GameDat {
            if (data.size > BoundedReader.Limits.DEFAULT.maxFileBytes) {
                throw WolfFormatException("Game.dat exceeds size limit")
            }
            val reader = BoundedReader(data)
            // Game.dat layout: core magic + 0x00,'F','M' then the version byte.
            val core = reader.readBytes(WolfContainer.CORE_MAGIC.size, "WOLF magic")
            if (!core.contentEquals(WolfContainer.CORE_MAGIC)) {
                throw WolfFormatException("Not a WOLF data file")
            }
            val preTag = reader.readBytes(4, "Game.dat tag")
            if (preTag[0] != 0x00.toByte() || preTag[1] != 'F'.code.toByte() ||
                preTag[2] != 'M'.code.toByte()
            ) {
                throw WolfFormatException("Not a Game.dat file")
            }
            val v3 = when (val version = preTag[3].toInt() and 0xFF) {
                WolfContainer.VERSION_V2 -> false
                WolfContainer.VERSION_V3 -> true
                else -> throw WolfFormatException(
                    "Unknown WOLF version header 0x${version.toString(16)}",
                )
            }

            val u8len = reader.readU4().toInt()
            if (u8len !in 20..64) {
                throw WolfFormatException("Unexpected Game.dat u8 settings length $u8len")
            }
            val u8Settings = reader.slice(u8len, "Game.dat u8 settings")
            val tileSize = when (val raw = u8Settings.readU1()) {
                16, 32, 40, 48 -> raw
                else -> throw WolfFormatException("Unknown tile size $raw")
            }
            // u8 settings indices (per game_dat.ksy): 4 = fps,
            // 15 = event move speed, 16 = hero/allies move speed.
            val fpsByte = u8SettingsByte(data, 4)
            val heroMoveSpeedByte = u8SettingsByte(data, 16).coerceIn(4, 9)

            // v3 has thirteen strings; v2 has eight Shift-JIS strings plus one trailing.
            val stringCount = if (v3) 13 else 9
            val stringBlockLen = reader.readU4()
            if (stringBlockLen > BoundedReader.Limits.DEFAULT.maxStringBytes) {
                throw WolfFormatException("Game.dat string block too large: $stringBlockLen")
            }
            var title = ""
            var serial = ""
            var encryptionKey = ""
            var startingHeroGraphic = ""
            repeat(stringCount) { index ->
                val value = reader.readString(v3)
                when (index) {
                    0 -> title = value
                    1 -> serial = value
                    2 -> encryptionKey = value
                    7 -> startingHeroGraphic = value
                }
            }

            reader.readU4() // static randoms section size marker ("filesize")
            reader.readU4() // unknown

            val u16Count = reader.readCount("Game.dat u16 settings").also {
                if (it < 19) throw WolfFormatException("Game.dat u16 settings too short: $it")
            }
            var width = 0
            var height = 0
            var versionWord = 0
            for (i in 0 until u16Count) {
                val value = reader.readU2()
                when (i) {
                    16 -> width = value
                    17 -> height = value
                    18 -> versionWord = value
                }
            }
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
                throw WolfFormatException("Unreasonable Game.dat resolution ${width}x$height")
            }

            return GameDat(
                v3 = v3,
                wolfVersionWord = versionWord,
                title = title,
                serial = serial,
                encryptionKey = encryptionKey,
                startingHeroGraphic = startingHeroGraphic,
                screenWidth = width,
                screenHeight = height,
                tileSize = tileSize,
                fps = if (fpsByte == 60) 60 else 30,
                heroMoveSpeed = heroMoveSpeedByte,
            )
        }

        private fun u8SettingsByte(data: ByteArray, index: Int): Int {
            // Layout: 9 magic + 1 version + 4 len(u8 block). The block itself follows.
            val base = 9 + 1 + 4
            if (base + index >= data.size) throw WolfFormatException("Game.dat truncated in u8 settings")
            return data[base + index].toInt() and 0xFF
        }
    }
}
