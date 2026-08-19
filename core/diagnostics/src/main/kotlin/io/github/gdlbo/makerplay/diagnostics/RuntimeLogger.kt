package io.github.gdlbo.makerplay.diagnostics

import android.util.Log

interface RuntimeLogger {
    fun info(event: String, fields: Map<String, String> = emptyMap())
    fun error(event: String, fields: Map<String, String>) {
        info(event, fields)
    }

    fun error(event: String, throwable: Throwable)
}

class AndroidRuntimeLogger(private val tag: String) : RuntimeLogger {
    override fun info(event: String, fields: Map<String, String>) {
        Log.i(tag, "$event ${sanitize(fields)}")
    }

    override fun error(event: String, throwable: Throwable) {
        Log.e(tag, "$event failure=${throwable.javaClass.name}")
    }

    override fun error(event: String, fields: Map<String, String>) {
        Log.e(tag, "$event ${sanitize(fields)}")
    }

    private fun sanitize(fields: Map<String, String>): Map<String, String> =
        fields.mapValues { (key, value) ->
            if (REDACTED_KEYS.any { key.contains(it, ignoreCase = true) }) "[REDACTED]" else value
        }

    private companion object {
        val REDACTED_KEYS = setOf("key", "save", "path", "secret", "token")
    }
}