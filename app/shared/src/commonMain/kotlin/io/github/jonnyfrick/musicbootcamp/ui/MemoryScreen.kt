package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Java: Options → Memory (`MemoryOptionsDialog`), plus an overview of what was learned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(controller: AppController, snackbar: SnackbarHostState) {
    Scaffold(
        topBar = { SetupTopBar(controller) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CenteredColumn(Modifier.padding(padding)) { MemoryContent(controller) }
    }
}

@Composable
private fun MemoryContent(controller: AppController) {
    val settings = controller.settings
    val enabled = !controller.running
    var confirmClear by remember { mutableStateOf(false) }

    SettingsSection(stringResource(Res.string.section_learning)) {
        SwitchSetting(
            title = stringResource(Res.string.learn_new),
            checked = settings.learnNewSequences,
            onCheckedChange = { checked -> controller.updateSettings { it.copy(learnNewSequences = checked) } },
            enabled = enabled,
            supporting = stringResource(Res.string.learn_new_hint),
        )
        IntSliderSetting(
            title = stringResource(Res.string.remember),
            value = settings.memorySize,
            range = 1..16,
            onChange = { value -> controller.updateSettings { it.copy(memorySize = value) } },
            enabled = enabled,
            valueText = stringResource(Res.string.value_steps, settings.memorySize),
        )
        val transpositions = (settings.transpositionsProbability * 100).roundToInt()
        IntSliderSetting(
            title = stringResource(Res.string.transpositions),
            value = transpositions,
            range = 0..100,
            onChange = { value -> controller.updateSettings { it.copy(transpositionsProbability = value / 100.0) } },
            enabled = enabled,
            valueText = "$transpositions %",
            supporting = stringResource(Res.string.transpositions_hint),
            fineSteps = true,
        )
    }

    SettingsSection(stringResource(Res.string.section_learned, modeLabel(settings.mode))) {
        val counts = controller.memoryCounts
        val most = counts.maxOrNull()?.takeIf { it > 0 } ?: 1
        counts.forEachIndexed { level, count ->
            ListItem(
                headlineContent = { Text(levelLabel(level, counts.size)) },
                supportingContent = {
                    LinearProgressIndicator(
                        progress = { count.toFloat() / most },
                        modifier = Modifier.padding(top = 8.dp).width(200.dp),
                        drawStopIndicator = {},
                    )
                },
                trailingContent = { Text("$count", style = MaterialTheme.typography.titleMedium) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(Res.string.total, counts.sum()), style = MaterialTheme.typography.titleSmall) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (controller.sequencesOutsideRange > 0) SettingHint(stringResource(Res.string.outside_range, controller.sequencesOutsideRange))
        SettingHint(stringResource(Res.string.levels_hint))
        ButtonRow {
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = enabled && counts.sum() > 0,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.forget_all)) }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(Res.string.forget_title, counts.sum())) },
                text = { Text(stringResource(Res.string.forget_text, modeLabel(settings.mode))) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        controller.clearMemory()
                    }) { Text(stringResource(Res.string.forget), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun levelLabel(level: Int, levels: Int): String = when (level) {
    0 -> stringResource(Res.string.level_first)
    levels - 1 -> stringResource(Res.string.level_last)
    else -> stringResource(Res.string.level, level + 1)
}
