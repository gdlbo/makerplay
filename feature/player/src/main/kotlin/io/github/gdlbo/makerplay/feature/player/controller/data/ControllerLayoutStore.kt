package io.github.gdlbo.makerplay.feature.player.controller.data

import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class ControllerLayoutStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(defaults: ControllerLayouts = ControllerLayouts()): ControllerLayouts {
        if (!file.isFile) return defaults
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            ControllerLayoutJsonCodec.decode(root, defaults)
        }.getOrDefault(defaults)
    }

    fun save(layouts: ControllerLayouts) {
        ControllerLayoutJsonCodec.validate(layouts)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(
                json.encodeToString(
                    JsonElement.serializer(),
                    ControllerLayoutJsonCodec.encode(layouts)
                )
                    .toByteArray(Charsets.UTF_8),
            )
            output.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}