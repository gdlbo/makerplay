package io.github.gdlbo.makerplay.runtime.webview.internal.input

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PointerContact

/** Coalesces logical input into at most one JavaScript call per scheduled frame. */
internal class RuntimeInputFrameBatcher(
    private val scheduleFrame: (() -> Unit) -> Unit,
    private val dispatch: (String) -> Unit,
) {
    private var pending: String? = null
    private val inputTransitions = ArrayDeque<String>()
    private var lastSubmittedKeyCodes = emptySet<Int>()
    private var lastSubmittedPointers = emptySet<PointerContact>()
    private var lastDispatched: String? = null
    private var scheduled = false
    private var closed = false

    fun submit(snapshot: LogicalInputSnapshot) {
        if (closed) return
        val script = RuntimeInputFrameBridge.script(snapshot)
        if (script == lastDispatched && pending == null) return
        if (
            snapshot.pressedKeyCodes != lastSubmittedKeyCodes ||
            snapshot.pointers != lastSubmittedPointers
        ) {
            require(inputTransitions.size < MAX_QUEUED_INPUT_TRANSITIONS) { "Too many queued input transitions" }
            inputTransitions.addLast(script)
            lastSubmittedKeyCodes = snapshot.pressedKeyCodes
            lastSubmittedPointers = snapshot.pointers
        } else {
            pending = script
        }
        if (!scheduled) {
            scheduled = true
            scheduleFrame(::dispatchFrame)
        }
    }

    fun close() {
        closed = true
        pending = null
        inputTransitions.clear()
    }

    private fun dispatchFrame() {
        scheduled = false
        if (closed) return
        val fromInputQueue = inputTransitions.isNotEmpty()
        val script = if (fromInputQueue) inputTransitions.removeFirst() else pending ?: return
        if (!fromInputQueue) pending = null
        if (script != lastDispatched) {
            lastDispatched = script
            dispatch(script)
        }
        if (inputTransitions.isNotEmpty() || pending != null) {
            scheduled = true
            scheduleFrame(::dispatchFrame)
        }
    }

    private companion object {
        const val MAX_QUEUED_INPUT_TRANSITIONS = 160
    }
}

internal object RuntimeInputFrameBridge {
    private const val MAX_ACTIONS = 16
    private const val MAX_KEY_CODES = 80
    private const val MAX_POINTERS = 16
    private const val MAX_SCRIPT_CHARS = 4096

    internal fun source(template: String): String = template.trimEnd()

