package io.github.gdlbo.makerplay.runtime.wolf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.MapFile
import io.github.gdlbo.makerplay.wolfformat.TileSetData
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException

/**
 * High-fidelity frame compositor for WOLF RPG:
 * - Decodes tilesets and character sheets without downsampling.
 * - Resolves 4-quadrant autotile mini-tile patterns (modes 0..9) with frame animation.
 * - Renders character sprites (CharaChip 3x4 / 5x4) with direction & walk cycle animation.
 * - Renders active map events (NPCs, chests, doors, tile chips, charachips).
 * - Multi-layer rendering pipeline with Y-depth sorting and Star (*) overhead tiles.
 * - Picture overlays (slots 1..1000) with zoom, opacity, anchor points, blend modes.
 * - Dialogue window & choice options presentation.
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
     * Composes the current frame from parsed project data and live engine state.
     */
    fun composeFrame(
        source: GameDataSource,
        project: GameDat,
        map: MapFile,
        tilesets: TileSetData,
        cameraTile: WolfGameEngine.Position? = null,
        heroTile: WolfGameEngine.Position? = null,
        heroFacing: WolfGameEngine.Direction = WolfGameEngine.Direction.DOWN,
        activeEvents: List<Pair<MapFile.MapEvent, MapFile.Page>> = emptyList(),
        tickCount: Long = 0L,
        pictures: List<WolfPictureState.Picture> = emptyList(),
        messageText: String? = null,
        choiceOptions: List<String> = emptyList(),
        selectedChoice: Int = 0,
        cameraExtraX: Int = 0,
        cameraExtraY: Int = 0,
        lockedCamX: Int? = null,
        lockedCamY: Int? = null,
    ): StaticFrame {
        val tileset = tilesets.tilesets.getOrNull(map.tilesetId)
            ?: throw WolfFormatException("Map references unknown tileset ${map.tilesetId}")

        val tileSize = project.tileSize
        val width = project.screenWidth.takeIf { it > 0 } ?: (map.width * tileSize)
        val height = project.screenHeight.takeIf { it > 0 } ?: (map.height * tileSize)
        if (width.toLong() * height > MAX_FRAME_PIXELS) {
            throw WolfFormatException("Map frame $width x $height exceeds limit")
        }

        val baseTileset = cachedDecode(source, "Data/" + tileset.baseTilesetFile.removePrefix("/"))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(0xFF101018.toInt())

        // Camera follow logic
        val mapPixelW = map.width * tileSize
        val mapPixelH = map.height * tileSize
        val targetPos = cameraTile ?: heroTile
        val heroPx = targetPos?.let {
            (it.tileX * tileSize) + (it.offsetX * tileSize).toInt()
        } ?: 0
        val heroPy = targetPos?.let {
            (it.tileY * tileSize) + (it.offsetY * tileSize).toInt()
        } ?: 0
        val followX = (heroPx + tileSize / 2 - width / 2)
            .coerceIn(0, (mapPixelW - width).coerceAtLeast(0))
        val followY = (heroPy + tileSize / 2 - height / 2)
            .coerceIn(0, (mapPixelH - height).coerceAtLeast(0))
        val camX = (lockedCamX ?: followX) + cameraExtraX
        val camY = (lockedCamY ?: followY) + cameraExtraY

        val passabilityList = tileset.tilePassability
        val columns = 8
        val srcTileSize = (baseTileset.width / columns).coerceAtLeast(1)

        // ----------------------------------------------------
        // Pass 1: Lower Background Layers (Layer 0, Layer 1 & 2 non-star)
        // ----------------------------------------------------
        val maxLayer = map.layers.size.coerceAtMost(3)
        for (layerIndex in 0 until maxLayer) {
            val layer = map.layers[layerIndex]
            for (i in layer.indices) {
                val raw = layer[i]
                if (raw == 0) continue
                val tileMapX = (i % map.width) * tileSize - camX
                val tileMapY = (i / map.width) * tileSize - camY
                if (tileMapX + tileSize < 0 || tileMapY + tileSize < 0 ||
                    tileMapX >= width || tileMapY >= height
                ) {
                    continue
                }

                // If on layer 1 or 2, skip tiles marked as Star (*) for overhead pass
                if (layerIndex > 0 && raw < 100000) {
                    val pass = passabilityList.getOrNull(raw)
                    if (pass?.star == true) continue
                }

                if (raw >= 100000) {
                    drawAutotileCell(
                        canvas = canvas,
                        source = source,
                        tileset = tileset,
                        baseTileset = baseTileset,
                        raw = raw,
                        dstX = tileMapX,
                        dstY = tileMapY,
                        tileSize = tileSize,
                        tickCount = tickCount,
                        paint = paint,
                    )
                } else {
                    val chipX = (raw % columns) * srcTileSize
                    val chipY = (raw / columns) * srcTileSize
                    if (chipX + srcTileSize <= baseTileset.width && chipY + srcTileSize <= baseTileset.height) {
                        canvas.drawBitmap(
                            baseTileset,
                            Rect(chipX, chipY, chipX + srcTileSize, chipY + srcTileSize),
                            Rect(tileMapX, tileMapY, tileMapX + tileSize, tileMapY + tileSize),
                            paint,
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------
        // Pass 2: Y-Sorted Entities (Map Events & Hero Sprite)
        // ----------------------------------------------------
        data class EntityDraw(val ySort: Double, val draw: () -> Unit)
        val entities = mutableListOf<EntityDraw>()

        // 2a. Map Events
        for ((event, page) in activeEvents) {
            val evX = event.x * tileSize - camX
            val evY = event.y * tileSize - camY
            if (evX + tileSize * 2 < 0 || evY + tileSize * 2 < 0 ||
                evX - tileSize >= width || evY - tileSize >= height
            ) {
                continue
            }

            if (page.graphicFile.isNotBlank()) {
                val chipPath = "Data/CharaChip/" + page.graphicFile.removePrefix("/")
                entities.add(EntityDraw(event.y.toDouble() * tileSize + tileSize) {
                    drawCharaChip(
                        source = source,
                        path = chipPath,
                        canvas = canvas,
                        anchorX = evX,
                        anchorY = evY + tileSize,
                        tileSize = tileSize,
                        row = page.graphicRow,
                        col = page.graphicCol,
                        opacity = page.graphicOpacity,
                        displayType = 0,
                    )
                })
            } else if (page.graphicChipId > 0) {
                val raw = page.graphicChipId
                val chipX = (raw % columns) * tileSize
                val chipY = (raw / columns) * tileSize
                entities.add(EntityDraw(event.y.toDouble() * tileSize + tileSize) {
                    if (chipX + tileSize <= baseTileset.width && chipY + tileSize <= baseTileset.height) {
                        val prevAlpha = paint.alpha
                        paint.alpha = page.graphicOpacity.coerceIn(0, 255)
                        canvas.drawBitmap(
                            baseTileset,
                            Rect(chipX, chipY, chipX + tileSize, chipY + tileSize),
                            Rect(evX, evY, evX + tileSize, evY + tileSize),
                            paint,
                        )
                        paint.alpha = prevAlpha
                    }
                })
            }
        }

        // 2b. Hero Character Sprite
        if (heroTile != null) {
            val heroFile = startingHeroGraphic(project)
            val px = heroPx - camX
            val py = heroPy - camY
            val dirRow = when (heroFacing) {
                WolfGameEngine.Direction.DOWN -> 0
                WolfGameEngine.Direction.LEFT -> 1
                WolfGameEngine.Direction.RIGHT -> 2
                WolfGameEngine.Direction.UP -> 3
            }
            val isMoving = (heroTile.offsetX != 0.0 || heroTile.offsetY != 0.0)
            val stepSeq = intArrayOf(1, 0, 1, 2)
            val walkCycle = if (isMoving) {
                val stepDist = (heroTile.offsetX * 4 + heroTile.offsetY * 4).toInt()
                stepSeq[(kotlin.math.abs(stepDist) + (tickCount / 6).toInt()) % 4]
            } else {
                1 // Standing frame
            }

            entities.add(EntityDraw(heroPy.toDouble() + tileSize) {
                val drewChip = heroFile != null && runCatching {
                    drawCharaChip(
                        source = source,
                        path = "Data/CharaChip/$heroFile",
                        canvas = canvas,
                        anchorX = px,
                        anchorY = py + tileSize,
                        tileSize = tileSize,
                        row = dirRow,
                        col = walkCycle,
                        opacity = 255,
                        displayType = 0,
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
            })
        }

        // Sort entities by Y so lower sprites draw in front of higher sprites
        entities.sortBy { it.ySort }
        for (entity in entities) {
            entity.draw()
        }

        // ----------------------------------------------------
        // Pass 3: Overhead / Star (*) Tiles (Layer 1 & 2 Star, Layer 3)
        // ----------------------------------------------------
        for (layerIndex in 1 until maxLayer) {
            val layer = map.layers[layerIndex]
            val isTopLayer = (layerIndex == 2)
            for (i in layer.indices) {
                val raw = layer[i]
                if (raw == 0) continue
                if (raw < 100000) {
                    val pass = passabilityList.getOrNull(raw)
                    if (!isTopLayer && pass?.star != true) continue
                } else if (!isTopLayer) {
                    continue
                }

                val tileMapX = (i % map.width) * tileSize - camX
                val tileMapY = (i / map.width) * tileSize - camY
                if (tileMapX + tileSize < 0 || tileMapY + tileSize < 0 ||
                    tileMapX >= width || tileMapY >= height
                ) {
                    continue
                }

                if (raw >= 100000) {
                    drawAutotileCell(
                        canvas = canvas,
                        source = source,
                        tileset = tileset,
                        baseTileset = baseTileset,
                        raw = raw,
                        dstX = tileMapX,
                        dstY = tileMapY,
                        tileSize = tileSize,
                        tickCount = tickCount,
                        paint = paint,
                    )
                } else {
                    val chipX = (raw % columns) * srcTileSize
                    val chipY = (raw / columns) * srcTileSize
                    if (chipX + srcTileSize <= baseTileset.width && chipY + srcTileSize <= baseTileset.height) {
                        canvas.drawBitmap(
                            baseTileset,
                            Rect(chipX, chipY, chipX + srcTileSize, chipY + srcTileSize),
                            Rect(tileMapX, tileMapY, tileMapX + tileSize, tileMapY + tileSize),
                            paint,
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------
        // Pass 4: Picture Overlays (Slots 1..1000)
        // ----------------------------------------------------
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
            val zoom = picture.zoom.coerceIn(1, 400)
            val dstW = (cellW * zoom) / 100
            val dstH = (cellH * zoom) / 100
            val originX = when (picture.anchor) {
                1 -> picture.x - dstW / 2
                2 -> picture.x
                3 -> picture.x - dstW
                4 -> picture.x - dstW
                else -> picture.x
            }
            val originY = when (picture.anchor) {
                1 -> picture.y - dstH / 2
                2 -> picture.y - dstH
                3 -> picture.y
                4 -> picture.y - dstH
                else -> picture.y
            }
            canvas.drawBitmap(
                decoded,
                Rect(srcX, srcY, srcX + cellW, srcY + cellH),
                Rect(originX, originY, originX + dstW, originY + dstH),
                paint,
            )
            paint.alpha = 255
        }

        // ----------------------------------------------------
        // Pass 5: In-Frame Dialogue & Choice Windows
        // ----------------------------------------------------
        val shouldDrawChoiceBox = choiceOptions.isNotEmpty() && !choiceOptions.all {
            val t = it.trim()
            t.isEmpty() || t.matches(Regex("""^\d+$"""))
        }
        if (shouldDrawChoiceBox) {
            val boxPaint = Paint().apply { color = 0xEE101824.toInt() }
            val borderPaint = Paint().apply {
                color = 0xFFE8D080.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val optPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 30f
            }
            val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFE08A.toInt()
                textSize = 30f
            }
            val lineH = 40f
            val boxW = minOf(width * 0.72f, 560f)
            val boxH = choiceOptions.size * lineH + 28f
            val boxX = ((width - boxW) / 2f).coerceAtLeast(16f)
            val boxY = (height - boxH - 64f).coerceAtLeast(16f)
            canvas.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, boxPaint)
            canvas.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, borderPaint)
            choiceOptions.forEachIndexed { idx, option ->
                val marker = if (idx == selectedChoice) "> " else "  "
                val p = if (idx == selectedChoice) selPaint else optPaint
                canvas.drawText(marker + option, boxX + 18f, boxY + 32f + idx * lineH, p)
            }
        }

        if (!messageText.isNullOrEmpty()) {
            val boxPaint = Paint().apply { color = 0xB4000000.toInt() }
            val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 26f
            }
            val cleanedText = WolfText.stripPresentationMarkup(messageText)
            val lines = cleanedText.split("\n")
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

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return StaticFrame(rgbaFromArgb(pixels), width, height)
    }

    private fun drawAutotileCell(
        canvas: Canvas,
        source: GameDataSource,
        tileset: TileSetData.Tileset,
        baseTileset: Bitmap,
        raw: Int,
        dstX: Int,
        dstY: Int,
        tileSize: Int,
        tickCount: Long,
        paint: Paint,
    ) {
        val autoIndex = raw / 100000 - 1
        val fileName = tileset.autoTileFiles.getOrNull(autoIndex)?.takeIf { it.isNotBlank() }
        if (fileName == null) {
            canvas.drawBitmap(
                baseTileset,
                Rect(0, 0, tileSize.coerceAtMost(baseTileset.width), tileSize.coerceAtMost(baseTileset.height)),
                Rect(dstX, dstY, dstX + tileSize, dstY + tileSize),
                paint,
            )
            return
        }
        val decoded = cachedDecode(source, "Data/$fileName")
        drawAutotile(
            canvas = canvas,
            bitmap = decoded,
            packedModes = raw % 10_000,
            dstX = dstX,
            dstY = dstY,
            tileSize = tileSize,
            tickCount = tickCount,
            paint = paint,
        )
    }

    /**
     * Renders 4 quadrants of an autotile (modes 0..9 for TL, TR, BL, BR).
     */
    private fun drawAutotile(
        canvas: Canvas,
        bitmap: Bitmap,
        packedModes: Int,
        dstX: Int,
        dstY: Int,
        tileSize: Int,
        tickCount: Long,
        paint: Paint,
    ) {
        val dstHalf = tileSize / 2
        val srcHalf = (bitmap.height / 10).coerceAtLeast(1)
        val srcTileW = srcHalf * 2
        if (dstHalf <= 0 || bitmap.width < srcHalf || bitmap.height < srcHalf) return

        // 3-frame animated autotiles (width >= srcTileW * 3)
        val frameCount = (bitmap.width / srcTileW).coerceAtLeast(1)
        val frameIndex = if (frameCount > 1) ((tickCount / 16) % frameCount).toInt() else 0
        val srcBaseX = frameIndex * srcTileW

        val modes = intArrayOf(
            packedModes / 1_000,
            (packedModes / 100) % 10,
            (packedModes / 10) % 10,
            packedModes % 10,
        )

        for (index in modes.indices) {
            val mode = modes[index]
            val srcY = mode * srcHalf
            if (srcY + srcHalf > bitmap.height) continue
            val isRight = index == 1 || index == 3
            val isBottom = index >= 2
            val srcX = srcBaseX + if (isRight) srcHalf else 0
            if (srcX + srcHalf > bitmap.width) continue
            val x = dstX + if (isRight) dstHalf else 0
            val y = dstY + if (isBottom) dstHalf else 0
            canvas.drawBitmap(
                bitmap,
                Rect(srcX, srcY, srcX + srcHalf, srcY + srcHalf),
                Rect(x, y, x + dstHalf, y + dstHalf),
                paint,
            )
        }
    }

    /**
     * Draws a character sprite from a CharaChip sheet (3x4 or 5x4 layout).
     */
    private fun drawCharaChip(
        source: GameDataSource,
        path: String,
        canvas: Canvas,
        anchorX: Int,
        anchorY: Int,
        tileSize: Int,
        row: Int = 0,
        col: Int = 0,
        opacity: Int = 255,
        displayType: Int = 0,
    ) {
        val sheet = runCatching { cachedDecode(source, path) }.getOrNull() ?: return
        val numCols = if (sheet.width >= tileSize * 5 && sheet.width % 5 == 0) 5 else 3
        val numRows = if (sheet.height >= tileSize * 4 && sheet.height % 4 == 0) 4 else 4
        val cellW = (sheet.width / numCols).coerceAtLeast(1)
        val cellH = (sheet.height / numRows).coerceAtLeast(1)

        val srcX = (col.coerceIn(0, numCols - 1)) * cellW
        val srcY = (row.coerceIn(0, numRows - 1)) * cellH

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = opacity.coerceIn(0, 255)
            when (displayType) {
                1 -> xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                2 -> xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                else -> Unit
            }
        }

        val dstW = tileSize
        val dstH = (cellH * tileSize) / cellW
        val dstLeft = anchorX
        val dstTop = anchorY - dstH
        val dstRight = anchorX + dstW
        val dstBottom = anchorY

        canvas.drawBitmap(
            sheet,
            Rect(srcX, srcY, srcX + cellW, srcY + cellH),
            Rect(dstLeft, dstTop, dstRight, dstBottom),
            paint,
        )
    }

    private fun drawTextPicture(
        canvas: Canvas,
        picture: WolfPictureState.Picture,
        width: Int,
        height: Int,
    ) {
        if (WolfPictureState.isSquarePrimitive(picture.fileName)) {
            val fillW = (picture.fillWidth ?: width).coerceIn(1, width * 2)
            val fillH = (picture.fillHeight ?: height).coerceIn(1, height * 2)
            val color = picture.fillColor ?: 0xFF000000.toInt()
            val rectPaint = Paint().apply {
                this.color = color
                alpha = picture.opacity.coerceIn(0, 255)
            }
            val left = picture.x.toFloat()
            val top = picture.y.toFloat()
            canvas.drawRect(left, top, left + fillW, top + fillH, rectPaint)
            return
        }
        val cleaned = WolfText.stripPresentationMarkup(picture.fileName)
        if (cleaned.isEmpty()) return
        val fontSize = if (picture.divisionHeight in 6..128) picture.divisionHeight.toFloat() else 24f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = picture.fillColor ?: 0xFFFFFFFF.toInt()
            textSize = fontSize
            alpha = picture.opacity.coerceIn(0, 255)
        }
        val lines = cleaned.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        val lineSpacing = if (picture.divisionWidth in 1..64) picture.divisionWidth.toFloat() else 4f
        val lineH = textPaint.textSize + lineSpacing
        val blockH = lines.size * lineH
        var y = when (picture.anchor) {
            1 -> picture.y - blockH / 2f + textPaint.textSize
            2 -> picture.y - blockH + textPaint.textSize
            3 -> picture.y.toFloat() + textPaint.textSize
            4 -> picture.y - blockH + textPaint.textSize
            else -> picture.y.toFloat() + textPaint.textSize
        }
        for (line in lines) {
            val tw = textPaint.measureText(line)
            val x = when (picture.anchor) {
                1 -> picture.x - tw / 2f
                2 -> picture.x.toFloat()
                3 -> picture.x - tw
                4 -> picture.x - tw
                else -> picture.x.toFloat()
            }
            canvas.drawText(
                line,
                x,
                y,
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

    internal fun startingHeroGraphic(project: GameDat): String? =
        project.startingHeroGraphic.takeIf { it.isNotBlank() }

    private fun loadInitialMap(source: GameDataSource): MapFile {
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
        val isTilesetOrSprite = path.contains("MapChip", ignoreCase = true) ||
            path.contains("CharaChip", ignoreCase = true) ||
            path.contains("Tile", ignoreCase = true) ||
            path.contains("BaseChip", ignoreCase = true)

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        // Tilesets and character sheets must NOT be downsampled so tile UVs match exact pixels.
        val maxDim = if (isTilesetOrSprite) 16384 else 2048
        var sample = 1
        val bw = bounds.outWidth.coerceAtLeast(1)
        val bh = bounds.outHeight.coerceAtLeast(1)
        while (bw / sample > maxDim || bh / sample > maxDim) sample *= 2

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
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
