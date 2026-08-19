package io.github.gdlbo.makerplay.feature.importer

import java.io.ByteArrayInputStream

fun Map<String, ByteArray>.asImportSource(): ImportSource = ImportSource {
    map { (path, bytes) ->
        ImportEntry(
            relativePath = path,
            size = bytes.size.toLong(),
            open = { ByteArrayInputStream(bytes.copyOf()) },
        )
    }
}