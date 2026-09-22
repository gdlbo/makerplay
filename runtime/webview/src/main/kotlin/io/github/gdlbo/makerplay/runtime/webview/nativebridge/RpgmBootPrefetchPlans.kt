package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Boot dependencies only; unrelated maps, pictures and audio remain demand-loaded. */
internal object RpgmBootPrefetchPlans {
    fun plaintextHotPaths(fileSystem: GameFileSystem): List<String> {
        val paths = linkedSetOf(
            "data/System.json", "data/CommonEvents.json", "data/MapInfos.json",
            "data/Actors.json", "data/Classes.json", "data/Skills.json", "data/Items.json",
            "data/Weapons.json", "data/Armors.json", "data/Enemies.json", "data/Troops.json",
            "data/States.json", "data/Animations.json", "data/Tilesets.json",
        )
        val startMap = system(fileSystem)?.get("startMapId")?.jsonPrimitive?.intOrNull ?: 1
        if (startMap > 0) paths += "data/Map${startMap.toString().padStart(3, '0')}.json"
        return paths.filter { fileSystem.resolve(it) != null }
    }

    fun mediaLogicalPaths(fileSystem: GameFileSystem): List<String> {
        val paths = linkedSetOf("img/system/Window.png", "img/system/IconSet.png")
        system(fileSystem)?.let { system ->
            fun addImage(field: String, directory: String) {
                system[field]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                    paths += "$directory/$it.png"
                }
            }
            addImage("title1Name", "img/titles1")
            addImage("title2Name", "img/titles2")
            (system["titleBgm"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)?.let { paths += "audio/bgm/$it.ogg" }
        }
        return paths.filter { fileSystem.resolve(it) != null }
    }

    fun encryptedLogicalPaths(fileSystem: GameFileSystem): List<String> =
        mediaLogicalPaths(fileSystem).filter { fileSystem.resolve(it)?.codecId != null }

    fun plaintextMediaPaths(fileSystem: GameFileSystem): List<String> =
        mediaLogicalPaths(fileSystem).filter { fileSystem.resolve(it)?.codecId == null }

    fun readEncryptionKey(fileSystem: GameFileSystem): String? =
        system(fileSystem)?.get("encryptionKey")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length == 32 && it.all { char -> char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' } }

    private fun system(fileSystem: GameFileSystem): JsonObject? = runCatching {
        val opened = fileSystem.open("data/System.json") as? VfsOpenResult.Found ?: return null
        opened.stream.use { stream ->
            if (opened.contentLength > 1024 * 1024) return null
            Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
        }
    }.getOrNull()
}
