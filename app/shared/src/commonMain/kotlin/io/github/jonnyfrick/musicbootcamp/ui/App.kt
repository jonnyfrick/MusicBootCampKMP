package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowSizeClass
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Entry point for platforms that need no shutdown handling (Android, iOS, web). */
@Composable
fun App(services: PlatformServices) {
    val scope = rememberCoroutineScope()
    val controller = remember(services) { AppController(services, scope) }
    App(controller)
}

/** The top-level destinations: a bottom bar on phones, a rail beside the content on wider windows. */
private enum class Destination(val label: StringResource, val icon: ImageVector) {
    PRACTICE(Res.string.nav_practice, AppIcons.MusicNote),
    MEMORY(Res.string.nav_memory, AppIcons.History),
    SETTINGS(Res.string.nav_settings, AppIcons.Settings),
}

@Composable
fun App(controller: AppController) {
    LaunchedEffect(controller) { controller.load() }

    AppTheme {
        val snackbar = remember { SnackbarHostState() }
        val message = controller.message
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message.text(), withDismissAction = true, duration = SnackbarDuration.Long)
                controller.dismissMessage()
            }
        }

        var destination by rememberSaveable { mutableStateOf(Destination.PRACTICE) }
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Destination.entries.forEach { entry ->
                    item(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            },
        ) {
            if (!controller.ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@NavigationSuiteScaffold
            }
            when (destination) {
                Destination.PRACTICE -> PracticeScreen(controller, snackbar)
                Destination.MEMORY -> MemoryScreen(controller, snackbar)
                Destination.SETTINGS -> SettingsScreen(controller, snackbar)
            }
        }
    }
}

/** Whether the window is wide enough to show a second pane next to the main content. */
@Composable
internal fun isWideWindow(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
