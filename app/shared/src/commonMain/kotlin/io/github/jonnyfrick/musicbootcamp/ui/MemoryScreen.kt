package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Java: Options → Memory (`MemoryOptionsDialog`), plus an overview of what was learned. */
@Composable
internal fun MemoryScreen(controller: AppController) {
    val settings = controller.settings
    val enabled = !controller.running
    var confirmClear by remember { mutableStateOf(false) }

    SectionTitle("Learning")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = settings.learnNewSequences,
            onCheckedChange = { checked -> controller.updateSettings { it.copy(learnNewSequences = checked) } },
            enabled = enabled,
        )
        Spacer(Modifier.width(12.dp))
        Text("Learn new sequences")
    }
    Hint("When you play a step wrong, the preceding steps are stored and come back later on the Practice tab.")
    Spacer(Modifier.height(8.dp))
    IntSlider("Remember", settings.memorySize, 1..16, enabled, valueText = { "$it steps" }) { value ->
        controller.updateSettings { it.copy(memorySize = value) }
    }
    IntSlider(
        "Transpositions",
        (settings.transpositionsProbability * 100).roundToInt(),
        0..100,
        enabled,
        valueText = { "$it %" },
    ) { value -> controller.updateSettings { it.copy(transpositionsProbability = value / 100.0) } }
    Hint("Transpositions are stored with the setup but not used by the exercise yet (neither in the Java version).")

    SectionTitle("Learned sequences (${modeLabel(settings.mode)})")
    val counts = controller.memoryCounts
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        counts.forEachIndexed { level, count ->
            Row {
                Text(priorityLabel(level), Modifier.width(280.dp))
                Text("$count", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Total: ${counts.sum()}", style = MaterialTheme.typography.titleSmall)
    }
    Hint(
        "New mistakes start at the top level. Each time a sequence is practised it moves one level down, " +
            "and after the last level it is forgotten. Higher levels come back more often.",
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { confirmClear = true }, enabled = enabled && counts.sum() > 0) {
        Text("Forget all sequences of this mode…")
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget ${counts.sum()} sequences?") },
            text = { Text("All learned sequences of the mode '${modeLabel(settings.mode)}' in this setup are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    controller.clearMemory()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun priorityLabel(level: Int) = when (level) {
    0 -> "Level 1 (newest, most often)"
    4 -> "Level 5 (almost mastered)"
    else -> "Level ${level + 1}"
}
