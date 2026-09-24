package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import kotlin.math.roundToInt

/** Java: the run dialog ("Pursuit" → Go! / Stop, random ↔ memorized slider). */
@Composable
internal fun PracticeScreen(controller: AppController) {
    val settings = controller.settings
    val status = controller.status
    val running = controller.running

    controller.midiUnavailableReason?.let { reason ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(reason, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Spacer(Modifier.height(16.dp))
    }

    Text(
        "${modeLabel(settings.mode)} · ${NoteNames.displayName(settings.lowLimit)} – ${NoteNames.displayName(settings.highLimit)} · " +
            "every ${settings.breathingTime} s",
        style = MaterialTheme.typography.bodyMedium,
    )

    Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    status.given.isEmpty() -> "–"
                    !controller.preferences.showGivenNotes -> "♪ ?"
                    else -> status.given.distinct().joinToString("  ") { NoteNames.displayName(it) }
                },
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (status.lastCorrect) {
                    true -> "✓ correct"
                    false -> "✗ wrong"
                    null -> if (running) "listen and play along" else "press Go! to start"
                },
                color = when (status.lastCorrect) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Steps", status.steps)
                Stat("Correct", status.correct)
                Stat("Wrong", status.wrong)
                Stat("Stored", status.storedMistakes)
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = controller::startPractice, enabled = !running) { Text("Go!") }
        OutlinedButton(onClick = controller::stopPractice, enabled = running) { Text("Stop") }
        Spacer(Modifier.weight(1f))
        Text("Show notes")
        Switch(checked = controller.preferences.showGivenNotes, onCheckedChange = controller::setShowGivenNotes)
    }

    SectionTitle("Your sequences")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("random")
        Slider(
            value = (settings.learnedProbability * 10).toFloat(),
            onValueChange = { value -> controller.updateSettings { it.copy(learnedProbability = value.roundToInt() / 10.0) } },
            valueRange = 0f..10f,
            steps = 9,
            enabled = !running,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Text("memorized")
    }
    Hint(
        "${(settings.learnedProbability * 100).roundToInt()} % of the steps replay a sequence you got wrong before " +
            "(${controller.memoryCounts.sum()} stored for this mode).",
    )
}

@Composable
private fun Stat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
