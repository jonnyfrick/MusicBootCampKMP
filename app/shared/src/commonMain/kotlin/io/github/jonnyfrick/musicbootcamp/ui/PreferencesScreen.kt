package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import kotlin.math.roundToInt

/** Java: MusicBootCamp → Preferences (`PreferencesDialog`). */
@Composable
internal fun PreferencesScreen(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running

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
