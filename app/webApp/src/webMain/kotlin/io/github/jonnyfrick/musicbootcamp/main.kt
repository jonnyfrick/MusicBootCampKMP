package io.github.jonnyfrick.musicbootcamp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.jonnyfrick.musicbootcamp.ui.App
import io.github.jonnyfrick.musicbootcamp.web.webServices

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val services = webServices()
    ComposeViewport {
        App(services)
    }
}
