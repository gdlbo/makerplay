package io.github.gdlbo.makerplay.vfs

import io.github.gdlbo.makerplay.codec.AssetCodecRegistry
import io.github.gdlbo.makerplay.codec.SeekableAssetSource
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

enum class ResolutionKind {
    EXACT,
    CASE_FOLDED,
    MZ_ENCRYPTED,
    MV_ENCRYPTED,
    CUSTOM_ALIAS,
    MEDIA_FORMAT_FALLBACK,
    MISSING_SEPARATOR_FALLBACK,
}

data class AssetAlias(
    val logicalPath: GamePath,
    val storedPath: GamePath,
    val codecId: String,
)

data class ResolvedAsset(
    val logicalPath: GamePath,
    val storedPath: GamePath,
    val kind: ResolutionKind,
    val codecId: String?,
    val storedSize: Long,
    val lastModifiedMillis: Long,
    val mimeType: String,
    val entityTag: String,
)

data class ByteRange(
    val startInclusive: Long,
    val endInclusive: Long? = null,
) {
    init {
        require(startInclusive >= 0L) { "Range start must not be negative" }
        require(endInclusive == null || endInclusive >= startInclusive) { "Range end precedes its start" }
    }
}

sealed interface VfsOpenResult {
    data object Missing : VfsOpenResult
    data class RequiresCodec(val asset: ResolvedAsset) : VfsOpenResult
    data class InvalidAsset(val asset: ResolvedAsset) : VfsOpenResult
    data class RangeNotSatisfiable(val completeLength: Long) : VfsOpenResult
    data class Found(
        val asset: ResolvedAsset,
        val stream: InputStream,
        val contentLength: Long,
        val contentRange: String?,
    ) : VfsOpenResult
}

