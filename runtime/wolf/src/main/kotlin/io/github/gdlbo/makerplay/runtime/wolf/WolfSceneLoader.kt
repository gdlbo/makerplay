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
        pictures: List<WolfPictureState.Picture> = emptyList(),
        messageText: String? = null,
        choiceOptions: List<String> = emptyList(),
    ): StaticFrame {
        val tileset = tilesets.tilesets.getOrNull(map.tilesetId)
            ?: throw WolfFormatException("Map references unknown tileset ${map.tilesetId}")

        val tileSize = project.tileSize
        // The WOLF drawing area is the game window (Game.dat resolution), not
        // the map size: pictures/messages use window coordinates and large
        // maps scroll inside it. The title map (800x600) sits at the top-left
        // of the 1280x960 window, so a 1280x960 title image is not clipped.
        val width = project.screenWidth.takeIf { it > 0 } ?: map.width * tileSize
        val height = project.screenHeight.takeIf { it > 0 } ?: map.height * tileSize
        if (width.toLong() * height > MAX_FRAME_PIXELS) {
            throw WolfFormatException("Map frame $width x $height exceeds limit")
        }

        val baseTileset = cachedDecode(source, "Data/" + tileset.baseTilesetFile.removePrefix("/"))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Camera follows the hero on maps larger than the game window.
        val mapPixelW = map.width * tileSize
        val mapPixelH = map.height * tileSize
        val heroPx = heroTile?.let {
            (it.tileX * tileSize) + (it.offsetX * tileSize).toInt()
        } ?: 0
        val heroPy = heroTile?.let {
            (it.tileY * tileSize) + (it.offsetY * tileSize).toInt()
        } ?: 0
        val camX = (heroPx + tileSize / 2 - width / 2)
            .coerceIn(0, (mapPixelW - width).coerceAtLeast(0))
        val camY = (heroPy + tileSize / 2 - height / 2)
            .coerceIn(0, (mapPixelH - height).coerceAtLeast(0))

        try {
            val columns = baseTileset.width / tileSize
            for (layerIndex in 0 until 3) {
                val layer = map.layers[layerIndex]
                for (i in layer.indices) {
                    val raw = layer[i]
                    if (raw == 0) continue // transparent
                    val tileMapX = (i % map.width) * tileSize - camX
                    val tileMapY = (i / map.width) * tileSize - camY
                    // Skip tiles fully outside the window.
                    if (tileMapX + tileSize < 0 || tileMapY + tileSize < 0 ||
                        tileMapX >= width || tileMapY >= height
                    ) {
                        continue
                    }
                    val chipX: Int
                    val chipY: Int
                    if (raw >= 100000) {
                        // A packed autotile stores one 0..9 source-cell index for
                        // each 8px quadrant. Its sheet has left/right cells in
                        // columns and the selected mode in rows.
                        val autoIndex = raw / 100000 - 1
                        val fileName = tileset.autoTileFiles.getOrNull(autoIndex)?.takeIf { it.isNotBlank() }
                        if (fileName == null) {
                            canvas.drawBitmap(
                                baseTileset,
                                Rect(0, 0, tileSize.coerceAtMost(baseTileset.width),
                                    tileSize.coerceAtMost(baseTileset.height)),
                                Rect(tileMapX, tileMapY, tileMapX + tileSize, tileMapY + tileSize),
                                paint,
                            )
                            continue
                        }
                        val decoded = cachedDecode(source, "Data/$fileName")
                        drawAutotile(
                            canvas = canvas,
                            bitmap = decoded,
                            packedModes = raw % 10_000,
                            dstX = tileMapX,
                            dstY = tileMapY,
                            tileSize = tileSize,
                            paint = paint,
                        )
                        continue
                    } else {
                        chipX = (raw % columns) * tileSize
                        chipY = (raw / columns) * tileSize
                    }
                    canvas.drawBitmap(
                        baseTileset,
                        Rect(chipX, chipY, chipX + tileSize, chipY + tileSize),
                        Rect(tileMapX, tileMapY, tileMapX + tileSize, tileMapY + tileSize),
                        paint,
                    )
                }
            }
            // Picture layer: event-driven overlays draw above the map.
            val pictureState = WolfPictureState()
            for (picture in pictures) {
                if (picture.isText) {
                    drawTextPicture(canvas, picture, width, height)
                    continue
                }
                val path = pictureState.resolvePath(source, picture.fileName)
                val decoded = path?.let { runCatching { cachedDecode(source, it) }.getOrNull() }
                    ?: continue
                val cols = picture.divisionWidth.coerceAtLeast(1)
                val rows = picture.divisionHeight.coerceAtLeast(1)
                val cellW = (decoded.width / cols).coerceAtLeast(1)
                val cellH = (decoded.height / rows).coerceAtLeast(1)
                val cells = cols * rows
                val pattern = picture.pattern.coerceIn(0, cells - 1)
                val srcX = (pattern % cols) * cellW
                val srcY = (pattern / cols) * cellH
                val originX = if (picture.centerOrigin) picture.x - cellW / 2 else picture.x
                val originY = if (picture.centerOrigin) picture.y - cellH / 2 else picture.y
                val picX = originX.coerceIn(-cellW, width)
                val picY = originY.coerceIn(-cellH, height)
                paint.alpha = picture.opacity.coerceIn(0, 255)
                canvas.drawBitmap(
                    decoded,
                    Rect(srcX, srcY, srcX + cellW, srcY + cellH),
                    Rect(picX, picY, picX + cellW, picY + cellH),
                    paint,
                )
                paint.alpha = 255
            }
            // Hero/cursor above pictures so title-map selection stays visible
            // under fullscreen fog/title layers.
            if (heroTile != null) {
                val heroFile = startingHeroGraphic(project)
                val px = heroPx - camX
                val py = heroPy - camY
                val drewChip = heroFile != null && runCatching {
                    drawHeroChip(
                        source, "Data/CharaChip/$heroFile", canvas,
                        anchorX = px,
                        anchorY = py + tileSize,
                        tileSize = tileSize,
                    )
                    true
                }.getOrDefault(false)
                if (!drewChip) {
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
                }
            }
            // Message window + choices draw into the frame: the GL surface
            // renders above the Compose window, so in-frame is the only
            // reliable presentation layer.
            if (choiceOptions.isNotEmpty()) {
                val boxPaint = Paint().apply { color = 0xB4000000.toInt() }
                val optPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 26f
                }
                val lineH = 34f
                val boxW = 320f
                val boxH = choiceOptions.size * lineH + 20f
                // Anchor near lower-center so letterboxed title frames keep the
                // window inside the visible game rect.
                val boxX = ((width - boxW) / 2f).coerceAtLeast(0f)
                val boxY = (height - boxH - 48f).coerceAtLeast(0f)
                canvas.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, boxPaint)
                choiceOptions.forEachIndexed { idx, option ->
                    canvas.drawText(option, boxX + 14f, boxY + 26f + idx * lineH, optPaint)
                }
            }
            if (!messageText.isNullOrEmpty()) {
                val boxPaint = Paint().apply { color = 0xB4000000.toInt() }
                val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 26f
                }
                val lines = messageText.split("\n")
                val lineH = 34f
                val boxH = lines.size * lineH + 24f
                canvas.drawRect(
                    0f, height - boxH,
                    width.toFloat(), height.toFloat(),
                    boxPaint,
                )
                lines.forEachIndexed { idx, line ->
                    canvas.drawText(line, 16f, height - boxH + 28f + idx * lineH, msgPaint)
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

    /** Renders a string-as-picture slot (display type text / window). */
    private fun drawAutotile(
        canvas: Canvas,
        bitmap: Bitmap,
        packedModes: Int,
        dstX: Int,
        dstY: Int,
        tileSize: Int,
        paint: Paint,
    ) {
        val half = tileSize / 2
        if (half <= 0 || bitmap.width < tileSize || bitmap.height < half) return
        val modes = intArrayOf(
            packedModes / 1_000,
            (packedModes / 100) % 10,
            (packedModes / 10) % 10,
            packedModes % 10,
        )
        for (index in modes.indices) {
            val mode = modes[index]
            val srcY = mode * half
            if (srcY + half > bitmap.height) continue
            val isRight = index == 1 || index == 3
            val isBottom = index >= 2
            val srcX = if (isRight) half else 0
            val x = dstX + if (isRight) half else 0
            val y = dstY + if (isBottom) half else 0
            canvas.drawBitmap(
                bitmap,
                Rect(srcX, srcY, srcX + half, srcY + half),
                Rect(x, y, x + half, y + half),
                paint,
            )
        }
    }

    private fun drawTextPicture(
        canvas: Canvas,
        picture: WolfPictureState.Picture,
        width: Int,
        height: Int,
    ) {
        val cleaned = picture.fileName
            .replace(Regex("""\\f\[\d+]"""), "")
            .replace(Regex("""</?C>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\\[A-Za-z]+\[[^]]*|]"""), "")
            .trim()
        if (cleaned.isEmpty()) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
            alpha = picture.opacity.coerceIn(0, 255)
        }
        val lines = cleaned.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        val lineH = textPaint.textSize + 8f
        val blockH = lines.size * lineH
        var y = if (picture.centerOrigin) {
            picture.y - blockH / 2f + textPaint.textSize
        } else {
            picture.y.toFloat() + textPaint.textSize
        }
        for (line in lines) {
            val tw = textPaint.measureText(line)
            val x = if (picture.centerOrigin) picture.x - tw / 2f else picture.x.toFloat()
            canvas.drawText(
                line,
                x.coerceIn(0f, width.toFloat()),
                y.coerceIn(0f, height.toFloat()),
                textPaint,
            )
            y += lineH
        }
    }

    /** LRU-bounded decode cache: full maps reference hundreds of images. */
    private const val MAX_CACHED_BITMAPS = 64
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            val evict = size > MAX_CACHED_BITMAPS
            if (evict) eldest.value.recycle()
            return evict
        }
    }

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
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Full CGs are often 4k–6k; decode at a screen-friendly size to avoid OOM.
        val maxDim = 2048
        var sample = 1
        val bw = bounds.outWidth.coerceAtLeast(1)
        val bh = bounds.outHeight.coerceAtLeast(1)
        while (bw / sample > maxDim || bh / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            // Force ARGB so palette+alpha PNGs (common for CGs) decode with color.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts),
        ) {
            "Undecodable image: $path"
        }
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return decoded
        if (copy !== decoded) decoded.recycle()
        return copy
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
