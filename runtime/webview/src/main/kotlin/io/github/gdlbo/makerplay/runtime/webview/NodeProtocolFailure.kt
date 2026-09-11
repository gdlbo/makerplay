package io.github.gdlbo.makerplay.runtime.webview

/**
 * A failure the Node compatibility protocol reports back to JavaScript with a stable reason code.
 *
 * The reason is translated into an errno-style `code` by the runtime script
 * (`missing` → ENOENT, `notdir` → ENOTDIR, `zdata` → Z_DATA_ERROR, ...).
 */
internal class ProtocolFailure(val reason: String) : RuntimeException(reason)
