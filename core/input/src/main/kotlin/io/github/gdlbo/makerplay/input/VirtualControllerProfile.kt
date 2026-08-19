package io.github.gdlbo.makerplay.input

enum class VirtualControlType { D_PAD, STICK, BUTTON, TOUCH_ZONE }

enum class VirtualControlBehavior { HOLD, TOGGLE, TURBO }

enum class VirtualControlShape { CIRCLE, ROUNDED_RECTANGLE }

data class VirtualControl(
    val id: String,
    val type: VirtualControlType,
    val action: GameAction,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val opacity: Float = 1f,
    val label: String? = null,
    val behavior: VirtualControlBehavior = VirtualControlBehavior.HOLD,
    val shape: VirtualControlShape = VirtualControlShape.ROUNDED_RECTANGLE,
    val color: Int = 0xFFE7E7E7.toInt(),
    val keyCode: Int? = null,
)

data class VirtualControllerProfile(
    val id: String,
    val controls: List<VirtualControl>,
)

object VirtualControllerProfileValidator {
    const val MAX_CONTROLS = 80
    const val MAX_ID_CHARS = 64
    const val MAX_LABEL_CHARS = 32

    fun validate(profile: VirtualControllerProfile) {
        requireIdentifier(profile.id, "Profile id")
        require(profile.controls.size <= MAX_CONTROLS) { "Too many virtual controls" }
        require(profile.controls.map(VirtualControl::id).toSet().size == profile.controls.size) {
            "Virtual control ids must be unique"
        }
        profile.controls.forEach { control ->
            requireIdentifier(control.id, "Control id")
            require(control.action !in POINTER_ACTIONS) { "Pointer actions are not virtual controls" }
            require(control.x in 0f..1f && control.y in 0f..1f) { "Control position must be normalized" }
            require(control.width in MIN_SIZE..1f && control.height in MIN_SIZE..1f) {
                "Control size must be normalized and non-zero"
            }
            require(control.x + control.width <= 1f && control.y + control.height <= 1f) {
                "Control must stay inside the normalized canvas"
            }
            require(control.opacity in 0f..1f) { "Control opacity must be between 0 and 1" }
            require(control.keyCode == null || control.keyCode in 0..512) { "Control key code is invalid" }
            require(control.label == null || control.label.length <= MAX_LABEL_CHARS) {
                "Control label is too long"
            }
        }
    }

    private fun requireIdentifier(value: String, label: String) {
        require(value.length in 1..MAX_ID_CHARS && value.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) {
            "$label is invalid"
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private val POINTER_ACTIONS =
        setOf(GameAction.POINTER_DOWN, GameAction.POINTER_MOVE, GameAction.POINTER_UP)
    private const val MIN_SIZE = 0.02f
}