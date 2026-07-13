package com.fluidvoice.remote

sealed interface OverlayState {
    data object Idle : OverlayState
    data class Recording(val mode: OverlayPromptMode) : OverlayState
    data class Processing(val mode: OverlayPromptMode) : OverlayState
    data class Error(val message: String) : OverlayState
}

sealed interface OverlayAction {
    data class Start(val mode: OverlayPromptMode) : OverlayAction
    data object Confirm : OverlayAction
    data object Reject : OverlayAction
    data object Complete : OverlayAction
    data class Fail(val message: String) : OverlayAction
}

object OverlayReducer {
    fun reduce(state: OverlayState, action: OverlayAction): OverlayState = when (state) {
        OverlayState.Idle -> when (action) {
            is OverlayAction.Start -> OverlayState.Recording(action.mode)
            else -> state
        }
        is OverlayState.Recording -> when (action) {
            OverlayAction.Confirm -> OverlayState.Processing(state.mode)
            OverlayAction.Reject -> OverlayState.Idle
            else -> state
        }
        is OverlayState.Processing -> when (action) {
            OverlayAction.Complete -> OverlayState.Idle
            is OverlayAction.Fail -> OverlayState.Error(action.message)
            else -> state
        }
        is OverlayState.Error -> when (action) {
            OverlayAction.Reject -> OverlayState.Idle
            else -> state
        }
    }
}
