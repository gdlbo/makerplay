package io.github.gdlbo.makerplay.feature.player.controller.data

import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import io.github.gdlbo.makerplay.feature.player.controller.model.PreviousDefaultGamepadProfile
import io.github.gdlbo.makerplay.feature.player.controller.model.PreviousDefaultKeyboardProfile
import io.github.gdlbo.makerplay.feature.player.controller.model.PreviousLargeGamepadProfile
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlBehavior
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs

internal object ControllerLayoutJsonCodec {
    fun validate(layouts: ControllerLayouts) {
        VirtualControllerProfileValidator.validate(layouts.gamepad)
        VirtualControllerProfileValidator.validate(layouts.keyboard)
    }

    fun encode(layouts: ControllerLayouts): JsonObject = buildJsonObject {
        put("version", 7)
        put("mode", layouts.mode.name)
        put("orientation", 6)
        put("profiles", buildJsonObject {
            put("gamepad", encodeProfile(layouts.gamepad))
            put("keyboard", encodeProfile(layouts.keyboard))
        })
        put(
            "buttons",
            encodeControls(layouts.gamepad.controls.filterNot { it.action in DIRECTION_ACTIONS })
        )
        put("keyboardButtons", encodeControls(layouts.keyboard.controls))
        put("joystick", buildJsonObject {
            put("centerX", .145f)
            put("centerY", .765f)
            put("outerCircleRadius", .14f)
            put("knobRadius", .05f)
            put("alpha", 72)
            put("color", 0xFFD9DDE3.toInt())
            put("dPadMode", true)
        })
    }

    fun decode(root: JsonObject, defaults: ControllerLayouts): ControllerLayouts =
        if (root["profiles"] != null) decodeCurrent(root, defaults) else decodeLegacy(
            root,
            defaults
        )

    private fun encodeProfile(profile: VirtualControllerProfile): JsonObject = buildJsonObject {
        put("id", profile.id)
        put("controls", encodeControls(profile.controls))
    }

    private fun encodeControls(controls: List<VirtualControl>): JsonArray = buildJsonArray {
        controls.forEach { control ->
            add(buildJsonObject {
                put("id", control.id)
                put("type", control.type.name)
                put("behavior", control.behavior.name)
                put("centerX", control.x + control.width / 2f)
                put("centerY", control.y + control.height / 2f)
                put("width", control.width)
                put("height", control.height)
                put("color", control.color)
                put("alpha", (control.opacity * 255).toInt())
                put("opacity", control.opacity)
                put("keyCode", control.keyCode ?: actionKeyCode(control.action))
                control.keyCode?.let { put("virtualKeyCode", it) }
                put("action", control.action.name)
                control.label?.let { put("label", it) }
                put("shape", control.shape.name)
                put("cornerRadius", if (control.shape == VirtualControlShape.CIRCLE) .5f else .08f)
                put("isUndeletable", false)
                put("isUnmovable", false)
            })
        }
    }

    private fun decodeCurrent(root: JsonObject, defaults: ControllerLayouts): ControllerLayouts {
        val profiles = root.getValue("profiles").jsonObject
        val version = root.int("version") ?: 1
        val loadedGamepad =
            profiles["gamepad"]?.jsonObject?.let { decodeProfile(it, defaults.gamepad) }
                ?: defaults.gamepad
        val loadedKeyboard =
            profiles["keyboard"]?.jsonObject?.let { decodeProfile(it, defaults.keyboard) }
                ?: defaults.keyboard
        return ControllerLayouts(
            mode = root.string("mode")
                ?.let { runCatching { ControllerMode.valueOf(it) }.getOrNull() }
                ?: defaults.mode,
            gamepad = when {
                version < 3 && loadedGamepad.isPreviousBuiltInGamepad() -> defaults.gamepad
                version < 4 && loadedGamepad.isPreviousSplitDpadGamepad() -> defaults.gamepad
                version < 6 && loadedGamepad.matchesProfile(PreviousDefaultGamepadProfile) -> defaults.gamepad
                version < 7 && loadedGamepad.matchesProfile(PreviousLargeGamepadProfile) -> defaults.gamepad
                else -> loadedGamepad
            },
            keyboard = when {
                version < 2 && loadedKeyboard.controls.none { it.keyCode != null } -> defaults.keyboard
                version < 5 && loadedKeyboard.matchesProfile(PreviousDefaultKeyboardProfile) -> defaults.keyboard
                else -> loadedKeyboard
            },
        )
    }

    private fun decodeLegacy(root: JsonObject, defaults: ControllerLayouts): ControllerLayouts {
        val buttons = root["buttons"]?.jsonArray?.mapIndexedNotNull { index, element ->
            decodeControl(index, element, preserveLegacyKeyCode = true)
        }.orEmpty()
        val directions = defaults.gamepad.controls.filter { it.action in DIRECTION_ACTIONS }
        val controls = LinkedHashMap<String, VirtualControl>()
        directions.forEach { controls[it.id] = it }
        buttons.forEach { controls[it.id] = it }
        val profile = defaults.gamepad.copy(controls = controls.values.toList())
        return defaults.copy(gamepad = profile.validOr(defaults.gamepad))
    }

