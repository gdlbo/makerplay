package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static retention audit for the WebView runtime module.
 *
 * The WebView runtime keeps per-view state in composition-scoped maps. Object, companion-object,
 * and top-level `val`/`var` properties are process-lifetime roots, so none of them may hold a
 * `WebView`, `Context`, or `Activity` (or a `Context`-typed/config holder) after teardown.
 * Add `// retention-audit: allow` on a line only with a documented reason.
 */
class RuntimeRetentionAuditTest {
    @Test
    fun `module has no static WebView Context or Activity property`() {
        val sourceRoot = sourceRoot()
        val findings = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> scanSource(file.path.replace('\\', '/'), file.readText()) }
            .toList()

        assertTrue(
            "Static WebView/Context/Activity retention found:\n" + findings.joinToString("\n"),
            findings.isEmpty(),
        )
    }

    @Test
    fun `audit detects a planted static retention`() {
        val planted = """
            internal object Planted {
                private var leaked: android.webkit.WebView? = null
            }
        """.trimIndent()

        val findings = scanSource("Planted.kt", planted)

        assertTrue("planted static retention was not detected", findings.isNotEmpty())
    }

    private fun sourceRoot(): File = listOf(
        File("src/main/kotlin"),
        File("runtime/webview/src/main/kotlin"),
    ).firstOrNull(File::isDirectory)
        ?: error("runtime webview sources not found from ${File(".").absolutePath}")

    internal companion object {
        private val FORBIDDEN_TYPES = Regex("""\b(?:WebView|Context|Activity)\b""")
        private val OBJECT_DECLARATION = Regex(
            """(?m)^\s*(?:(?:private|internal|public|protected|abstract|open|sealed|data)\s+)?(?:companion\s+object|object\s+\w+)""",
        )
        private val STATIC_PROPERTY = Regex(
            """^\s*(?:(?:private|internal|public|protected|override|lateinit|const)\s+)*(?:val|var)\s+\w+""",
        )

        internal fun scanSource(path: String, source: String): List<String> {
            val findings = mutableListOf<String>()

            fun check(line: String, lineNumber: Int) {
                if (line.contains("retention-audit: allow")) return
                if (STATIC_PROPERTY.containsMatchIn(line) && FORBIDDEN_TYPES.containsMatchIn(line)) {
                    findings += "$path:$lineNumber: ${line.trim()}"
                }
            }

            var searchFrom = 0
            while (true) {
                val declaration = OBJECT_DECLARATION.find(source, searchFrom) ?: break
                val lineEnd = source.indexOf('\n', declaration.range.last).let {
                    if (it < 0) source.length else it
                }
                val braceStart = source.indexOf('{', declaration.range.last)
                if (braceStart < 0 || braceStart > lineEnd) {
                    // Bodyless object declaration (for example `data object None : Type`).
                    searchFrom = declaration.range.last + 1
                    continue
                }
                val braceEnd = matchingBrace(source, braceStart)
                if (braceEnd < 0) break
                val bodyStart = braceStart + 1
                source.substring(bodyStart, braceEnd).lineSequence().forEachIndexed { index, line ->
                    check(line, lineNumberOf(source, bodyStart) + index)
                }
                searchFrom = braceEnd + 1
            }

            // Top-level properties are process-lifetime roots as well.
            source.lineSequence().forEachIndexed { index, line ->
                if (line.isNotEmpty() && !line.first().isWhitespace()) {
                    check(line, index + 1)
                }
            }
            return findings
        }

        private fun matchingBrace(source: String, openIndex: Int): Int {
            var depth = 0
            for (index in openIndex until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            return -1
        }

        private fun lineNumberOf(source: String, offset: Int): Int =
            source.take(offset).count { it == '\n' } + 1
    }
}
