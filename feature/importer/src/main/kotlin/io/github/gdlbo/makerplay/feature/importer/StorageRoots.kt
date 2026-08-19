package io.github.gdlbo.makerplay.feature.importer

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object StorageRoots {
    fun available(context: Context): List<File> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .asSequence()
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { it.directory }
            .filter { it.isDirectory && it.canRead() }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
            .toList()
}