    private fun decodeProfile(
        value: JsonObject,
        fallback: VirtualControllerProfile
    ): VirtualControllerProfile {
        val controls = value["controls"]?.jsonArray?.mapIndexedNotNull { index, element ->
            decodeControl(index, element)
        }.orEmpty()
        if (controls.isEmpty()) return fallback
        return VirtualControllerProfile(value.string("id") ?: fallback.id, controls).validOr(
            fallback
        )
    }

    private fun decodeControl(
        index: Int,
        element: JsonElement,
        preserveLegacyKeyCode: Boolean = false,
    ): VirtualControl? = runCatching {
        val value = element.jsonObject
        val width = value.float("width")?.coerceIn(.02f, 1f) ?: .1f
        val height = value.float("height")?.coerceIn(.02f, 1f) ?: .12f
        val centerX = value.float("centerX") ?: .5f
        val centerY = value.float("centerY") ?: .5f
        val storedKeyCode =
            value.int("virtualKeyCode") ?: value.int("keyCode").takeIf { preserveLegacyKeyCode }
        val action =
            value.string("action")?.let { runCatching { GameAction.valueOf(it) }.getOrNull() }
                ?: keyCodeAction(value.int("keyCode"))
                ?: storedKeyCode?.let { GameAction.OK }
                ?: return null
        VirtualControl(
            id = (value.string("id") ?: "button-$index").sanitizeId("button-$index"),
            type = value.string("type")
                ?.let { runCatching { VirtualControlType.valueOf(it) }.getOrNull() }
                ?: VirtualControlType.BUTTON,
            action = action,
            x = (centerX - width / 2f).coerceIn(0f, 1f - width),
            y = (centerY - height / 2f).coerceIn(0f, 1f - height),
            width = width,
            height = height,
            opacity = ((value.float("opacity") ?: ((value.int("alpha")
                ?: 180) / 255f))).coerceIn(0f, 1f),
            label = value.string("label")?.take(32),
            behavior = value.string("behavior")?.let {
                runCatching { VirtualControlBehavior.valueOf(it) }.getOrNull()
            } ?: VirtualControlBehavior.HOLD,
            shape = value.string("shape")
                ?.let { runCatching { VirtualControlShape.valueOf(it) }.getOrNull() }
                ?: VirtualControlShape.ROUNDED_RECTANGLE,
            color = value.int("color") ?: 0xFFE7E7E7.toInt(),
            keyCode = storedKeyCode,
        )
    }.getOrNull()

    private fun VirtualControllerProfile.validOr(fallback: VirtualControllerProfile): VirtualControllerProfile =
        runCatching { also(VirtualControllerProfileValidator::validate) }.getOrDefault(fallback)

    private fun VirtualControllerProfile.isPreviousBuiltInGamepad(): Boolean {
        if (controls.map { it.id }.toSet() != PREVIOUS_GAMEPAD_CONTROLS.keys) return false
        return controls.all { control ->
            val expected = PREVIOUS_GAMEPAD_CONTROLS[control.id] ?: return@all false
            control.keyCode == null &&
                    control.action == expected.first &&
                    abs(control.x - expected.second.first) < .0001f &&
                    abs(control.y - expected.second.second) < .0001f
        }
    }

    private fun VirtualControllerProfile.isPreviousSplitDpadGamepad(): Boolean {
        if (controls.map { it.id }.toSet() != PREVIOUS_SPLIT_DPAD_CONTROLS.keys) return false
        return controls.all { control ->
            val expected = PREVIOUS_SPLIT_DPAD_CONTROLS[control.id] ?: return@all false
            control.type == VirtualControlType.BUTTON &&
                    control.action == expected.action &&
                    control.keyCode == expected.keyCode &&
                    control.label == expected.label &&
                    control.shape == expected.shape &&
                    control.behavior == VirtualControlBehavior.HOLD &&
                    control.color == PREVIOUS_GAMEPAD_COLOR &&
                    abs(control.x - expected.x) < .0001f &&
                    abs(control.y - expected.y) < .0001f &&
                    abs(control.width - expected.width) < .0001f &&
                    abs(control.height - expected.height) < .0001f &&
                    abs(control.opacity - .82f) < .0001f
        }
    }

