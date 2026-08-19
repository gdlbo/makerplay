package io.github.gdlbo.makerplay.feature.player.controller.model

import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator

internal val DefaultGamepadProfile = VirtualControllerProfile(
    id = "gamepad",
    controls = listOf(
        dPadControl(),
        gamepadControl("enter", GameAction.OK, .82f, .63f, .07f, .14f, keyCode = 66),
        gamepadControl("escape", GameAction.CANCEL, .91f, .63f, .07f, .14f, keyCode = 111),
        gamepadControl("key-z", GameAction.OK, .82f, .81f, .07f, .14f, keyCode = 54),
        gamepadControl("key-b", GameAction.CANCEL, .91f, .81f, .07f, .14f, keyCode = 30),
    ),
).also(VirtualControllerProfileValidator::validate)

private fun dPadControl() = VirtualControl(
    id = "dpad",
    type = VirtualControlType.D_PAD,
    action = GameAction.UP,
    x = .02f,
    y = .58f,
    width = .165f,
    height = .39f,
    opacity = .82f,
    shape = VirtualControlShape.CIRCLE,
    color = 0xFF25272B.toInt(),
)

internal val DefaultKeyboardProfile = VirtualControllerProfile(
    id = "keyboard",
    controls = buildKeyboardControls(
        left = .25f,
        top = .66f,
        totalWidth = .50f,
        rowHeight = .052f,
        horizontalGap = .003f,
        verticalGap = .006f,
        color = 0xFF25272B.toInt(),
        opacity = .82f,
    ),
).also(VirtualControllerProfileValidator::validate)

internal val PreviousDefaultKeyboardProfile = VirtualControllerProfile(
    id = "keyboard",
    controls = buildKeyboardControls(
        left = .23f,
        top = .57f,
        totalWidth = .54f,
        rowHeight = .072f,
        horizontalGap = .004f,
        verticalGap = .008f,
        color = 0xFFE6E9EE.toInt(),
        opacity = .78f,
    ),
).also(VirtualControllerProfileValidator::validate)

private fun gamepadControl(
    id: String,
    action: GameAction,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    label: String? = null,
    keyCode: Int? = null,
) = VirtualControl(
    id = id,
    type = VirtualControlType.BUTTON,
    action = action,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = .82f,
    label = label,
    shape = if (keyCode == null) VirtualControlShape.ROUNDED_RECTANGLE else VirtualControlShape.CIRCLE,
    color = 0xFF25272B.toInt(),
    keyCode = keyCode,
)

private data class KeyboardKeySpec(val keyCode: Int, val units: Float = 1f)

private fun buildKeyboardControls(
    left: Float,
    top: Float,
    totalWidth: Float,
    rowHeight: Float,
    horizontalGap: Float,
    verticalGap: Float,
    color: Int,
    opacity: Float,
): List<VirtualControl> {
    val rows = listOf(
        listOf(68, 8, 9, 10, 11, 12, 13, 14, 15, 16, 7, 69, 70, 67).map(::KeyboardKeySpec),
        listOf(KeyboardKeySpec(61, 1.5f)) + keyCodes("QWERTYUIOP").map(::KeyboardKeySpec) + listOf(
            71,
            72,
            73
        ).map(::KeyboardKeySpec),
        listOf(KeyboardKeySpec(115, 1.7f)) + keyCodes("ASDFGHJKL").map(::KeyboardKeySpec) + listOf(
            KeyboardKeySpec(74),
            KeyboardKeySpec(75),
            KeyboardKeySpec(66, 2f)
        ),
        listOf(KeyboardKeySpec(59, 2.2f)) + keyCodes("ZXCVBNM").map(::KeyboardKeySpec) + listOf(
            KeyboardKeySpec(55),
            KeyboardKeySpec(56),
            KeyboardKeySpec(76),
            KeyboardKeySpec(60, 2.2f)
        ),
        listOf(
            KeyboardKeySpec(113, 1.4f),
            KeyboardKeySpec(57, 1.4f),
            KeyboardKeySpec(62, 6f),
            KeyboardKeySpec(58, 1.4f),
            KeyboardKeySpec(82, 1.4f),
            KeyboardKeySpec(114, 1.4f),
            KeyboardKeySpec(111, 1.4f)
        ),
    )
    return rows.flatMapIndexed { rowIndex, row ->
        val unitWidth = (totalWidth - horizontalGap * (row.size - 1)) /
                row.sumOf { it.units.toDouble() }.toFloat()
        var x = left
        row.mapIndexed { keyIndex, spec ->
            val width = unitWidth * spec.units
            VirtualControl(
                id = "key-${spec.keyCode}-$keyIndex",
                type = VirtualControlType.BUTTON,
                action = keyCodeActionForDefault(spec.keyCode),
                x = x,
                y = top + rowIndex * (rowHeight + verticalGap),
                width = width,
                height = rowHeight,
                opacity = opacity,
                shape = VirtualControlShape.ROUNDED_RECTANGLE,
                color = color,
                keyCode = spec.keyCode,
            ).also { x += width + horizontalGap }
        }
    }
}

private fun keyCodes(letters: String): List<Int> = letters.map { 29 + (it - 'A') }

private fun keyCodeActionForDefault(keyCode: Int): GameAction = when (keyCode) {
    66 -> GameAction.OK
    67, 111 -> GameAction.CANCEL
    59, 60 -> GameAction.SHIFT
    113, 114 -> GameAction.CONTROL
    61 -> GameAction.TAB
    82 -> GameAction.MENU
    else -> GameAction.OK
}