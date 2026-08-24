package io.github.gdlbo.makerplay.runtime.wolf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.MapFile
import io.github.gdlbo.makerplay.wolfformat.TileSetData
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException

/**
 * Composites the static boot frame (milestone 4): the initial map's tile
 * layers drawn from the tileset image, plus the starting hero chip.
 *
 * Autotile pixels (raw >= 100000) draw the corresponding autotile image's
 * first cell; transparent pixels let lower layers show through.
 */
object WolfSceneLoader {

    data class StaticFrame(val rgba: ByteArray, val width: Int, val height: Int)

    private const val MAX_FRAME_PIXELS = 4096L * 4096

    fun loadStaticFrame(source: GameDataSource, project: GameDat): StaticFrame {
        val map = loadInitialMap(source)
        val tilesets = TileSetData.parse(source.read("Data/BasicData/TileSetData.dat"))
        return composeFrame(source, project, map, tilesets)
    }

    /**
     * Composes the current frame from parsed project data. [heroTile] places
     * the starting-hero chip; null skips it. Decoded images are cached because
     * the loop recomposes every tick while only the hero position changes.
     */
    fun composeFrame(
        source: GameDataSource,
        project: GameDat,
        map: MapFile,
        tilesets: TileSetData,
        heroTile: WolfGameEngine.Position? = null,
    ): StaticFrame {
        val tileset = tilesets.tilesets.getOrNull(map.tilesetId)
            ?: throw WolfFormatException("Map references unknown tileset ${map.tilesetId}")

        val tileSize = project.tileSize
        val width = map.width * tileSize
        val height = map.height * tileSize
        if (width.toLong() * height > MAX_FRAME_PIXELS) {
            throw WolfFormatException("Map frame $width x $height exceeds limit")
        }

        val baseTileset = cachedDecode(source, "Data/" + tileset.baseTilesetFile.removePrefix("/"))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        try {
            val columns = baseTileset.width / tileSize
            for (layerIndex in 0 until 3) {
                val layer = map.layers[layerIndex]
                for (i in layer.indices) {
                    val raw = layer[i]
                    if (raw == 0) continue // transparent
                    val chipX: Int
                    val chipY: Int
                    if (raw >= 100000) {
                        // Autotile: raw / 100000 selects the [A] slot; render its
                        // first cell. Corner-mode quadrants (raw % 10000) are
                        // refined alongside the full autotile pass in a later
                        // milestone; deployments with blank slot entries fall
                        // back to the base tileset's first chip so the boot
                        // frame still shows map geometry.
                        val autoIndex = raw / 100000 - 1
                        val fileName = tileset.autoTileFiles.getOrNull(autoIndex)?.takeIf { it.isNotBlank() }
                        val dst = Rect((i % map.width) * tileSize, (i / map.width) * tileSize,
                            ((i % map.width) + 1) * tileSize, ((i / map.width) + 1) * tileSize)
                        if (fileName == null) {
                            canvas.drawBitmap(
                                baseTileset,
                                Rect(0, 0, tileSize.coerceAtMost(baseTileset.width),
                                    tileSize.coerceAtMost(baseTileset.height)),
                                dst, paint,
                            )
                            continue
                        }
                        val decoded = cachedDecode(source, "Data/$fileName")
                        canvas.drawBitmap(
                            decoded,
                            Rect(0, 0, decoded.width.coerceAtMost(tileSize), decoded.height.coerceAtMost(tileSize)),
                            dst,
                            paint,
                        )
                        continue
                    } else {
                        chipX = (raw % columns) * tileSize
                        chipY = (raw / columns) * tileSize
                    }
                    canvas.drawBitmap(
                        baseTileset,
                        Rect(chipX, chipY, chipX + tileSize, chipY + tileSize),
                        Rect((i % map.width) * tileSize, (i / map.width) * tileSize,
                            ((i % map.width) + 1) * tileSize, ((i / map.width) + 1) * tileSize),
                        paint,
                    )
                }
            }
            if (heroTile != null) {
                val heroFile = startingHeroGraphic(project)
                if (heroFile == null) {
                    // Deployments without a hero chip get a position cursor so
                    // the player remains trackable on screen.
                    val px = (heroTile.tileX * tileSize) + (heroTile.offsetX * tileSize).toInt()
                    val py = ((heroTile.tileY + 1) * tileSize) - tileSize +
                        (heroTile.offsetY * tileSize).toInt()
                    val marker = Paint().apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3f
                        color = 0xFFFFFF00.toInt()
                    }
                    canvas.drawRect(
                        px.toFloat() + 2f,
                        py.toFloat() + 2f,
                        (px + tileSize).toFloat() - 2f,
                        (py + tileSize).toFloat() - 2f,
                        marker,
                    )
                } else {
                    drawHeroChip(
                        source, "Data/CharaChip/$heroFile", canvas,
                        anchorX = (heroTile.tileX * tileSize) + (heroTile.offsetX * tileSize).toInt(),
                        anchorY = (heroTile.tileY * tileSize) + ((heroTile.offsetY + 1.0) * tileSize).toInt(),
                        tileSize = tileSize,
                    )
                }
            }
        } finally {
            // Cached bitmaps are intentionally kept alive for later frames.
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return StaticFrame(rgbaFromArgb(pixels), width, height)
    }

