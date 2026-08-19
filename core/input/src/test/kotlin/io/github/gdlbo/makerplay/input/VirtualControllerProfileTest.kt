package io.github.gdlbo.makerplay.input

import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualControllerProfileTest {
    @Test
    fun acceptsNormalizedUniqueControls() {
        VirtualControllerProfileValidator.validate(
            VirtualControllerProfile(
                "default",
                listOf(
                    VirtualControl(
                        "ok",
                        VirtualControlType.BUTTON,
                        GameAction.OK,
                        .8f,
                        .8f,
                        .15f,
                        .15f
                    ),
                    VirtualControl(
                        "cancel",
                        VirtualControlType.BUTTON,
                        GameAction.CANCEL,
                        .6f,
                        .8f,
                        .15f,
                        .15f
                    ),
                ),
            ),
        )
    }

    @Test
    fun rejectsDuplicateIdsAndOutOfBoundsGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerProfileValidator.validate(
                VirtualControllerProfile(
                    "default",
                    listOf(
                        VirtualControl(
                            "same",
                            VirtualControlType.BUTTON,
                            GameAction.OK,
                            0f,
                            0f,
                            .2f,
                            .2f
                        ),
                        VirtualControl(
                            "same",
                            VirtualControlType.BUTTON,
                            GameAction.CANCEL,
                            .3f,
                            .3f,
                            .2f,
                            .2f
                        ),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerProfileValidator.validate(
                VirtualControllerProfile(
                    "default",
                    listOf(
                        VirtualControl(
                            "bad",
                            VirtualControlType.BUTTON,
                            GameAction.OK,
                            .9f,
                            0f,
                            .2f,
                            .1f
                        )
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsPointerActionsAndInvalidOpacity() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerProfileValidator.validate(
                VirtualControllerProfile(
                    "default",
                    listOf(
                        VirtualControl(
                            "touch",
                            VirtualControlType.TOUCH_ZONE,
                            GameAction.POINTER_DOWN,
                            0f,
                            0f,
                            .2f,
                            .2f
                        )
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerProfileValidator.validate(
                VirtualControllerProfile(
                    "default",
                    listOf(
                        VirtualControl(
                            "bad",
                            VirtualControlType.BUTTON,
                            GameAction.OK,
                            0f,
                            0f,
                            .2f,
                            .2f,
                            opacity = 2f
                        )
                    ),
                ),
            )
        }
    }
}
