package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict
import io.github.jonnyfrick.musicbootcamp.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The main screen (Java: the run dialog): what the app plays, how it went, and Start / Stop as
 * the floating action button. The exercise's settings open in a bottom sheet on phones and
 * stay open in a side pane on wide windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PracticeScreen(controller: AppController, snackbar: SnackbarHostState) {
    val wide = isWideWindow()
    var sheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SetupTopBar(controller) {
                if (!wide) {
                    IconButton(onClick = { sheet = true }) {
                        Icon(AppIcons.Tune, contentDescription = stringResource(Res.string.customize_exercise))
                    }
                }
            }
        },
        // On wide windows the button belongs to the practice area, not over the exercise pane.
        floatingActionButton = { if (!wide) StartStopButton(controller) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PracticeContent(controller, onCustomize = null, modifier = Modifier)
                    StartStopButton(controller, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
                VerticalDivider()
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(400.dp).fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        Text(
                            stringResource(Res.string.exercise),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = SettingPadding, top = 16.dp, end = SettingPadding),
                        )
                        ExerciseSettings(controller)
                    }
                }
            }
        } else {
            PracticeContent(controller, onCustomize = { sheet = true }, modifier = Modifier.padding(padding))
        }
    }

    if (sheet && !wide) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(
                stringResource(Res.string.exercise),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = SettingPadding),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ExerciseSettings(controller)
            }
        }
    }
}

/** Start / Stop, the screen's main action. */
@Composable
private fun StartStopButton(controller: AppController, modifier: Modifier = Modifier) {
    val running = controller.running
    // The extended FAB does not pass its text on to screen readers by itself.
    val label = stringResource(if (running) Res.string.stop else Res.string.start)
    ExtendedFloatingActionButton(
        onClick = if (running) controller::stopPractice else controller::startPractice,
        icon = { Icon(if (running) AppIcons.Stop else AppIcons.Play, contentDescription = null) },
        text = { Text(label) },
        modifier = modifier.semantics { contentDescription = label },
        containerColor = if (running) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (running) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun PracticeContent(controller: AppController, onCustomize: (() -> Unit)?, modifier: Modifier) {
    val settings = controller.settings
    val status = controller.status
    val running = controller.running

    CenteredColumn(modifier, bottomSpace = 96.dp) {
        controller.midiUnavailableReason?.let { reason ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 8.dp),
            ) {
                Text(reason, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // What the exercise is; on phones a tap opens its settings.
        val summary = stringResource(
            Res.string.practice_summary,
            modeLabel(settings.mode),
            controller.noteName(settings.lowLimit),
            controller.noteName(settings.highLimit),
            settings.breathingTime.toString(),
        )
        if (onCustomize != null) {
            FilterChip(
                selected = false,
                onClick = onCustomize,
                label = { Text(summary) },
                leadingIcon = { Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.padding(horizontal = SettingPadding),
            )
        } else {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingPadding, vertical = 8.dp),
            )
        }

        PracticeStage(status, controller.feedback, running, settings, controller.preferences.showGivenNotes, controller::noteName)

        Row(Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(stringResource(Res.string.stat_steps), status.steps)
            Stat(stringResource(Res.string.stat_correct), status.correct, MaterialTheme.extendedColors.correct)
            Stat(stringResource(Res.string.stat_wrong), status.wrong, MaterialTheme.colorScheme.error)
            Stat(stringResource(Res.string.stat_stored), status.storedMistakes)
        }

        FilterChip(
            selected = controller.preferences.showGivenNotes,
            onClick = { controller.setShowGivenNotes(!controller.preferences.showGivenNotes) },
            label = { Text(stringResource(Res.string.show_notes)) },
            leadingIcon = if (controller.preferences.showGivenNotes) {
                { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = SettingPadding),
        )

        if (!running) controller.runSummary?.let { RunSummaryCard(it, controller::noteName) }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** After a run in optimization mode: each step with what was recognised. */
@Composable
private fun RunSummaryCard(summary: RunSummary, noteName: (Int) -> String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.run_summary_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.run_summary_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalSpace()
            SummaryRow("#", "", stringResource(Res.string.run_summary_given), stringResource(Res.string.run_summary_heard), header = true)
            for (step in summary.steps) {
                SummaryRow(
                    number = step.number.toString(),
                    result = when (step.correct) {
                        true -> "✓"
                        false -> "✗"
                        null -> ""
                    },
                    given = step.given.joinToString(" ") { noteName(it) },
                    heard = step.detected.joinToString(" ") { noteName(it) }.ifEmpty { "–" },
                    wrong = step.correct == false,
                )
            }
            VerticalSpace()
            Text(
                stringResource(Res.string.run_summary_recording, summary.recording),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryRow(number: String, result: String, given: String, heard: String, header: Boolean = false, wrong: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = when {
        header -> MaterialTheme.colorScheme.onSurfaceVariant
        wrong -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = style, color = color, fontFamily = FontFamily.Monospace, modifier = Modifier.width(36.dp))
        Text(result, style = style, color = color, modifier = Modifier.width(24.dp))
        Text(given, style = style, color = color, modifier = Modifier.weight(1f))
        Text(heard, style = style, color = color, modifier = Modifier.weight(1f))
    }
}