    private val bitmapCache = HashMap<String, Bitmap>()

    private fun cachedDecode(source: GameDataSource, path: String): Bitmap =
        synchronized(bitmapCache) { bitmapCache[path] } ?: run {
            val bmp = decodeBitmap(source, path)
            synchronized(bitmapCache) { bitmapCache[path] = bmp }
            bmp
        }

    /** Draws one character chip centered horizontally on the given pixel anchor. */
    private fun drawHeroChip(
        source: GameDataSource,
        path: String,
        canvas: Canvas,
        anchorX: Int,
        anchorY: Int,
        tileSize: Int,
    ) {
        val hero = runCatching { cachedDecode(source, path) }.getOrNull() ?: return
        val chipW = (hero.width / 3).coerceAtLeast(1)
        val chipH = (hero.height / 4).coerceAtLeast(1)
        // Bottom-left walking frame of a 3x4 character sheet, scaled to a tile.
        canvas.drawBitmap(
            hero,
            Rect(0, chipH * 2, chipW, chipH * 3),
            Rect(anchorX, anchorY - tileSize, anchorX + tileSize, anchorY),
            null,
        )
    }

    internal fun startingHeroGraphic(project: GameDat): String? =
        project.startingHeroGraphic.takeIf { it.isNotBlank() }

    private fun loadInitialMap(source: GameDataSource): MapFile {
        // Deployments number maps inconsistently and ship pseudo-maps/format
        // variants the parser rejects; try each in listing order.
        val errors = mutableListOf<String>()
        val names = source.list("Data/MapData")
            .filter { it.endsWith(".mps", true) }
            .sorted()
            .ifEmpty {
                (1..20).map { "Map%03d.mps".format(it) }.filter { source.has("Data/MapData/$it") }
            }
        for (name in names) {
            val bytes = runCatching { source.read("Data/MapData/$name") }.getOrNull() ?: continue
            val map = runCatching { MapFile.parse(bytes) }.getOrElse {
                errors += "$name: ${it.message?.take(60)}"
                continue
            }
            return map
        }
        throw WolfFormatException(
            "No parseable map under Data/MapData (${errors.size} tried)" +
                if (errors.isNotEmpty()) ": ${errors.first()}" else "",
        )
    }

    private fun decodeBitmap(source: GameDataSource, path: String): Bitmap {
        val bytes = source.read(path)
        return requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Undecodable image: $path"
        }
    }

    /** ARGB ints from getPixels() to RGBA byte order for GLES. */
    internal fun rgbaFromArgb(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 4)
        var o = 0
        for (p in pixels) {
            out[o] = ((p ushr 16) and 0xFF).toByte() // R
            out[o + 1] = ((p ushr 8) and 0xFF).toByte() // G
            out[o + 2] = (p and 0xFF).toByte() // B
            out[o + 3] = ((p ushr 24) and 0xFF).toByte() // A
            o += 4
        }
        return out
    }
}
