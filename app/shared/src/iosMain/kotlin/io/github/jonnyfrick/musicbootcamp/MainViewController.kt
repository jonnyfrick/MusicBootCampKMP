package io.github.jonnyfrick.musicbootcamp

import androidx.compose.ui.window.ComposeUIViewController
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.ui.App

fun MainViewController() = ComposeUIViewController { App(PlatformServices.withoutMidi("iOS")) }
