package io.github.jonnyfrick.musicbootcamp

import androidx.compose.ui.window.ComposeUIViewController
import io.github.jonnyfrick.musicbootcamp.ios.iosServices
import io.github.jonnyfrick.musicbootcamp.ui.App

fun MainViewController() = run {
    val services = iosServices()
    ComposeUIViewController { App(services) }
}