    private fun VirtualControllerProfile.matchesProfile(expected: VirtualControllerProfile): Boolean {
        if (id != expected.id || controls.size != expected.controls.size) return false
        return controls.zip(expected.controls).all { (actual, oldDefault) ->
            actual.id == oldDefault.id &&
                    actual.type == oldDefault.type &&
                    actual.action == oldDefault.action &&
                    actual.keyCode == oldDefault.keyCode &&
                    actual.label == oldDefault.label &&
                    actual.behavior == oldDefault.behavior &&
                    actual.shape == oldDefault.shape &&
                    actual.color == oldDefault.color &&
                    abs(actual.x - oldDefault.x) < .0001f &&
                    abs(actual.y - oldDefault.y) < .0001f &&
                    abs(actual.width - oldDefault.width) < .0001f &&
                    abs(actual.height - oldDefault.height) < .0001f &&
                    abs(actual.opacity - oldDefault.opacity) < .0001f
        }
    }

    private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.float(name: String) = get(name)?.jsonPrimitive?.floatOrNull
    private fun JsonObject.int(name: String) = get(name)?.jsonPrimitive?.intOrNull

    private fun String.sanitizeId(fallback: String): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { fallback }

    private fun actionKeyCode(action: GameAction): Int = when (action) {
        GameAction.UP -> 19
        GameAction.DOWN -> 20
        GameAction.LEFT -> 21
        GameAction.RIGHT -> 22
        GameAction.OK -> 66
        GameAction.CANCEL, GameAction.ESCAPE -> 111
        GameAction.SHIFT -> 59
        GameAction.MENU -> 82
        GameAction.PAGE_UP -> 92
        GameAction.PAGE_DOWN -> 93
        GameAction.CONTROL -> 113
        GameAction.TAB -> 61
        GameAction.DEBUG -> 131
        else -> 0
    }

    private fun keyCodeAction(keyCode: Int?): GameAction? = when (keyCode) {
        19 -> GameAction.UP
        20 -> GameAction.DOWN
        21 -> GameAction.LEFT
        22 -> GameAction.RIGHT
        23, 66, 54 -> GameAction.OK
        4, 30, 111 -> GameAction.CANCEL
        59, 60 -> GameAction.SHIFT
        61 -> GameAction.TAB
        82 -> GameAction.MENU
        92 -> GameAction.PAGE_UP
        93 -> GameAction.PAGE_DOWN
        113, 114 -> GameAction.CONTROL
        131 -> GameAction.DEBUG
        else -> null
    }

    private val DIRECTION_ACTIONS =
        setOf(GameAction.UP, GameAction.DOWN, GameAction.LEFT, GameAction.RIGHT)
    private val PREVIOUS_GAMEPAD_CONTROLS = mapOf(
        "up" to (GameAction.UP to (.09f to .57f)),
        "down" to (GameAction.DOWN to (.09f to .79f)),
        "left" to (GameAction.LEFT to (.02f to .68f)),
        "right" to (GameAction.RIGHT to (.16f to .68f)),
        "ok" to (GameAction.OK to (.84f to .64f)),
        "cancel" to (GameAction.CANCEL to (.73f to .78f)),
        "shift" to (GameAction.SHIFT to (.90f to .82f)),
        "menu" to (GameAction.MENU to (.78f to .55f)),
    )

    private data class PreviousControl(
        val action: GameAction,
        val keyCode: Int?,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val label: String?,
        val shape: VirtualControlShape,
    )

    private val PREVIOUS_SPLIT_DPAD_CONTROLS = mapOf(
        "up" to PreviousControl(
            GameAction.UP,
            null,
            .075f,
            .58f,
            .055f,
            .13f,
            "▲",
            VirtualControlShape.ROUNDED_RECTANGLE
        ),
        "left" to PreviousControl(
            GameAction.LEFT,
            null,
            .02f,
            .71f,
            .055f,
            .13f,
            "◀",
            VirtualControlShape.ROUNDED_RECTANGLE
        ),
        "right" to PreviousControl(
            GameAction.RIGHT,
            null,
            .13f,
            .71f,
            .055f,
            .13f,
            "▶",
            VirtualControlShape.ROUNDED_RECTANGLE
        ),
        "down" to PreviousControl(
            GameAction.DOWN,
            null,
            .075f,
            .84f,
            .055f,
            .13f,
            "▼",
            VirtualControlShape.ROUNDED_RECTANGLE
        ),
        "enter" to PreviousControl(
            GameAction.OK,
            66,
            .82f,
            .63f,
            .07f,
            .14f,
            null,
            VirtualControlShape.CIRCLE
        ),
        "escape" to PreviousControl(
            GameAction.CANCEL,
            111,
            .91f,
            .63f,
            .07f,
            .14f,
            null,
            VirtualControlShape.CIRCLE
        ),
        "key-z" to PreviousControl(
            GameAction.OK,
            54,
            .82f,
            .81f,
            .07f,
            .14f,
            null,
            VirtualControlShape.CIRCLE
        ),
        "key-b" to PreviousControl(
            GameAction.CANCEL,
            30,
            .91f,
            .81f,
            .07f,
            .14f,
            null,
            VirtualControlShape.CIRCLE
        ),
    )

    private const val PREVIOUS_GAMEPAD_COLOR = -14342357
}