class GameFileSystem(
    private val index: GameFileIndex,
    aliases: List<AssetAlias> = emptyList(),
    private val codecs: AssetCodecRegistry = AssetCodecRegistry.EMPTY,
) {
    private val exactAliases = aliases.uniqueBy { it.logicalPath.value }
    private val foldedAliases = aliases.uniqueBy { it.logicalPath.folded }
    private val resolutionCache = ResolutionCache(MAX_CACHE_ENTRIES)

    fun resolve(rawPath: String): ResolvedAsset? {
        val path = try {
            GamePath.parse(rawPath)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (val cached = resolutionCache.getOrPut(path.value) { resolveUncached(path) }) {
            CachedResolution.Missing -> null
            is CachedResolution.Found -> cached.asset
        }
    }

    /** Absolute on-disk file for a non-codec asset, or null when missing/encrypted/unavailable. */
    fun absoluteFile(rawPath: String): java.io.File? {
        val asset = resolve(rawPath) ?: return null
        if (asset.codecId != null) return null
        val entry = index.exact(asset.storedPath) ?: index.folded(asset.storedPath) ?: return null
        return index.file(entry)
    }

    fun list(rawPath: String): List<String>? {
        val directory = if (rawPath.isBlank() || rawPath == "/") {
            ""
        } else {
            try {
                GamePath.parse(rawPath).value
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        return index.list(directory)
    }

    fun open(rawPath: String, range: ByteRange? = null): VfsOpenResult {
        val asset = resolve(rawPath) ?: return VfsOpenResult.Missing
        val codec = asset.codecId?.let(codecs::get)
        if (asset.codecId != null && codec == null) return VfsOpenResult.RequiresCodec(asset)
        val entry = index.exact(asset.storedPath) ?: index.folded(asset.storedPath)
        ?: return VfsOpenResult.Missing
        val file = index.file(entry) ?: return VfsOpenResult.Missing
        if (
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(file.toPath()) ||
            file.length() != entry.size ||
            file.lastModified() != entry.lastModifiedMillis
        ) {
            return VfsOpenResult.Missing
        }
        val completeLength = try {
            codec?.logicalLength(entry.size) ?: entry.size
        } catch (_: Exception) {
            return VfsOpenResult.InvalidAsset(asset)
        }
        if (completeLength !in 0L..entry.size) return VfsOpenResult.InvalidAsset(asset)
        val source = try {
            FileAssetSource.open(file, entry.size)
        } catch (_: Exception) {
            return VfsOpenResult.Missing
        }
        val start = range?.startInclusive ?: 0L
        if (range != null && start >= completeLength) {
            if (codec != null) {
                try {
                    codec.open(source, 0, 0).close()
                } catch (_: Exception) {
                    runCatching(source::close)
                    return VfsOpenResult.InvalidAsset(asset)
                }
            } else {
                source.close()
            }
            return VfsOpenResult.RangeNotSatisfiable(completeLength)
        }
        val end = when {
            completeLength == 0L -> -1L
            range?.endInclusive == null -> completeLength - 1
            else -> minOf(range.endInclusive, completeLength - 1)
        }
        val contentLength = if (completeLength == 0L) 0L else end - start + 1
        val stream = try {
            codec?.open(source, start, contentLength) ?: SourceRangeInputStream(
                source,
                start,
                contentLength
            )
        } catch (_: Exception) {
            runCatching(source::close)
            return VfsOpenResult.InvalidAsset(asset)
        }
        val responseAsset = if (codec == null) {
            asset
        } else {
            asset.copy(
                entityTag = "W/\"${completeLength.toString(16)}-${
                    entry.lastModifiedMillis.toString(
                        16
                    )
                }-" +
                        "${codec.cacheTag}\"",
            )
        }
        return VfsOpenResult.Found(
            asset = responseAsset,
            stream = stream,
            contentLength = contentLength,
            contentRange = range?.let { "bytes $start-$end/$completeLength" },
        )
    }

    private fun resolveUncached(path: GamePath): CachedResolution {
        resolveStandard(path)?.let { return it }

        val recovered = (1 until path.value.lastIndex).asSequence()
            .filter { index -> path.value[index - 1] != '/' && path.value[index] != '/' }
            .mapNotNull { index ->
                runCatching {
                    GamePath.parse(
                        path.value.substring(
                            0,
                            index
                        ) + "/" + path.value.substring(index)
                    )
                }.getOrNull()
            }
            .mapNotNull(::resolveStandard)
            .distinctBy { it.asset.storedPath.folded }
            .take(2)
            .toList()
        return recovered.singleOrNull()?.let { found ->
            CachedResolution.Found(
                found.asset.copy(
                    logicalPath = path,
                    kind = ResolutionKind.MISSING_SEPARATOR_FALLBACK,
                ),
            )
        } ?: CachedResolution.Missing
    }

    private fun resolveStandard(path: GamePath): CachedResolution.Found? {
        index.exact(path)?.let { return it.resolved(path, ResolutionKind.EXACT) }
        index.folded(path)?.let { return it.resolved(path, ResolutionKind.CASE_FOLDED) }

        findCandidate(GamePath.parse("${path.value}_"))
            ?.resolved(path, ResolutionKind.MZ_ENCRYPTED, STANDARD_CODEC_ID)
            ?.let { return it }
        encryptedCandidates(path, MV_SUFFIXES).firstNotNullOfOrNull { candidate ->
            findCandidate(candidate)?.resolved(path, ResolutionKind.MV_ENCRYPTED, STANDARD_CODEC_ID)
        }?.let { return it }

        (exactAliases[path.value] ?: foldedAliases[path.folded])?.let { alias ->
            findCandidate(alias.storedPath)?.let { entry ->
                return entry.resolved(path, ResolutionKind.CUSTOM_ALIAS, alias.codecId)
            }
        }
        mediaFormatFallbacks(path).firstNotNullOfOrNull { fallback ->
            resolveMediaFallback(path, fallback)
        }?.let { return it }
        return null
    }

    private fun findCandidate(path: GamePath): IndexedGameFile? =
        index.exact(path) ?: index.folded(path)

    private fun mediaFormatFallbacks(path: GamePath): List<GamePath> {
        val suffixes = when {
            path.value.startsWith("audio/", ignoreCase = true) -> AUDIO_FORMAT_VARIANTS
            path.value.startsWith("movies/", ignoreCase = true) -> VIDEO_FORMAT_VARIANTS
            else -> return emptyList()
        }
        val matched = suffixes.entries.firstOrNull { (suffix, _) ->
            path.value.endsWith(suffix, ignoreCase = true)
        } ?: return emptyList()
        val base = path.value.dropLast(matched.key.length)
        return matched.value.map { GamePath.parse(base + it) }
    }

    private fun resolveMediaFallback(
        logicalPath: GamePath,
        fallbackPath: GamePath,
    ): CachedResolution.Found? {
        findCandidate(fallbackPath)?.let { entry ->
            return entry.resolved(
                logicalPath = logicalPath,
                kind = ResolutionKind.MEDIA_FORMAT_FALLBACK,
                mimePath = fallbackPath,
            )
        }
        findCandidate(GamePath.parse("${fallbackPath.value}_"))?.let { entry ->
            return entry.resolved(
                logicalPath = logicalPath,
                kind = ResolutionKind.MEDIA_FORMAT_FALLBACK,
                codecId = STANDARD_CODEC_ID,
                mimePath = fallbackPath,
            )
        }
        return encryptedCandidates(fallbackPath, MV_SUFFIXES).firstNotNullOfOrNull { candidate ->
            findCandidate(candidate)?.resolved(
                logicalPath = logicalPath,
                kind = ResolutionKind.MEDIA_FORMAT_FALLBACK,
                codecId = STANDARD_CODEC_ID,
                mimePath = fallbackPath,
            )
        }
    }

    private fun <K> List<AssetAlias>.uniqueBy(key: (AssetAlias) -> K): Map<K, AssetAlias> =
        buildMap {
            this@uniqueBy.forEach { alias ->
                require(
                    put(
                        key(alias),
                        alias
                    ) == null
                ) { "Asset aliases must have unique logical paths" }
            }
        }

    private fun encryptedCandidates(
        logicalPath: GamePath,
        suffixes: Map<String, String>,
    ): List<GamePath> {
        val extension = logicalPath.value.substringAfterLast('.', missingDelimiterValue = "")
        val storedSuffix =
            suffixes[extension.lowercase(java.util.Locale.ROOT)] ?: return emptyList()
        val base = logicalPath.value.dropLast(extension.length + 1)
        return listOf(GamePath.parse("$base$storedSuffix"))
    }

    private fun IndexedGameFile.resolved(
        logicalPath: GamePath,
        kind: ResolutionKind,
        codecId: String? = null,
        mimePath: GamePath = logicalPath,
    ): CachedResolution.Found = CachedResolution.Found(
        ResolvedAsset(
            logicalPath = logicalPath,
            storedPath = path,
            kind = kind,
            codecId = codecId,
            storedSize = size,
            lastModifiedMillis = lastModifiedMillis,
            mimeType = MimeTypes.forPath(mimePath),
            entityTag = "W/\"${size.toString(16)}-${lastModifiedMillis.toString(16)}\"",
        ),
    )

    internal sealed interface CachedResolution {
        data object Missing : CachedResolution
        data class Found(val asset: ResolvedAsset) : CachedResolution
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 1024
        const val STANDARD_CODEC_ID = "rpg-maker-standard"
        val MV_SUFFIXES = mapOf(
            "png" to ".rpgmvp",
            "ogg" to ".rpgmvo",
            "m4a" to ".rpgmvm",
        )
        val AUDIO_FORMAT_VARIANTS = linkedMapOf(
            ".rpgmvm" to listOf(".rpgmvo"),
            ".rpgmvo" to listOf(".rpgmvm"),
            ".m4a_" to listOf(".ogg_"),
            ".ogg_" to listOf(".m4a_"),
            ".m4a" to listOf(".ogg"),
            ".ogg" to listOf(".m4a"),
        )
        val VIDEO_FORMAT_VARIANTS = linkedMapOf(
            ".webm" to listOf(".mp4"),
            ".mp4" to listOf(".webm"),
        )
    }
}

private class FileAssetSource private constructor(
    private val channel: FileChannel,
    override val length: Long,
) : SeekableAssetSource {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun close() = channel.close()

    companion object {
        fun open(file: java.io.File, expectedLength: Long): FileAssetSource {
            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            )
            try {
                require(channel.size() == expectedLength) { "Indexed file length changed" }
                return FileAssetSource(channel, expectedLength)
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }
    }
}

private class SourceRangeInputStream(
    private val source: SeekableAssetSource,
    start: Long,
    length: Long,
) : InputStream() {
    private var position = start
    private var remaining = length

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        try {
            val read =
                source.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read <= 0) throw java.io.EOFException("Indexed file ended before its recorded length")
            position += read
            remaining -= read
            return read
        } catch (error: Exception) {
            try {
                close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    override fun close() = source.close()
}

private class ResolutionCache(
    private val maxEntries: Int,
) {
    private val entries =
        object : LinkedHashMap<String, GameFileSystem.CachedResolution>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, GameFileSystem.CachedResolution>,
            ): Boolean = size > maxEntries
        }

    @Synchronized
    fun getOrPut(
        key: String,
        value: () -> GameFileSystem.CachedResolution,
    ): GameFileSystem.CachedResolution = entries[key] ?: value().also { entries[key] = it }
}