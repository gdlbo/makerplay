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

    /**
     * Largest frame the composer will allocate.
     *
     * This is a memory ceiling, not a layout rule. It used to be applied as
     * `coerceIn(1, 2048)` on the width and height, which silently cropped any map
     * larger than 51 tiles at 40 px -- every dungeon in this deployment (Map146 is
     * 60x62) rendered as a mostly-black frame with a diagonal band, and the
     * crop looked like a rendering bug rather than a clamp. Dimensions are now
     * validated instead of clamped so an oversized request fails loudly.
     */
    // Measured across this deployment's 305 maps: the largest is Map034 at
    // 104x72 tiles = 4160x2880 px (13516800 px, ~54 MB as ARGB_8888), and 75 maps
    // exceed 2048 px in at least one axis. The ceiling must clear the real maximum
    // or whole-map composition is impossible for a quarter of the game.
    private const val MAX_FRAME_PIXELS = 4608L * 4608
    private const val MAX_FRAME_EDGE = 4608

    /**
     * Display type for お手軽ウィンドウ (easy window), the 9-slice picture form.
     * Read from the low byte of the picture option word.
     */
    private const val DISPLAY_TYPE_EASY_WINDOW = 4

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
        /**
         * Receives each picture's drawn bounds (slot, left, top, right, bottom)
         * in virtual screen pixels. The runtime records these so the engine's
         * "picture under the cursor" query can be answered, which is how WOLF's
         * mouse-driven picture menus decide what was clicked.
         */
        onPictureBounds: ((Int, Int, Int, Int, Int) -> Unit)? = null,
    ): StaticFrame {
        val tileset = tilesets.tilesets.getOrNull(map.tilesetId)
            ?: throw WolfFormatException("Map references unknown tileset ${map.tilesetId}")

        val tileSize = project.tileSize
        val width = (project.screenWidth.takeIf { it > 0 } ?: (map.width * tileSize))
        val height = (project.screenHeight.takeIf { it > 0 } ?: (map.height * tileSize))
        if (width <= 0 || height <= 0) {
            throw WolfFormatException("Map frame has a non-positive size ${width}x$height")
        }
        // Refuse rather than clamp: a silent crop renders a plausible-looking but
        // wrong frame, which is far harder to notice than an error.
        if (width > MAX_FRAME_EDGE || height > MAX_FRAME_EDGE ||
            width.toLong() * height > MAX_FRAME_PIXELS
        ) {
            throw WolfFormatException(
                "Map frame $width x $height exceeds the ${MAX_FRAME_EDGE}px/" +
                    "${MAX_FRAME_PIXELS}px limit",
            )
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
        // Base-chip entries come first; the 32 entries after them belong to the
        // autotile slots (verified for all 53 tilesets in this deployment).
        val autotileBase = (passabilityList.size - WolfGameEngine.AUTOTILE_SLOTS).coerceAtLeast(0)
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

                // If on layer 1 or 2, skip tiles marked as Star (*) for overhead pass.
                // Autotiles carry their own entry appended after the base sheet.
                if (layerIndex > 0) {
                    val pass = if (raw < 100000) {
                        passabilityList.getOrNull(raw)
                    } else {
                        passabilityList.getOrNull(autotileBase + (raw / 100000 - 1))
                    }
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
                // `graphicFile` may be Data-relative with its own folder
                // ("CharaChip/[npc]f2_1.png", "CharaChipA/b_02.png",
                // "SystemFile/[mapchip]dummy.png") or a bare chip name. Prefixing
                // "CharaChip/" unconditionally produced
                // Data/CharaChip/CharaChip/..., which does not exist -- so NPC event
                // sprites never drew. Accept the name as-is when it already resolves,
                // and only fall back to the CharaChip/ prefix otherwise.
                val rel = page.graphicFile.removePrefix("/")
                val direct = "Data/$rel"
                val chipPath = if (source.has(direct)) direct else "Data/CharaChip/$rel"
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
                        // `startingHeroGraphic` is already Data-relative and carries
                        // its own folder ("CharaChipA/a_02.png"). Prefixing
                        // "CharaChip/" produced Data/CharaChip/CharaChipA/a_02.png,
                        // which does not exist -- so the hero never drew and the
                        // renderer fell back to its missing-sprite marker.
                        path = "Data/" + heroFile.removePrefix("/"),
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
                    val pass = passabilityList.getOrNull(autotileBase + (raw / 100000 - 1))
                    if (pass?.star != true) continue
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
        var picturesDrawn = 0
        var picturesSkipped = 0
        for (picture in pictures) {
            if (picture.isText) {
                drawTextPicture(canvas, picture, width, height)
                picturesDrawn++
                continue
            }
            val path = synchronized(picturePathCache) {
                if (picturePathCache.containsKey(picture.fileName)) {
                    picturePathCache[picture.fileName]
                } else {
                    pictureState.resolvePath(source, picture.fileName).also {
                        picturePathCache[picture.fileName] = it
                    }
                }
            }
            val decoded = path?.let { runCatching { cachedDecode(source, it) }.getOrNull() }
            if (decoded == null) {
                picturesSkipped++
                continue
            }
            val cols = picture.divisionWidth.coerceAtLeast(1)
            val rows = picture.divisionHeight.coerceAtLeast(1)
            val cellW = (decoded.width / cols).coerceAtLeast(1)
            val cellH = (decoded.height / rows).coerceAtLeast(1)
            val cells = cols * rows
            val pattern = picture.pattern.coerceIn(0, cells - 1)
            val srcX = (pattern % cols) * cellW
            val srcY = (pattern / cols) * cellH
            // Zoom and rotation. The manual's rule that separate X/Y zoom is
            // ignored once the picture is rotated is applied by effectiveZoom().
            val transform = PictureTransform.fromCommand(
                zoomPercent = picture.zoom,
                angleTenths = picture.angleTenths,
                zoomYPercent = picture.zoomY,
            )
            val (zoomX, zoomY) = transform.effectiveZoom()
            val dstW = (cellW * zoomX) / 100
            val dstH = (cellH * zoomY) / 100
            // Anchor decides which part of the picture sits on (x, y). The old
            // when-block here only handled five cases and mapped two of them
            // wrongly; PictureAnchor covers all seven documented modes and is
            // tested against the deployment's own recorded rectangles.
            val (originX, originY) = PicturePlacement.topLeft(
                x = picture.x,
                y = picture.y,
                width = dstW,
                height = dstH,
                anchor = PictureAnchor.fromCode(picture.anchor),
            )
            val prevAlpha = paint.alpha
            paint.alpha = picture.opacity.coerceIn(0, 255)

            // お手軽ウィンドウ (easy window): a 9-slice, where the corners keep
            // their scale and the edges/centre stretch to the target box. The
            // manual describes it as a three-part split; rendering it as a plain
            // stretch would distort the frame's corners.
            if (picture.displayType == DISPLAY_TYPE_EASY_WINDOW &&
                picture.fillWidth != null && picture.fillHeight != null
            ) {
                val boxW = picture.fillWidth
                val boxH = picture.fillHeight
                val slices = PictureGeometry.nineSlice(
                    srcW = cellW,
                    srcH = cellH,
                    dstX = originX,
                    dstY = originY,
                    dstW = boxW,
                    dstH = boxH,
                )
                for (slice in slices) {
                    if (slice.dstRight <= slice.dstLeft || slice.dstBottom <= slice.dstTop) continue
                    canvas.drawBitmap(
                        decoded,
                        Rect(
                            srcX + slice.srcLeft, srcY + slice.srcTop,
                            srcX + slice.srcRight, srcY + slice.srcBottom,
                        ),
                        Rect(slice.dstLeft, slice.dstTop, slice.dstRight, slice.dstBottom),
                        paint,
                    )
                }
                paint.alpha = prevAlpha
                pictureState.recordDrawnBounds(
                    picture.slot, originX, originY, originX + boxW, originY + boxH,
                )
                onPictureBounds?.invoke(picture.slot, originX, originY, originX + boxW, originY + boxH)
                picturesDrawn++
                continue
            }
            // Blend mode from the option word. The old code had no picture blend
            // at all, so additive/subtractive/multiply overlays all drew as plain
            // replacements.
            val prevXfermode = paint.xfermode
            val prevColorFilter = paint.colorFilter
            // Per-picture colour correction. The parameters are percent with 100
            // neutral, and the engine converts each with `(v - 100) * 2.55` before
            // adding it to the channel (sub_990570), so the offset must be the
            // converted delta, not the raw parameter.
            val tintR = PictureBlend.engineColourChannel(picture.colourR)
            val tintG = PictureBlend.engineColourChannel(picture.colourG)
            val tintB = PictureBlend.engineColourChannel(picture.colourB)
            if (tintR != 0 || tintG != 0 || tintB != 0) {
                val matrix = android.graphics.ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, tintR.toFloat(),
                        0f, 1f, 0f, 0f, tintG.toFloat(),
                        0f, 0f, 1f, 0f, tintB.toFloat(),
                        0f, 0f, 0f, 1f, 0f,
                    ))
                }
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            }
            when (PictureBlend.resolveMode(picture.blendMode, PictureBlend.NORMAL)) {
                PictureBlend.ADD -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                PictureBlend.MULTIPLY -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                PictureBlend.SUBTRACT -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OUT)
                else -> paint.xfermode = null
            }
            if (transform.isRotated) {
                // Rotate about the anchor: save, move the origin to the anchor
                // point, rotate, then draw the source rect centred on that point.
                val anchor = PictureAnchor.fromCode(picture.anchor)
                val ax = (dstW * anchor.xFactor).toFloat()
                val ay = (dstH * anchor.yFactor).toFloat()
                canvas.save()
                canvas.rotate(transform.angleDegrees.toFloat(), originX + ax, originY + ay)
                canvas.drawBitmap(
                    decoded,
                    Rect(srcX, srcY, srcX + cellW, srcY + cellH),
                    Rect(originX, originY, originX + dstW, originY + dstH),
                    paint,
                )
                canvas.restore()
            } else {
                canvas.drawBitmap(
                    decoded,
                    Rect(srcX, srcY, srcX + cellW, srcY + cellH),
                    Rect(originX, originY, originX + dstW, originY + dstH),
                    paint,
                )
            }
            paint.alpha = prevAlpha
            paint.xfermode = prevXfermode
            paint.colorFilter = prevColorFilter
            // Remember where this slot landed so the interpreter can answer the
            // engine's "picture under the cursor" query.
            pictureState.recordDrawnBounds(
                picture.slot,
                originX,
                originY,
                originX + dstW,
                originY + dstH,
            )
            onPictureBounds?.invoke(
                picture.slot,
                originX,
                originY,
                originX + dstW,
                originY + dstH,
            )
            picturesDrawn++
        }
        if (picturesSkipped > 0 && picturesDrawn == 0) {
            android.util.Log.w(
                "WolfScene",
                "pics drawn=0 skipped=$picturesSkipped of ${pictures.size}",
            )
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
     * Renders an autotile cell.
     *
     * The four digits of `raw % 10000` are the quadrant modes (TL, TR, BL, BR)
     * and each mode selects one **whole** autotile frame from the sheet. Sheets
     * are `tileSize` wide by `10 * tileSize` tall (the shipped ones are 40x200:
     * ten 40x40 frames); `200`, `202`, `214` and `4444` in Map114 are the same
     * autotile with different quadrant combinations.
     *
     * Each quadrant then samples the corresponding quarter of that frame. The
     * previous implementation used a half-tile row stride (`height / 10`), which
     * sampled at y = mode * 20 and produced the repeating speckled pattern seen
     * against the original's coherent grass and foliage.
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
        val frame = tileSize
        if (frame <= 0 || bitmap.width < frame || bitmap.height < frame) return
        val dstHalf = frame / 2
        val srcHalf = frame / 2

        // Horizontally animated sheets hold several frames side by side.
        val frameCount = (bitmap.width / frame).coerceAtLeast(1)
        val frameIndex = if (frameCount > 1) ((tickCount / 16) % frameCount).toInt() else 0
        val srcBaseX = frameIndex * frame

        val modes = intArrayOf(
            packedModes / 1_000,
            (packedModes / 100) % 10,
            (packedModes / 10) % 10,
            packedModes % 10,
        )

        for (index in modes.indices) {
            val mode = modes[index]
            // Whole-frame row: mode 0..9 selects one of ten frames.
            val srcY = mode * frame
            if (srcY + frame > bitmap.height) continue
            val isRight = index == 1 || index == 3
            val isBottom = index >= 2
            val srcX = srcBaseX + if (isRight) srcHalf else 0
            if (srcX + srcHalf > bitmap.width) continue
            val srcYOffset = srcY + if (isBottom) srcHalf else 0
            val x = dstX + if (isRight) dstHalf else 0
            val y = dstY + if (isBottom) dstHalf else 0
            canvas.drawBitmap(
                bitmap,
                Rect(srcX, srcYOffset, srcX + srcHalf, srcYOffset + srcHalf),
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

    /** LRU-bounded decode cache: title UIs + tilesets easily exceed 64 images. */
    private const val MAX_CACHED_BITMAPS = 256
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            // Eviction must not recycle either: a bitmap evicted here may still
            // be referenced by an in-flight compose pass. See clearCache().
            return size > MAX_CACHED_BITMAPS
        }
    }
    /** Positive + negative picture path lookups; misses are expensive on Direct mode. */
    private val picturePathCache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean =
            size > MAX_CACHED_BITMAPS
    }

    fun clearCache() {
        // Do NOT recycle here. composeFrame runs on a worker coroutine and can be
        // mid-draw holding a previously fetched bitmap when a map change calls
        // this; recycling it out from under Canvas.drawBitmap aborts the process
        // with SIGABRT from android::CanvasJNI::drawBitmapRect (observed on
        // device: WolfSceneLoader.kt drawBitmap after a teleport). Dropping the
        // references is enough: the GC reclaims unreferenced bitmaps, and the
        // cache is rebuilt lazily on the next compose.
        synchronized(bitmapCache) { bitmapCache.clear() }
        synchronized(picturePathCache) { picturePathCache.clear() }
    }

    private fun cachedDecode(source: GameDataSource, path: String): Bitmap =
        synchronized(bitmapCache) { bitmapCache[path]?.takeUnless { it.isRecycled } } ?: run {
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
