package io.github.gdlbo.makerplay.runtime.webview.internal.bridge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCatalogEntry
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatInventoryKind
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.CheatResource
import io.github.gdlbo.makerplay.runtime.api.RecoveryTarget
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.renderRuntimeScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal object RuntimeCheatBridge {
    private const val MAX_SCRIPT_CHARS = 4096
    private const val CATALOG_OBJECT_NAME = "makerplayCheatCatalog"
    private const val MAX_CATALOG_ENTRIES = 2000
    private const val MAX_CATALOG_MESSAGE_CHARS = 512_000

    internal class Session internal constructor(internal val token: String)

    internal fun source(template: String, session: Session): String = renderRuntimeScript(
        template,
        "__MAKERPLAY_SESSION_TOKEN__" to JsonPrimitive(session.token).toString(),
    )

    fun install(
        webView: WebView,
        allowedOrigin: String,
        scriptTemplate: String,
        onCatalogChanged: (CheatCatalog) -> Unit = {},
    ): Session? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        require(Uri.parse(allowedOrigin).scheme.equals("https", ignoreCase = true)) {
            "Cheat bridge requires an HTTPS origin"
        }
        val session = Session(UUID.randomUUID().toString())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                CATALOG_OBJECT_NAME,
                setOf(allowedOrigin),
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame ||
                    !sourceOrigin.sameOrigin(Uri.parse(allowedOrigin)) ||
                    message.type != WebMessageCompat.TYPE_STRING
                ) return@addWebMessageListener
                parseCatalog(message.data.orEmpty(), session.token)?.let(onCatalogChanged)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            source(scriptTemplate, session),
            setOf(allowedOrigin),
        )
        return session
    }

    fun uninstall(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, CATALOG_OBJECT_NAME)
        }
    }

    fun apply(webView: WebView, session: Session, command: CheatCommand) {
        webView.evaluateJavascript(script(session, command), null)
    }

    internal fun script(session: Session, command: CheatCommand): String {
        val payload = when (val operation = command.operation) {
            is CheatOperation.SetFlags -> {
                val playerSpeedMultiplier = operation.playerSpeedMultiplier
                    .takeIf { it.isFinite() }
                    ?.coerceIn(1.0, 8.0)
                    ?: 1.0
                "\"op\":\"flags\",\"godMode\":${operation.godMode},\"infiniteHp\":${operation.infiniteHp},\"infiniteMp\":${operation.infiniteMp},\"playerSpeedMultiplier\":$playerSpeedMultiplier,\"noClip\":${operation.noClip}"
            }

            is CheatOperation.AddGold -> "\"op\":\"gold\",\"amount\":${
                operation.amount.coerceIn(
                    -1_000_000_000,
                    1_000_000_000
                )
            }"

            is CheatOperation.AddExperience ->
                "\"op\":\"experience\",\"actorId\":${
                    operation.actorId.coerceIn(
                        1,
                        9999
                    )
                },\"amount\":${operation.amount.coerceIn(-1_000_000_000, 1_000_000_000)}"

            is CheatOperation.AddParameter ->
                "\"op\":\"parameter\",\"actorId\":${
                    operation.actorId.coerceIn(
                        1,
                        9999
                    )
                },\"parameterId\":${
                    operation.parameterId.coerceIn(
                        0,
                        7
                    )
                },\"amount\":${operation.amount.coerceIn(-1_000_000_000, 1_000_000_000)}"

            is CheatOperation.AddInventory ->
                "\"op\":\"inventory\",\"kind\":\"${
                    when (operation.kind) {
                        CheatInventoryKind.ITEM -> "item"
                        CheatInventoryKind.WEAPON -> "weapon"
                        CheatInventoryKind.ARMOR -> "armor"
                    }
                }\",\"id\":${operation.id.coerceIn(1, 9999)},\"amount\":${
                    operation.amount.coerceIn(
                        -9999,
                        9999
                    )
                }"

            is CheatOperation.Teleport ->
                "\"op\":\"teleport\",\"mapId\":${
                    operation.mapId.coerceIn(
                        1,
                        9999
                    )
                },\"x\":${operation.x.coerceIn(0, 9999)},\"y\":${operation.y.coerceIn(0, 9999)}"

            is CheatOperation.SavePosition ->
                "\"op\":\"savePosition\",\"slot\":${operation.slot.coerceIn(0, 2)}"

            is CheatOperation.RecallPosition ->
                "\"op\":\"recallPosition\",\"slot\":${operation.slot.coerceIn(0, 2)}"

            is CheatOperation.SetVariable ->
                "\"op\":\"variable\",\"id\":${
                    operation.id.coerceIn(
                        1,
                        9999
                    )
                },\"value\":${operation.value.coerceIn(-1_000_000_000.0, 1_000_000_000.0)}"

            is CheatOperation.SetSwitch ->
                "\"op\":\"switch\",\"id\":${
                    operation.id.coerceIn(
                        1,
                        9999
                    )
                },\"enabled\":${operation.enabled}"

            is CheatOperation.Recover -> "\"op\":\"recover\",\"target\":\"${
                when (operation.target) {
                    RecoveryTarget.LEADER -> "leader"
                    RecoveryTarget.PARTY -> "party"
                    RecoveryTarget.ENEMIES -> "enemies"
                    RecoveryTarget.ALL -> "all"
                }
            }\""

            is CheatOperation.RefillResource -> "\"op\":\"refill\",\"target\":\"${
                operation.target.payloadName()
            }\",\"resource\":\"${
                when (operation.resource) {
                    CheatResource.MP -> "mp"
                    CheatResource.TP -> "tp"
                }
            }\""

            is CheatOperation.ClearStates ->
                "\"op\":\"clearStates\",\"target\":\"${operation.target.payloadName()}\""

            is CheatOperation.SetHpToOne -> "\"op\":\"hpOne\",\"target\":\"${
                when (operation.target) {
                    RecoveryTarget.LEADER -> "leader"
                    RecoveryTarget.PARTY -> "party"
                    RecoveryTarget.ENEMIES -> "enemies"
                    RecoveryTarget.ALL -> "all"
                }
            }\""

            is CheatOperation.Defeat -> "\"op\":\"defeat\",\"target\":\"${
                when (operation.target) {
                    RecoveryTarget.LEADER -> "leader"
                    RecoveryTarget.PARTY -> "party"
                    RecoveryTarget.ENEMIES -> "enemies"
                    RecoveryTarget.ALL -> "all"
                }
            }\""

            CheatOperation.RefreshCatalog -> "\"op\":\"catalog\""
        }
        val script = "globalThis.__makerplayApplyCheat(\"${session.token}\",{\"v\":1,$payload});"
        require(script.length <= MAX_SCRIPT_CHARS)
        return script
    }

    internal fun parseCatalog(message: String, expectedToken: String): CheatCatalog? {
        if (message.length > MAX_CATALOG_MESSAGE_CHARS) return null
        return runCatching {
            val root = Json.parseToJsonElement(message).jsonObject
            if (root["v"]?.jsonPrimitive?.intOrNull != 1 ||
                root["token"]?.jsonPrimitive?.content != expectedToken
            ) return null

            fun parseEntries(key: String): List<CheatCatalogEntry> =
                root[key]?.jsonArray
                    ?.take(MAX_CATALOG_ENTRIES)
                    ?.mapNotNull { element ->
                        val item = element.jsonObject
                        val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val name = item["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (id !in 1..9999 || name.isEmpty()) return@mapNotNull null
                        CheatCatalogEntry(
                            id = id,
                            name = name.take(128),
                            value = item["value"]?.jsonPrimitive?.content.orEmpty().take(160),
                        )
                    }
                    .orEmpty()

            CheatCatalog(
                variables = parseEntries("variables"),
                switches = parseEntries("switches"),
            )
        }.getOrNull()
    }

    private fun Uri.sameOrigin(expected: Uri): Boolean =
        scheme.equals(expected.scheme, ignoreCase = true) &&
                host.equals(expected.host, ignoreCase = true) &&
                effectivePort() == expected.effectivePort()

    private fun Uri.effectivePort(): Int = when {
        port != -1 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }

    private fun RecoveryTarget.payloadName(): String = when (this) {
        RecoveryTarget.LEADER -> "leader"
        RecoveryTarget.PARTY -> "party"
        RecoveryTarget.ENEMIES -> "enemies"
        RecoveryTarget.ALL -> "all"
    }
}