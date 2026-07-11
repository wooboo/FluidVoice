package com.fluidvoice.remote

sealed interface OverlayState {
    data object Idle : OverlayState
    data object Recording : OverlayState
    data object Processing : OverlayState
    data class Error(val message: String) : OverlayState
}

sealed interface OverlayAction {
    data object Start : OverlayAction
    data object Confirm : OverlayAction
    data object Reject : OverlayAction
    data object Complete : OverlayAction
    data class Fail(val message: String) : OverlayAction
}

object OverlayReducer {
    fun reduce(state: OverlayState, action: OverlayAction): OverlayState = when (state) {
        OverlayState.Idle -> when (action) {
            OverlayAction.Start -> OverlayState.Recording
            else -> state
        }
        OverlayState.Recording -> when (action) {
            OverlayAction.Confirm -> OverlayState.Processing
            OverlayAction.Reject -> OverlayState.Idle
            else -> state
        }
        OverlayState.Processing -> when (action) {
            OverlayAction.Complete -> OverlayState.Idle
            is OverlayAction.Fail -> OverlayState.Error(action.message)
            else -> state
        }
        is OverlayState.Error -> when (action) {
            OverlayAction.Start -> OverlayState.Recording
            OverlayAction.Reject -> OverlayState.Idle
            else -> state
        }
    }
}
