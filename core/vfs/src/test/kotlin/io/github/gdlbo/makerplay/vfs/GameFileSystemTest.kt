package io.github.gdlbo.makerplay.vfs

import io.github.gdlbo.makerplay.codec.AssetCodecRegistry
import io.github.gdlbo.makerplay.codec.RpgMakerAssetCodec
import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameFileSystemTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("makerplay-vfs-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun exactAndCaseFoldedPlainPathsBeatEncryptedAlternatives() {
        write("img/Icon.png", "plain")
        write("img/icon.png_", "mz")
        write("img/icon.rpgmvp", "mv")
        val vfs = mount()

        assertEquals(ResolutionKind.EXACT, vfs.resolve("img/Icon.png")?.kind)
        assertEquals("img/Icon.png", vfs.resolve("img/Icon.png")?.storedPath?.value)
        assertEquals(ResolutionKind.CASE_FOLDED, vfs.resolve("IMG/ICON.PNG")?.kind)
        assertEquals("img/Icon.png", vfs.resolve("IMG/ICON.PNG")?.storedPath?.value)
    }

    @Test
    fun encryptedAndCustomAliasesFollowDocumentedOrder() {
        write("img/Hero.png_", "mz")
        write("img/Hero.rpgmvp", "mv")
        write("custom/hero.bin", "custom")
        write("audio/Theme.rpgmvo", "audio")
        write("effects/Burst.efkefc_", "effect")
        write("data/MvOnly.rpgmvp", "mv-custom-order")
        val aliases = listOf(
            AssetAlias(GamePath.parse("img/Hero.png"), GamePath.parse("custom/hero.bin"), "custom"),
            AssetAlias(
                GamePath.parse("data/Custom.dat"),
                GamePath.parse("custom/hero.bin"),
                "custom"
            ),
            AssetAlias(
                GamePath.parse("data/MvOnly.png"),
                GamePath.parse("custom/hero.bin"),
                "custom"
            ),
        )
        val vfs = GameFileSystem(GameFileIndex.build(root), aliases)

        assertEquals(ResolutionKind.MZ_ENCRYPTED, vfs.resolve("img/Hero.png")?.kind)
        assertEquals("img/Hero.png_", vfs.resolve("img/Hero.png")?.storedPath?.value)
        assertEquals(ResolutionKind.MV_ENCRYPTED, vfs.resolve("audio/theme.ogg")?.kind)
        assertEquals("audio/Theme.rpgmvo", vfs.resolve("audio/theme.ogg")?.storedPath?.value)
        assertEquals(ResolutionKind.CUSTOM_ALIAS, vfs.resolve("data/Custom.dat")?.kind)
        assertEquals(ResolutionKind.CUSTOM_ALIAS, vfs.resolve("DATA/CUSTOM.DAT")?.kind)
        assertEquals(ResolutionKind.MZ_ENCRYPTED, vfs.resolve("effects/Burst.efkefc")?.kind)
        assertEquals(ResolutionKind.MV_ENCRYPTED, vfs.resolve("data/MvOnly.png")?.kind)
        assertNull(vfs.resolve("missing.file"))
        assertNull(vfs.resolve("../index.html"))
    }

    @Test
    fun caseFoldedAliasCollisionsAreRejected() {
        write("custom/hero.bin", "custom")
        val aliases = listOf(
            AssetAlias(GamePath.parse("data/Custom.dat"), GamePath.parse("custom/hero.bin"), "one"),
            AssetAlias(GamePath.parse("DATA/CUSTOM.DAT"), GamePath.parse("custom/hero.bin"), "two"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            GameFileSystem(GameFileIndex.build(root), aliases)
        }
    }

    @Test
    fun plainOpenSupportsMimeValidatorsAndBoundedRanges() {
        write("audio/theme.ogg", "0123456789")
        val vfs = mount()

        val full = vfs.open("audio/theme.ogg") as VfsOpenResult.Found
        full.stream.use { assertArrayEquals("0123456789".toByteArray(), it.readBytes()) }
        assertEquals("audio/ogg", full.asset.mimeType)
        assertTrue(full.asset.entityTag.startsWith("W/\""))
        assertEquals(10L, full.contentLength)
        assertNull(full.contentRange)

        val partial = vfs.open("audio/theme.ogg", ByteRange(2, 5)) as VfsOpenResult.Found
        partial.stream.use { assertArrayEquals("2345".toByteArray(), it.readBytes()) }
        assertEquals(4L, partial.contentLength)
        assertEquals("bytes 2-5/10", partial.contentRange)

        assertEquals(
            VfsOpenResult.RangeNotSatisfiable(10),
            vfs.open("audio/theme.ogg", ByteRange(10)),
        )
    }

    @Test
    fun engineSelectedMediaFormatsFallBackToAvailableVariants() {
        write("audio/bgm/Theme.ogg", "OggS-audio")
        write("audio/se/Confirm.m4a", "m4a-audio")
        write("movies/Intro.mp4", "mp4-video")
        write("movies/Ending.webm", "webm-video")
        val vfs = mount()

        val audio = vfs.open("audio/bgm/Theme.m4a") as VfsOpenResult.Found
        val reverseAudio = vfs.open("audio/se/Confirm.ogg") as VfsOpenResult.Found
        val video = vfs.open("movies/Intro.webm", ByteRange(4, 8)) as VfsOpenResult.Found
        val reverseVideo = vfs.open("movies/Ending.mp4") as VfsOpenResult.Found

        audio.stream.use { assertArrayEquals("OggS-audio".toByteArray(), it.readBytes()) }
        reverseAudio.stream.close()
        video.stream.use { assertArrayEquals("video".toByteArray(), it.readBytes()) }
        reverseVideo.stream.close()
        assertEquals(ResolutionKind.MEDIA_FORMAT_FALLBACK, audio.asset.kind)
        assertEquals("audio/bgm/Theme.ogg", audio.asset.storedPath.value)
        assertEquals("audio/ogg", audio.asset.mimeType)
        assertEquals("audio/mp4", reverseAudio.asset.mimeType)
        assertEquals("video/mp4", video.asset.mimeType)
        assertEquals("bytes 4-8/9", video.contentRange)
        assertEquals("video/webm", reverseVideo.asset.mimeType)
    }

    @Test
    fun mediaFallbackSupportsEncryptedAlternativesButStaysInMediaDirectories() {
        write("audio/bgm/Theme.ogg_", "encrypted")
        write("other/Theme.ogg", "unrelated")
        val vfs = GameFileSystem(
            GameFileIndex.build(root),
            codecs = AssetCodecRegistry.of(RpgMakerAssetCodec.fromHexKey("00".repeat(16))),
        )

        val encrypted = vfs.resolve("audio/bgm/Theme.m4a")

        assertEquals(ResolutionKind.MEDIA_FORMAT_FALLBACK, encrypted?.kind)
        assertEquals("audio/bgm/Theme.ogg_", encrypted?.storedPath?.value)
        assertEquals("audio/ogg", encrypted?.mimeType)
        assertNull(vfs.resolve("other/Theme.m4a"))
    }

    @Test
    fun recoversOnlyUniquePathsWithOneMissingDirectorySeparator() {
        write("img/system/Wave_B.rpgmvp", "wave")
        write("img/pictures/map_move.rpgmvp", "map")
        val vfs = mount()

        val wave = vfs.resolve("img/systemWave_B.rpgmvp")
        val map = vfs.resolve("img/picturesmap_move.rpgmvp")

        assertEquals(ResolutionKind.MISSING_SEPARATOR_FALLBACK, wave?.kind)
        assertEquals("img/system/Wave_B.rpgmvp", wave?.storedPath?.value)
        assertEquals(ResolutionKind.MISSING_SEPARATOR_FALLBACK, map?.kind)
        assertEquals("img/pictures/map_move.rpgmvp", map?.storedPath?.value)
    }

    @Test
    fun encryptedAssetRequiresCodecInsteadOfServingStoredBytes() {
        write("img/Hero.png_", "encrypted")
        val result = mount().open("img/Hero.png")

        assertTrue(result is VfsOpenResult.RequiresCodec)
        assertEquals("image/png", (result as VfsOpenResult.RequiresCodec).asset.mimeType)
    }

    @Test
    fun encryptedMvAndMzFixturesSupportFullAndRangedReads() {
        RpgMakerFixtureGenerator.standardEncryptedAssets().forEach { fixture ->
            val stripWww = fixture.files.keys.any { it.startsWith("www/") }
            root.deleteRecursively()
            root.mkdirs()
            fixture.files.forEach { (path, bytes) ->
                write(if (stripWww) path.removePrefix("www/") else path, bytes)
            }
            val codec = RpgMakerAssetCodec.fromHexKey(fixture.encryptionKey)
            val vfs = GameFileSystem(
                GameFileIndex.build(root),
                codecs = AssetCodecRegistry.of(codec),
            )

            val full = vfs.open(fixture.logicalAssetPath) as VfsOpenResult.Found
            full.stream.use { assertArrayEquals(fixture.plaintext, it.readBytes()) }
            assertEquals(fixture.plaintext.size.toLong(), full.contentLength)
            assertEquals(fixture.expectedMimeType, full.asset.mimeType)
            assertTrue(!full.asset.entityTag.contains(fixture.encryptionKey))

            val partial =
                vfs.open(fixture.logicalAssetPath, ByteRange(12, 20)) as VfsOpenResult.Found
            partial.stream.use {
                assertArrayEquals(
                    fixture.plaintext.copyOfRange(12, 21),
                    it.readBytes()
                )
            }
            assertEquals("bytes 12-20/${fixture.plaintext.size}", partial.contentRange)

            assertEquals(
                VfsOpenResult.RangeNotSatisfiable(fixture.plaintext.size.toLong()),
                vfs.open(fixture.logicalAssetPath, ByteRange(fixture.plaintext.size.toLong())),
            )
        }
    }

    @Test
    fun invalidEncryptedHeaderFailsClosed() {
        write("img/Broken.png_", ByteArray(32))
        val vfs = GameFileSystem(
            GameFileIndex.build(root),
            codecs = AssetCodecRegistry.of(RpgMakerAssetCodec.fromHexKey("00".repeat(16))),
        )

        assertTrue(vfs.open("img/Broken.png") is VfsOpenResult.InvalidAsset)
        assertTrue(vfs.open("img/Broken.png", ByteRange(16)) is VfsOpenResult.InvalidAsset)
    }

    @Test
    fun persistedIndexReopensWithoutDiscoveringNewFiles() {
        val original = write("index.html", "original")
        GameFileIndex.build(root).write()
        write("late.js", "late")

        val reopened = GameFileIndex.loadOrBuild(root)

        assertEquals(original.length(), reopened.exact(GamePath.parse("index.html"))?.size)
        assertNull(reopened.exact(GamePath.parse("late.js")))
    }

    @Test
    fun corruptIndexRebuildsAndReplacesIt() {
        write("index.html", "game")
        File(root, GameFileIndex.INDEX_FILE).writeText("corrupt")

        val rebuilt = GameFileIndex.loadOrBuild(root)

        assertEquals("index.html", rebuilt.entries.single().path.value)
        assertTrue(File(root, GameFileIndex.INDEX_FILE).length() > "corrupt".length)
    }

    @Test
    fun caseFoldCollisionIsRejected() {
        write("img/Icon.png", "one")
        write("img/icon.png", "two")
        assumeTrue(File(root, "img").listFiles().orEmpty().size == 2)

        assertThrows(IllegalArgumentException::class.java) { GameFileIndex.build(root) }
    }

    @Test
    fun indexAndMetadataFilesAreNeverServed() {
        write("index.html", "game")
        write(".makerplay.properties", "private")
        write(".MAKERPLAY.PROPERTIES", "private-case-variant")
        GameFileIndex.build(root).write()

        val reopened = GameFileIndex.loadOrBuild(root)

        assertNull(reopened.exact(GamePath.parse(".makerplay.properties")))
        assertNull(reopened.exact(GamePath.parse(".MAKERPLAY.PROPERTIES")))
        assertNull(reopened.exact(GamePath.parse(GameFileIndex.INDEX_FILE)))
    }

    @Test
    fun exposedEntriesCannotMutateTheIndex() {
        write("index.html", "game")
        val index = GameFileIndex.build(root)

        assertThrows(UnsupportedOperationException::class.java) {
            (index.entries as MutableList).clear()
        }
        assertEquals("index.html", index.entries.single().path.value)
    }

    @Test
    fun directoryListingsReuseIndexedChildrenAndRemainCaseInsensitive() {
        write("img/characters/Hero.rpgmvp", "hero")
        write("img/characters/Villain.rpgmvp", "villain")
        write("img/system/Icon.rpgmvp", "icon")
        val vfs = mount()

        assertEquals(listOf("img"), vfs.list(""))
        assertEquals(listOf("characters", "system"), vfs.list("IMG"))
        assertEquals(
            listOf("Hero.rpgmvp", "Villain.rpgmvp"),
            vfs.list("IMG/CHARACTERS"),
        )
        assertNull(vfs.list("img/missing"))
    }

    @Test
    fun fileReplacedBySymlinkFailsClosed() {
        val indexed = write("data/Actor.json", "original")
        val replacement = write("replacement.json", "replacement")
        val vfs = mount()
        Files.delete(indexed.toPath())
        try {
            Files.createSymbolicLink(indexed.toPath(), replacement.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertEquals(VfsOpenResult.Missing, vfs.open("data/Actor.json"))
    }

    private fun mount(): GameFileSystem = GameFileSystem(GameFileIndex.build(root))

    private fun write(path: String, content: String): File = File(root, path).apply {
        parentFile?.mkdirs()
        writeText(content)
    }

    private fun write(path: String, content: ByteArray): File = File(root, path).apply {
        parentFile?.mkdirs()
        writeBytes(content)
    }
}
