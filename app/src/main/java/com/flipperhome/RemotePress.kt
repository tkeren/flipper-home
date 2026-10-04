package com.flipperhome

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*

/** Separate a short tap from a held press, without restarting on busy-state changes. */
@Composable
internal fun Modifier.remotePress(
    id: String, enabled: Boolean, click: () -> Unit, hold: (Deferred<Unit>) -> Job?,
): Modifier {
    val canPress by rememberUpdatedState(enabled)
    val tap by rememberUpdatedState(click)
    val startHold by rememberUpdatedState(hold)
    var pressing by remember(id) { mutableStateOf(false) }
    return this.graphicsLayer { alpha = if(pressing) .65f else 1f }
        .semantics {
            role = Role.Button
            if(!enabled) disabled()
            onClick {
                if(canPress) { tap(); true } else false
            }
        }
        .pointerInput(id) {
            detectTapGestures(onPress = {
                if(canPress) coroutineScope {
                    val release = CompletableDeferred<Unit>()
                    var sender: Job? = null
                    var held = false
                    var lifted = false
                    val timer = launch { delay(300); held = true; sender = startHold(release) }
                    try {
                        pressing = true
                        lifted = tryAwaitRelease()
                        timer.cancelAndJoin()
                        release.complete(Unit)
                        if(lifted && !held) tap()
                    } finally {
                        timer.cancel()
                        release.complete(Unit)
                        if(!lifted) sender?.cancel()
                        pressing = false
                    }
                }
            })
        }
}