    fun install(
        webView: WebView,
        allowedOrigin: String,
        scriptTemplate: String
    ): RuntimeInputFrameBatcher? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        require(allowedOrigin.isNotBlank()) { "Allowed origin must not be blank" }
        val rules = setOf(allowedOrigin)
        WebViewCompat.addDocumentStartJavaScript(webView, source(scriptTemplate), rules)
        return RuntimeInputFrameBatcher(
            scheduleFrame = { callback -> webView.postOnAnimation(callback) },
            dispatch = { script -> webView.evaluateJavascript(script, null) },
        )
    }

    internal fun script(snapshot: LogicalInputSnapshot): String {
        require(snapshot.pressedActions.size <= MAX_ACTIONS) { "Too many input actions" }
        require(snapshot.pressedKeyCodes.size <= MAX_KEY_CODES) { "Too many input key codes" }
        require(snapshot.pointers.size <= MAX_POINTERS) { "Too many input pointers" }
        snapshot.pointers.forEach {
            require(it.sourceId.isNotBlank()) { "Input source id must not be blank" }
            require(it.pointerId >= 0) { "Pointer id must not be negative" }
            require(it.x.isFinite() && it.y.isFinite()) { "Pointer coordinates must be finite" }
        }
        val actions = snapshot.pressedActions
            .sortedBy(GameAction::ordinal)
            .joinToString(",") { "\"${actionName(it)}\"" }
        val pointers = snapshot.pointers
            .sortedWith(compareBy<PointerContact> { it.pointerId }.thenBy(PointerContact::sourceId))
            .joinToString(",") {
                "{\"id\":${quote("${it.sourceId}:${it.pointerId}")},\"x\":${it.x},\"y\":${it.y}}"
            }
        val keys =
            snapshot.pressedKeyCodes.sorted().joinToString(",") { domKeyDescriptor(it).json() }
        val payload = "{\"v\":1,\"actions\":[$actions],\"keys\":[$keys],\"pointers\":[$pointers]}"
        val script = """
            (() => {
              const payload = $payload;
              const apply = globalThis.__makerplayApplyInputSnapshot;
              if (typeof apply === "function") apply(payload);
              else {
                const pending = globalThis.__makerplayPendingInputSnapshots;
                if (Array.isArray(pending)) {
                  if (pending.length >= 160) pending.shift();
                  pending.push(payload);
                } else globalThis.__makerplayPendingInputSnapshots = [payload];
              }
            })();
        """.trimIndent()
        require(script.length <= MAX_SCRIPT_CHARS) { "Input update is too large" }
        return script
    }

    private fun actionName(action: GameAction): String = when (action) {
        GameAction.PAGE_UP -> "pageup"
        GameAction.PAGE_DOWN -> "pagedown"
        GameAction.POINTER_DOWN, GameAction.POINTER_MOVE, GameAction.POINTER_UP -> error("Pointer action is not digital")
        else -> action.name.lowercase()
    }

    private data class DomKeyDescriptor(
        val androidCode: Int,
        val domCode: Int,
        val key: String,
        val code: String,
        val location: Int = 0,
    ) {
        fun json(): String =
            "{\"a\":$androidCode,\"d\":$domCode,\"k\":${quote(key)},\"c\":${quote(code)},\"l\":$location}"
    }

    private fun domKeyDescriptor(androidCode: Int): DomKeyDescriptor = when (androidCode) {
        in 7..16 -> {
            val digit = if (androidCode == 7) 0 else androidCode - 7
            DomKeyDescriptor(androidCode, 48 + digit, digit.toString(), "Digit$digit")
        }

        in 29..54 -> {
            val letter = ('A' + androidCode - 29)
            DomKeyDescriptor(androidCode, letter.code, letter.lowercase(), "Key$letter")
        }

        19 -> DomKeyDescriptor(androidCode, 38, "ArrowUp", "ArrowUp")
        20 -> DomKeyDescriptor(androidCode, 40, "ArrowDown", "ArrowDown")
        21 -> DomKeyDescriptor(androidCode, 37, "ArrowLeft", "ArrowLeft")
        22 -> DomKeyDescriptor(androidCode, 39, "ArrowRight", "ArrowRight")
        55 -> DomKeyDescriptor(androidCode, 188, ",", "Comma")
        56 -> DomKeyDescriptor(androidCode, 190, ".", "Period")
        57 -> DomKeyDescriptor(androidCode, 18, "Alt", "AltLeft", 1)
        58 -> DomKeyDescriptor(androidCode, 18, "Alt", "AltRight", 2)
        59 -> DomKeyDescriptor(androidCode, 16, "Shift", "ShiftLeft", 1)
        60 -> DomKeyDescriptor(androidCode, 16, "Shift", "ShiftRight", 2)
        61 -> DomKeyDescriptor(androidCode, 9, "Tab", "Tab")
        62 -> DomKeyDescriptor(androidCode, 32, " ", "Space")
        66 -> DomKeyDescriptor(androidCode, 13, "Enter", "Enter")
        67 -> DomKeyDescriptor(androidCode, 8, "Backspace", "Backspace")
        68 -> DomKeyDescriptor(androidCode, 192, "`", "Backquote")
        69 -> DomKeyDescriptor(androidCode, 189, "-", "Minus")
        70 -> DomKeyDescriptor(androidCode, 187, "=", "Equal")
        71 -> DomKeyDescriptor(androidCode, 219, "[", "BracketLeft")
        72 -> DomKeyDescriptor(androidCode, 221, "]", "BracketRight")
        73 -> DomKeyDescriptor(androidCode, 220, "\\", "Backslash")
        74 -> DomKeyDescriptor(androidCode, 186, ";", "Semicolon")
        75 -> DomKeyDescriptor(androidCode, 222, "'", "Quote")
        76 -> DomKeyDescriptor(androidCode, 191, "/", "Slash")
        82 -> DomKeyDescriptor(androidCode, 93, "ContextMenu", "ContextMenu")
        111 -> DomKeyDescriptor(androidCode, 27, "Escape", "Escape")
        113 -> DomKeyDescriptor(androidCode, 17, "Control", "ControlLeft", 1)
        114 -> DomKeyDescriptor(androidCode, 17, "Control", "ControlRight", 2)
        115 -> DomKeyDescriptor(androidCode, 20, "CapsLock", "CapsLock")
        else -> DomKeyDescriptor(androidCode, androidCode, "Unidentified", "Unidentified")
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(
                    character
                )
            }
        }
        append('"')
    }
}