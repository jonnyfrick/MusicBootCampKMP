package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.persistence.InputSource
import kotlin.math.roundToInt

/** Java: MusicBootCamp → Preferences (`PreferencesDialog`). */
@Composable
internal fun PreferencesScreen(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running

    SectionTitle("Input")
    RadioGroup(
        options = InputSource.entries,
        selected = preferences.inputSource,
        label = {
            when (it) {
                InputSource.MIDI -> "MIDI keyboard"
                InputSource.MICROPHONE -> "Microphone (acoustic piano, single notes)"
            }
        },
        enabled = { !running && (it == InputSource.MIDI || controller.audioUnavailableReason == null) },
        onSelect = controller::setInputSource,
    )
    if (preferences.inputSource == InputSource.MICROPHONE) MicrophoneSettings(controller)
    controller.audioUnavailableReason?.let { Hint(it) }

    SectionTitle("MIDI devices")
    controller.midiUnavailableReason?.let { Hint(it) }
    DeviceChooser(
        label = "MIDI In (your keyboard)",
        devices = controller.inputDevices,
        selected = preferences.midiInputDevice?.takeIf { it in controller.inputDevices }
            ?: controller.defaultDevice(controller.inputDevices),
        enabled = !running,
        onSelect = controller::selectInputDevice,
    )
    DeviceChooser(
        label = "MIDI Out (sound)",
        devices = controller.outputDevices,
        selected = preferences.midiOutputDevice?.takeIf { it in controller.outputDevices }
            ?: controller.defaultDevice(controller.outputDevices),
        enabled = !running,
        onSelect = controller::selectOutputDevice,
    )
    TextButton(onClick = controller::refreshDevices, enabled = !running) { Text("Refresh device list") }
    Hint("Devices are opened when you press Go!. \"Gervill\" is Java's built-in software synthesizer.")
    Spacer(Modifier.height(8.dp))
    MidiTest(controller)

    SectionTitle("Tuning")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Kammerton A", Modifier.width(140.dp))
        Slider(
            value = preferences.referenceAHz.toFloat(),
            onValueChange = { controller.setReferenceA((it * 2).roundToInt() / 2.0) },
            valueRange = Tuning.MIN_A_HZ.toFloat()..Tuning.MAX_A_HZ.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("${preferences.referenceAHz} Hz", Modifier.width(90.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { controller.setReferenceA(preferences.referenceAHz - Tuning.STEP_HZ) }) { Text("− 0.5 Hz") }
        TextButton(onClick = { controller.setReferenceA(preferences.referenceAHz + Tuning.STEP_HZ) }) { Text("+ 0.5 Hz") }
        TextButton(onClick = { controller.setReferenceA(Tuning.STANDARD_A_HZ) }) { Text("Reset to 440 Hz") }
    }
    Hint("Shifts the whole output by a pitch bend; applied immediately, also during an exercise.")
}

@Composable
private fun MicrophoneSettings(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running
    DeviceChooser(
        label = "Microphone",
        devices = listOf(SYSTEM_DEFAULT) + controller.audioInputDevices,
        selected = preferences.audioInputDevice?.takeIf { it in controller.audioInputDevices } ?: SYSTEM_DEFAULT,
        enabled = !running,
        onSelect = { controller.selectAudioInputDevice(it.takeIf { name -> name != SYSTEM_DEFAULT }) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = preferences.usesHeadphones, onCheckedChange = controller::setUsesHeadphones, enabled = !running)
        Spacer(Modifier.width(12.dp))
        Text("I use headphones")
    }
    Hint(
        if (preferences.usesHeadphones) {
            "Notes you play are recognised at any time."
        } else {
            "Without headphones the microphone hears the app too, so your notes only count once the given note has ended."
        },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (controller.micTesting) {
            OutlinedButton(onClick = controller::stopMicTest) { Text("Stop test") }
        } else {
            OutlinedButton(onClick = controller::startMicTest, enabled = !running) { Text("Test microphone") }
        }
        if (controller.micTesting) {
            LinearProgressIndicator(
                progress = { (controller.microphoneLevel * 5).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.width(160.dp),
            )
            val note = controller.micTestNote
            Text(
                if (note == null) "play a key…" else {
                    val cents = note.cents.roundToInt()
                    "${NoteNames.displayName(note.midiNote)} (${if (cents >= 0) "+" else ""}$cents ct)"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Hint("Relative to Kammerton A below. The level bar should move clearly when you play.")
}

@Composable
private fun MidiTest(controller: AppController) {
    val running = controller.running
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (controller.midiTesting) {
            OutlinedButton(onClick = controller::stopMidiTest) { Text("Stop test") }
        } else {
            OutlinedButton(onClick = controller::startMidiTest, enabled = !running && controller.inputDevices.isNotEmpty()) {
                Text("Test MIDI")
            }
        }
        OutlinedButton(onClick = controller::playTestNote, enabled = !running && controller.outputDevices.isNotEmpty()) {
            Text("Play test note")
        }
        if (controller.midiTesting) {
            val notes = controller.midiTestNotes
            Text(
                if (notes.isEmpty()) "play a key…" else {
                    val latest = notes.first()
                    "${NoteNames.displayName(latest.data1)} (${latest.data1}, velocity ${latest.data2})"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    if (controller.midiTesting && controller.midiTestNotes.size > 1) {
        Hint("Before: " + controller.midiTestNotes.drop(1).joinToString("  ") { NoteNames.displayName(it.data1) })
    }
    Hint("\"Test MIDI\" shows the keys arriving from MIDI In; \"Play test note\" plays A' on MIDI Out, tuned to the Kammerton A below.")
}

@Composable
private fun DeviceChooser(
    label: String,
    devices: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(200.dp))
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled && devices.isNotEmpty()) {
                Text(selected ?: "no device found")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                devices.forEach { device ->
                    DropdownMenuItem(text = { Text(device) }, onClick = {
                        open = false
                        onSelect(device)
                    })
                }
            }
        }
    }
}

private const val SYSTEM_DEFAULT = "System default"
