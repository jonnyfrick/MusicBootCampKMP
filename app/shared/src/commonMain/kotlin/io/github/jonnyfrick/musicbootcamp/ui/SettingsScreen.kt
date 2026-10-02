package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.persistence.InputSource
import io.github.jonnyfrick.musicbootcamp.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The pages of the settings (Java: MusicBootCamp → Preferences, `PreferencesDialog`). */
internal enum class SettingsPage(val title: StringResource, val icon: ImageVector) {
    INPUT(Res.string.settings_input, AppIcons.Mic),
    MIDI(Res.string.settings_midi, AppIcons.Speaker),
    TUNING(Res.string.settings_tuning, AppIcons.Tune),
    RECOGNITION(Res.string.settings_recognition, AppIcons.GraphicEq),
}

/**
 * Material's list-detail pattern: the pages as a list, one page at a time on phones (with the
 * system's back gesture), list and page side by side on wide windows.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun SettingsScreen(controller: AppController, snackbar: SnackbarHostState) {
    val navigator = rememberListDetailPaneScaffoldNavigator<SettingsPage>()
    val scope = rememberCoroutineScope()
    val twoPanes = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Hidden &&
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    // The recognition's tuning tools are for debug builds only.
    val pages = SettingsPage.entries.filter { it != SettingsPage.RECOGNITION || controller.debugTools }
    // Side by side, a page is always shown: the first one until another is chosen.
    val page = navigator.currentDestination?.contentKey ?: SettingsPage.INPUT

    // The system's back (gesture, button, Escape) returns from a page to the list where only one shows.
    BackHandler(enabled = navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        listPane = {
            AnimatedPane {
                Scaffold(
                    topBar = { TopAppBar(title = { Text(stringResource(Res.string.nav_settings)) }) },
                    snackbarHost = { if (!twoPanes) SnackbarHost(snackbar) },
                ) { padding ->
                    CenteredColumn(Modifier.padding(padding)) {
                        pages.forEach { entry ->
                            NavigationSetting(
                                title = stringResource(entry.title),
                                summary = summary(controller, entry),
                                icon = entry.icon,
                                selected = twoPanes && entry == page,
                                onClick = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, entry) } },
                            )
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(page.title)) },
                            navigationIcon = {
                                if (!twoPanes) {
                                    IconButton(onClick = { scope.launch { navigator.navigateBack() } }) {
                                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
                                    }
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    CenteredColumn(Modifier.padding(padding)) {
                        when (page) {
                            SettingsPage.INPUT -> InputSettings(controller)
                            SettingsPage.MIDI -> MidiSettings(controller)
                            SettingsPage.TUNING -> TuningSettings(controller)
                            SettingsPage.RECOGNITION -> RecognitionSettings(controller)
                        }
                    }
                }
            }
        },
    )
}

/** What a page is set to, in one line of the list. */
@Composable
private fun summary(controller: AppController, page: SettingsPage): String? {
    val preferences = controller.preferences
    return when (page) {
        SettingsPage.INPUT -> inputLabel(preferences.inputSource)
        SettingsPage.MIDI -> listOfNotNull(selectedInput(controller), selectedOutput(controller)).joinToString(" → ").ifEmpty { null }
        SettingsPage.TUNING -> "A = ${stringResource(Res.string.value_hz, preferences.referenceAHz.toString())}"
        SettingsPage.RECOGNITION -> stringResource(Res.string.settings_recognition_summary)
    }
}

@Composable
private fun inputLabel(source: InputSource) = stringResource(
    when (source) {
        InputSource.MIDI -> Res.string.input_midi
        InputSource.MICROPHONE -> Res.string.input_microphone
    },
)

private fun selectedInput(controller: AppController): String? =
    controller.preferences.midiInputDevice?.takeIf { it in controller.inputDevices } ?: controller.defaultDevice(controller.inputDevices)

private fun selectedOutput(controller: AppController): String? =
    controller.preferences.midiOutputDevice?.takeIf { it in controller.outputDevices } ?: controller.defaultDevice(controller.outputDevices)

@Composable
private fun InputSettings(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running

    SettingsSection(stringResource(Res.string.input_source)) {
        RadioSetting(
            options = InputSource.entries,
            selected = preferences.inputSource,
            label = { inputLabel(it) },
            enabled = { !running && (it == InputSource.MIDI || controller.audioUnavailableReason == null) },
            onSelect = controller::setInputSource,
        )
        controller.audioUnavailableReason?.let { SettingHint(it) }
    }
    if (preferences.inputSource != InputSource.MICROPHONE) return

    SettingsSection(stringResource(Res.string.microphone)) {
        val systemDefault = stringResource(Res.string.system_default)
        ChoiceSetting(
            title = stringResource(Res.string.microphone),
            options = listOf<String?>(null) + controller.audioInputDevices,
            selected = preferences.audioInputDevice?.takeIf { it in controller.audioInputDevices },
            label = { it ?: systemDefault },
            onSelect = controller::selectAudioInputDevice,
            onOpen = controller::refreshDevices,
            enabled = !running,
            icon = AppIcons.Mic,
        )
        SwitchSetting(
            title = stringResource(Res.string.headphones),
            checked = preferences.usesHeadphones,
            onCheckedChange = controller::setUsesHeadphones,
            enabled = !running,
            supporting = when {
                preferences.usesHeadphones -> stringResource(Res.string.headphones_on_hint)
                controller.ownSynthName != null -> stringResource(Res.string.headphones_off_hint, controller.ownSynthName!!)
                else -> stringResource(Res.string.headphones_off_hint_plain)
            },
        )
        SwitchSetting(
            title = stringResource(Res.string.octaves),
            checked = preferences.octavesCountAsCorrect,
            onCheckedChange = controller::setOctavesCountAsCorrect,
            enabled = !running,
            supporting = stringResource(Res.string.octaves_hint),
        )
        MicrophoneTest(controller)
    }
}

