package io.github.jonnyfrick.musicbootcamp.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.jonnyfrick.musicbootcamp.ui.App
import io.github.jonnyfrick.musicbootcamp.ui.AppController
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

fun main() = application {
    val scope = rememberCoroutineScope()
    val controller = remember { AppController(desktopServices(), scope) }
    val closing = remember { AtomicBoolean(false) }

    Window(
        title = "MusicBootCamp",
        state = rememberWindowState(width = 900.dp, height = 820.dp),
        onCloseRequest = {
            // Java asked "Save current setup?"; everything is saved automatically now.
            if (closing.compareAndSet(false, true)) {
                scope.launch {
                    controller.shutdown()
                    exitApplication()
                }
            }
        },
    ) {
        App(controller)
    }
}
