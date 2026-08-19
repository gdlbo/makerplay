package io.github.gdlbo.makerplay.runtime.webview.internal.input

import io.github.gdlbo.makerplay.input.LogicalInputSnapshot

internal class RuntimeInputMixer(
    private val dispatch: (LogicalInputSnapshot) -> Unit,
) {
    private var physical = EMPTY_INPUT
    private var virtual = EMPTY_INPUT
    private var lastDispatched: LogicalInputSnapshot? = null
    private var platformActive = true
    private var uiEnabled = true
    private var virtualNeedsNeutral = false

    fun setPhysical(snapshot: LogicalInputSnapshot) {
        physical = if (isActive()) snapshot else EMPTY_INPUT
        publish()
    }

    fun setVirtual(snapshot: LogicalInputSnapshot) {
        if (virtualNeedsNeutral) {
            if (snapshot.isNeutral()) virtualNeedsNeutral = false
            virtual = EMPTY_INPUT
        } else {
            virtual = if (isActive()) snapshot else EMPTY_INPUT
        }
        publish()
    }

    fun setPlatformActive(active: Boolean) {
        platformActive = active
        if (!active) clearAndRequireNeutral()
        publish()
    }

    fun setUiEnabled(enabled: Boolean) {
        uiEnabled = enabled
        if (!enabled) clearAndRequireNeutral()
        publish()
    }

    private fun clearAndRequireNeutral() {
        virtualNeedsNeutral = virtualNeedsNeutral || !virtual.isNeutral()
        physical = EMPTY_INPUT
        virtual = EMPTY_INPUT
    }

    private fun publish() {
        val snapshot = if (isActive()) {
            LogicalInputSnapshot(
                pressedActions = physical.pressedActions + virtual.pressedActions,
                pointers = physical.pointers + virtual.pointers,
                pressedKeyCodes = physical.pressedKeyCodes + virtual.pressedKeyCodes,
            )
        } else {
            EMPTY_INPUT
        }
        if (snapshot != lastDispatched) {
            lastDispatched = snapshot
            dispatch(snapshot)
        }
    }

    private fun isActive(): Boolean = platformActive && uiEnabled
}

internal val EMPTY_INPUT = LogicalInputSnapshot(emptySet(), emptySet())

private fun LogicalInputSnapshot.isNeutral(): Boolean =
    pressedActions.isEmpty() && pointers.isEmpty() && pressedKeyCodes.isEmpty()