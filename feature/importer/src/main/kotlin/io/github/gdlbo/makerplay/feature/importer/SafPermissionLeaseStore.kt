package io.github.gdlbo.makerplay.feature.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import java.util.UUID

internal class SafPermissionLeaseStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun acquire(workId: UUID, uri: Uri) {
        synchronized(PROCESS_LOCK) {
            val alreadyGranted = hasReadPermission(uri)
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            if (!preferences.edit().putString(workId.toString(), uri.toString()).commit()) {
                if (!alreadyGranted) runCatching { release(uri) }
                error("Unable to retain the import permission lease")
            }
        }
    }

    fun complete(workId: UUID) {
        synchronized(PROCESS_LOCK) {
            val key = workId.toString()
            val uriValue = preferences.getString(key, null) ?: return
            val hasAnotherLease = preferences.all.any { (otherKey, value) ->
                otherKey != key && value == uriValue
            }
            if (hasAnotherLease) {
                preferences.edit(commit = true) { remove(key) }
                return
            }
            val uri = Uri.parse(uriValue)
            val released = runCatching { release(uri) }.isSuccess || !hasReadPermission(uri)
            if (released) preferences.edit(commit = true) { remove(key) }
        }
    }

    fun reconcile(activeWorkIds: Set<String>) {
        synchronized(PROCESS_LOCK) {
            preferences.all.keys.filterNot(activeWorkIds::contains).forEach { workId ->
                runCatching { UUID.fromString(workId) }.getOrNull()?.let(::complete)
            }
        }
    }

    private fun release(uri: Uri) {
        appContext.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun hasReadPermission(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private companion object {
        const val PREFERENCES = "game_import_uri_leases"
        val PROCESS_LOCK = Any()
    }
}