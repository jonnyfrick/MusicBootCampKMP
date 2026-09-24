package io.github.jonnyfrick.musicbootcamp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.ui.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App(PlatformServices.withoutMidi("the web"))
    }
}
