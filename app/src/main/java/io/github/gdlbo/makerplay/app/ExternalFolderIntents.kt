package io.github.gdlbo.makerplay.app

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

internal const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
private const val TAG = "MakerPlay"

internal fun storageDocumentId(
    folderPath: String,
    primaryRoot: String?,
    volumeRoots: List<Pair<String, String>> = emptyList(),
): String? {
    fun normalize(path: String) = path.replace('\\', '/').trimEnd('/')
    val folder = normalize(folderPath)
    if (!primaryRoot.isNullOrBlank()) {
        val root = normalize(primaryRoot)
        when {
            folder.equals(root, ignoreCase = true) -> return "primary:"
            folder.startsWith("$root/", ignoreCase = true) ->
                return "primary:" + folder.substring(root.length + 1)
        }
    }
    for ((uuid, rootPath) in volumeRoots) {
        if (uuid.isBlank()) continue
        val root = normalize(rootPath)
        when {
            folder.equals(root, ignoreCase = true) -> return "$uuid:"
            folder.startsWith("$root/", ignoreCase = true) ->
                return "$uuid:" + folder.substring(root.length + 1)
        }
    }
    return null
}

internal fun openFolderExternally(context: Context, folder: File): Boolean {
    if (!folder.isDirectory) {
        Log.e(TAG, "native.folder.open rejected path=${folder.path} isDirectory=false exists=${folder.exists()}")
        return false
    }
    val canonical = runCatching { folder.canonicalFile }.getOrDefault(folder)
    val primaryRoot = runCatching {
        Environment.getExternalStorageDirectory()?.canonicalFile?.path
    }.getOrNull()
    val volumes = storageVolumeRoots(context)
    val documentId = storageDocumentId(canonical.path, primaryRoot, volumes)
    Log.i(
        TAG,
        "native.folder.open path=${canonical.path} primaryRoot=$primaryRoot " +
            "volumes=${volumes.joinToString { it.first }} documentId=$documentId",
    )
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    val uris = buildList {
        if (documentId != null) {
            add(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, documentId))
            add(DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, documentId))
        }
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.export", canonical)
        }.onFailure { error ->
            Log.e(TAG, "native.folder.open fileprovider ${error.javaClass.simpleName}: ${error.message}")
        }.getOrNull()?.let(::add)
    }
    if (uris.isEmpty()) {
        Log.e(TAG, "native.folder.open no-uri path=${canonical.path}")
        return false
    }
    val mimeTypes = listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder")
    val packages = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.apps.nbu.files",
    )
    val attempts = buildList {
        for (uri in uris) {
            for (mime in mimeTypes) {
                add(folderViewIntent(uri, mime, flags))
                for (pkg in packages) {
                    add(folderViewIntent(uri, mime, flags).setPackage(pkg))
                }
            }
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(flags)
                },
            )
        }
    }
    if (attempts.any { startFolderIntent(context, it) }) return true
    val chooser = Intent.createChooser(
        folderViewIntent(uris.first(), DocumentsContract.Document.MIME_TYPE_DIR, flags),
        context.getString(io.github.gdlbo.makerplay.feature.library.R.string.open_in_file_manager),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return startFolderIntent(context, chooser)
}

private fun folderViewIntent(uri: android.net.Uri, mimeType: String, flags: Int): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(flags)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

private fun startFolderIntent(context: Context, intent: Intent): Boolean {
    return try {
        Log.i(
            TAG,
            "native.folder.open attempt action=${intent.action} data=${intent.data} " +
                "type=${intent.type} pkg=${intent.`package`}",
        )
        context.startActivity(intent)
        Log.i(TAG, "native.folder.open started")
        true
    } catch (error: Exception) {
        Log.e(TAG, "native.folder.open failed ${error.javaClass.simpleName}: ${error.message}")
        false
    }
}

private fun storageVolumeRoots(context: Context): List<Pair<String, String>> {
    val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return manager.storageVolumes.mapNotNull { volume ->
        val uuid = volume.uuid ?: return@mapNotNull null
        val directory = volume.directory ?: return@mapNotNull null
        val path = runCatching { directory.canonicalPath }.getOrNull() ?: return@mapNotNull null
        uuid to path
    }
}
