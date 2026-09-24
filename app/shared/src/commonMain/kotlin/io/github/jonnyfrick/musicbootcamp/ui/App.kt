package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices

/** Entry point for platforms that need no shutdown handling (Android, iOS, web). */
@Composable
fun App(services: PlatformServices) {
    val scope = rememberCoroutineScope()
    val controller = remember(services) { AppController(services, scope) }
    App(controller)
}

private enum class Screen(val title: String) {
    PRACTICE("Practice"),
    EXERCISE("Exercise"),
    MEMORY("Memory"),
    PREFERENCES("Preferences"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(controller: AppController) {
    LaunchedEffect(controller) { controller.load() }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val snackbar = remember { SnackbarHostState() }
        val message = controller.message
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
                controller.dismissMessage()
            }
        }

        var screen by rememberSaveable { mutableStateOf(Screen.PRACTICE) }

        Scaffold(
            topBar = { SetupBar(controller) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (!controller.ready) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = screen.ordinal) {
                    Screen.entries.forEach { entry ->
                        Tab(
                            selected = screen == entry,
                            onClick = { screen = entry },
                            text = { Text(entry.title) },
                        )
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 720.dp).padding(16.dp)) {
                            when (screen) {
                                Screen.PRACTICE -> PracticeScreen(controller)
                                Screen.EXERCISE -> ExerciseScreen(controller)
                                Screen.MEMORY -> MemoryScreen(controller)
                                Screen.PREFERENCES -> PreferencesScreen(controller)
                            }
                        }
                    }
                }
            }
        }
    }
}