@Composable
private fun MicrophoneTest(controller: AppController) {
    ButtonRow {
        if (controller.micTesting) {
            OutlinedButton(onClick = controller::stopMicTest) { Text(stringResource(Res.string.stop_test)) }
            val levelDescription = stringResource(Res.string.microphone_level)
            LinearProgressIndicator(
                progress = { (controller.microphoneLevel * 5).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.width(120.dp).semantics { contentDescription = levelDescription },
            )
            val note = controller.micTestNote
            Text(
                if (note == null) {
                    stringResource(Res.string.play_a_key)
                } else {
                    val cents = note.cents.roundToInt()
                    "${NoteNames.displayName(note.midiNote)} (${if (cents >= 0) "+" else ""}$cents ct)"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            OutlinedButton(onClick = controller::startMicTest, enabled = !controller.running) {
                Text(stringResource(Res.string.test_microphone))
            }
        }
    }
    SettingHint(stringResource(Res.string.test_microphone_hint))
}

@Composable
private fun MidiSettings(controller: AppController) {
    val running = controller.running
    val noDevice = stringResource(Res.string.no_device)
    SettingsSection(null) {
        controller.midiUnavailableReason?.let { SettingHint(it, color = MaterialTheme.colorScheme.error) }
        ChoiceSetting(
            title = stringResource(Res.string.midi_in),
            options = controller.inputDevices,
            selected = selectedInput(controller),
            label = { it ?: noDevice },
            onSelect = { it?.let(controller::selectInputDevice) },
            onOpen = controller::refreshDevices,
            enabled = !running,
            icon = AppIcons.MusicNote,
        )
        ChoiceSetting(
            title = stringResource(Res.string.midi_out),
            options = controller.outputDevices,
            selected = selectedOutput(controller),
            label = { it ?: noDevice },
            onSelect = { it?.let(controller::selectOutputDevice) },
            onOpen = controller::refreshDevices,
            enabled = !running,
            icon = AppIcons.Speaker,
        )
        if (controller.canConnectBluetoothMidi) {
            ButtonRow {
                OutlinedButton(onClick = controller::connectBluetoothMidi, enabled = !running) {
                    Text(stringResource(Res.string.bluetooth_midi))
                }
            }
        }
        SettingHint(stringResource(Res.string.devices_hint))
        controller.ownSynthName?.let { SettingHint(stringResource(Res.string.own_synth_hint, it)) }
    }
    SettingsSection(stringResource(Res.string.test_midi)) {
        ButtonRow {
            if (controller.midiTesting) {
                OutlinedButton(onClick = controller::stopMidiTest) { Text(stringResource(Res.string.stop_test)) }
            } else {
                OutlinedButton(onClick = controller::startMidiTest, enabled = !running && controller.inputDevices.isNotEmpty()) {
                    Text(stringResource(Res.string.test_midi))
                }
            }
            OutlinedButton(onClick = controller::playTestNote, enabled = !running && controller.outputDevices.isNotEmpty()) {
                Text(stringResource(Res.string.play_test_note))
            }
        }
        if (controller.midiTesting) {
            val notes = controller.midiTestNotes
            Column(Modifier.padding(horizontal = SettingPadding).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    notes.firstOrNull()?.let { latest ->
                        stringResource(Res.string.midi_test_note, NoteNames.displayName(latest.data1), latest.data1, latest.data2)
                    } ?: stringResource(Res.string.play_a_key),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (notes.size > 1) {
                    Text(
                        stringResource(Res.string.midi_test_before, notes.drop(1).joinToString("  ") { NoteNames.displayName(it.data1) }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingHint(stringResource(Res.string.midi_test_hint))
    }
}

@Composable
private fun TuningSettings(controller: AppController) {
    val reference = controller.preferences.referenceAHz
    SettingsSection(null) {
        SliderSetting(
            title = stringResource(Res.string.reference_a),
            value = reference,
            range = Tuning.MIN_A_HZ..Tuning.MAX_A_HZ,
            step = Tuning.STEP_HZ,
            valueText = stringResource(Res.string.value_hz, reference.toString()),
            onChange = controller::setReferenceA,
            supporting = stringResource(Res.string.tuning_hint),
            fineSteps = true,
        )
        ButtonRow {
            TextButton(onClick = { controller.setReferenceA(Tuning.STANDARD_A_HZ) }, enabled = reference != Tuning.STANDARD_A_HZ) {
                Text(stringResource(Res.string.reset_440))
            }
        }
    }
}
