package io.github.gdlbo.makerplay.feature.player

import io.github.gdlbo.makerplay.feature.player.controller.data.ControllerLayoutStore
import io.github.gdlbo.makerplay.feature.player.controller.data.ControllerLayoutJsonCodec
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultGamepadProfile
import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultKeyboardProfile
import io.github.gdlbo.makerplay.feature.player.controller.model.PreviousDefaultKeyboardProfile
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControlType
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerLayoutStoreTest {
    @Test
    fun `upgrades the previous keyboard only when it was not customized`() {
        val directory = Files.createTempDirectory("makerplay-keyboard-migration-").toFile()
        try {
            val file = File(directory, "gamepad.json")
            val store = ControllerLayoutStore(file)
            val previousLayouts = ControllerLayouts(keyboard = PreviousDefaultKeyboardProfile)
            val previousJson = ControllerLayoutJsonCodec.encode(previousLayouts).toString()
                .replace("\"version\":5", "\"version\":4")
            file.writeText(previousJson)

            val upgraded = store.load()

            assertEquals(DefaultKeyboardProfile, upgraded.keyboard)

            val customized = PreviousDefaultKeyboardProfile.copy(
                controls = PreviousDefaultKeyboardProfile.controls.mapIndexed { index, control ->
                    if (index == 0) control.copy(x = control.x + .01f) else control
                },
            )
            val customizedJson = ControllerLayoutJsonCodec.encode(previousLayouts.copy(keyboard = customized)).toString()
                .replace("\"version\":5", "\"version\":4")
            file.writeText(customizedJson)

            val restoredCustomized = store.load().keyboard
            assertNotEquals(DefaultKeyboardProfile, restoredCustomized)
            assertEquals(customized.controls.first().x, restoredCustomized.controls.first().x, .0001f)
            assertEquals(customized.controls.map { it.id }, restoredCustomized.controls.map { it.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `upgrades the previous built in gamepad without replacing custom layouts`() {
        val directory = Files.createTempDirectory("makerplay-layout-migration-").toFile()
        try {
            val file = File(directory, "gamepad.json")
            file.writeText(
                """{"version":2,"profiles":{"gamepad":{"id":"gamepad","controls":[{"id":"up","action":"UP","centerX":0.145,"centerY":0.655,"width":0.11,"height":0.17},{"id":"down","action":"DOWN","centerX":0.145,"centerY":0.875,"width":0.11,"height":0.17},{"id":"left","action":"LEFT","centerX":0.075,"centerY":0.765,"width":0.11,"height":0.17},{"id":"right","action":"RIGHT","centerX":0.215,"centerY":0.765,"width":0.11,"height":0.17},{"id":"ok","action":"OK","centerX":0.89,"centerY":0.73,"width":0.10,"height":0.18},{"id":"cancel","action":"CANCEL","centerX":0.78,"centerY":0.87,"width":0.10,"height":0.18},{"id":"shift","action":"SHIFT","centerX":0.935,"centerY":0.88,"width":0.07,"height":0.12},{"id":"menu","action":"MENU","centerX":0.815,"centerY":0.61,"width":0.07,"height":0.12}]}}}""",
            )

            val loaded = ControllerLayoutStore(file).load()

            assertEquals(DefaultGamepadProfile.controls.map { it.id }, loaded.gamepad.controls.map { it.id })
            assertEquals(setOf(66, 111, 54, 30), loaded.gamepad.controls.mapNotNull { it.keyCode }.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `loads legacy gamepad json and round trips both modes`() {
        val directory = Files.createTempDirectory("makerplay-layout-").toFile()
        try {
            val file = File(directory, "gamepad.json")
            file.writeText(
                """{"buttons":[{"id":"legacy-z","centerX":0.8,"centerY":0.7,"width":0.1,"height":0.12,"alpha":128,"keyCode":54,"label":"Z","shape":"CIRCLE"},{"id":"legacy-a","centerX":0.7,"centerY":0.7,"width":0.1,"height":0.12,"alpha":128,"keyCode":29,"label":"A","shape":"CIRCLE"}],"orientation":6}""",
            )
            val store = ControllerLayoutStore(file)
            val loaded = store.load()
            assertEquals(GameAction.OK, loaded.gamepad.controls.single { it.id == "legacy-z" }.action)
            assertEquals(29, loaded.gamepad.controls.single { it.id == "legacy-a" }.keyCode)

            val keyboard = loaded.copy(mode = ControllerMode.KEYBOARD)
            store.save(keyboard)
            val restored = store.load()
            assertEquals(ControllerMode.KEYBOARD, restored.mode)
            assertEquals(keyboard.keyboard.controls.map { it.id to it.action }, restored.keyboard.controls.map { it.id to it.action })
            assertEquals(keyboard.keyboard.controls.first().x, restored.keyboard.controls.first().x, .0001f)
            assertTrue(file.readText().contains("\"profiles\""))
            assertTrue(restored.keyboard.controls.size >= 50)
            assertEquals(VirtualControlType.D_PAD, restored.gamepad.controls.single { it.id == "dpad" }.type)
            assertEquals(
                keyboard.keyboard.controls.mapNotNull { it.keyCode },
                restored.keyboard.controls.mapNotNull { it.keyCode },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `upgrades untouched split dpad layout but preserves modified one`() {
        val directory = Files.createTempDirectory("makerplay-dpad-migration-").toFile()
        try {
            val file = File(directory, "gamepad.json")
            val store = ControllerLayoutStore(file)
            file.writeText(previousSplitDpadJson(upCenterX = .1025f))

            val upgraded = store.load()

            assertEquals(VirtualControlType.D_PAD, upgraded.gamepad.controls.single { it.id == "dpad" }.type)

            file.writeText(previousSplitDpadJson(upCenterX = .1025f, upShape = "CIRCLE"))
            val customized = store.load()

            assertTrue(customized.gamepad.controls.none { it.type == VirtualControlType.D_PAD })
            assertTrue(customized.gamepad.controls.any { it.id == "up" })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun previousSplitDpadJson(
        upCenterX: Float,
        upShape: String = "ROUNDED_RECTANGLE",
    ): String =
        """{"version":3,"mode":"GAMEPAD","profiles":{"gamepad":{"id":"gamepad","controls":[
            {"id":"up","type":"BUTTON","behavior":"HOLD","action":"UP","centerX":$upCenterX,"centerY":0.645,"width":0.055,"height":0.13,"opacity":0.82,"color":-14342357,"label":"\u25B2","shape":"$upShape"},
            {"id":"left","type":"BUTTON","behavior":"HOLD","action":"LEFT","centerX":0.0475,"centerY":0.775,"width":0.055,"height":0.13,"opacity":0.82,"color":-14342357,"label":"\u25C0","shape":"ROUNDED_RECTANGLE"},
            {"id":"right","type":"BUTTON","behavior":"HOLD","action":"RIGHT","centerX":0.1575,"centerY":0.775,"width":0.055,"height":0.13,"opacity":0.82,"color":-14342357,"label":"\u25B6","shape":"ROUNDED_RECTANGLE"},
            {"id":"down","type":"BUTTON","behavior":"HOLD","action":"DOWN","centerX":0.1025,"centerY":0.905,"width":0.055,"height":0.13,"opacity":0.82,"color":-14342357,"label":"\u25BC","shape":"ROUNDED_RECTANGLE"},
            {"id":"enter","type":"BUTTON","behavior":"HOLD","action":"OK","virtualKeyCode":66,"centerX":0.855,"centerY":0.70,"width":0.07,"height":0.14,"opacity":0.82,"color":-14342357,"shape":"CIRCLE"},
            {"id":"escape","type":"BUTTON","behavior":"HOLD","action":"CANCEL","virtualKeyCode":111,"centerX":0.945,"centerY":0.70,"width":0.07,"height":0.14,"opacity":0.82,"color":-14342357,"shape":"CIRCLE"},
            {"id":"key-z","type":"BUTTON","behavior":"HOLD","action":"OK","virtualKeyCode":54,"centerX":0.855,"centerY":0.88,"width":0.07,"height":0.14,"opacity":0.82,"color":-14342357,"shape":"CIRCLE"},
            {"id":"key-b","type":"BUTTON","behavior":"HOLD","action":"CANCEL","virtualKeyCode":30,"centerX":0.945,"centerY":0.88,"width":0.07,"height":0.14,"opacity":0.82,"color":-14342357,"shape":"CIRCLE"}
        ]}}} """.trimIndent()
}
