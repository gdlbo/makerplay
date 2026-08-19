package io.github.gdlbo.makerplay.feature.player.controller.model

import io.github.gdlbo.makerplay.input.VirtualControllerProfile

internal enum class ControllerMode { GAMEPAD, KEYBOARD }

internal data class ControllerLayouts(
    val mode: ControllerMode = ControllerMode.GAMEPAD,
    val gamepad: VirtualControllerProfile = DefaultGamepadProfile,
    val keyboard: VirtualControllerProfile = DefaultKeyboardProfile,
) {
    fun activeProfile(): VirtualControllerProfile =
        if (mode == ControllerMode.GAMEPAD) gamepad else keyboard

    fun updateActive(profile: VirtualControllerProfile): ControllerLayouts = when (mode) {
        ControllerMode.GAMEPAD -> copy(gamepad = profile)
        ControllerMode.KEYBOARD -> copy(keyboard = profile)
    }

    fun resetActive(): ControllerLayouts = when (mode) {
        ControllerMode.GAMEPAD -> copy(gamepad = DefaultGamepadProfile)
        ControllerMode.KEYBOARD -> copy(keyboard = DefaultKeyboardProfile)
    }
}