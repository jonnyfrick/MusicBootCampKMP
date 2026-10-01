package io.github.jonnyfrick.musicbootcamp

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import io.github.jonnyfrick.musicbootcamp.android.androidServices
import io.github.jonnyfrick.musicbootcamp.ui.App
import io.github.jonnyfrick.musicbootcamp.ui.AppController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The controller lives as long as the activity; rotating or resizing does not recreate it (see
 * `configChanges` in the manifest), so a running exercise keeps running. Leaving the app stops
 * the exercise and the tests: Android silences the microphone of apps in the background anyway.
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: AppController

    private var permissionAnswer: CompletableDeferred<Boolean>? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionAnswer?.complete(granted)
        permissionAnswer = null
    }

    /** Asks for the microphone; the answer comes back through [microphonePermission]. */
    private suspend fun requestMicrophone(): Boolean {
        val answer = CompletableDeferred<Boolean>().also { permissionAnswer = it }
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        return answer.await()
    }

    private var pickedFiles: CompletableDeferred<List<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickedFiles?.complete(uris)
        pickedFiles = null
    }

    /** Lets the user choose files (several at once) with the system's file dialog; empty if cancelled. */
    private suspend fun pickFiles(): List<Uri> {
        val answer = CompletableDeferred<List<Uri>>().also { pickedFiles = it }
        filePicker.launch(arrayOf("text/xml", "application/xml", "*/*"))
        return answer.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = AppController(androidServices(this, ::requestMicrophone, ::pickFiles), scope)

        // The screen stays on while an exercise runs: the player has their hands on the piano.
        scope.launch {
            snapshotFlow { controller.running || controller.calibrating }.collect { busy ->
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent { App(controller) }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        controller.stopPractice()
        controller.stopMicTest()
        controller.stopMidiTest()
        controller.stopCalibration()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations) return
        // Saves what is still pending and closes the devices, then ends the scope.
        scope.launch {
            withContext(NonCancellable) { controller.shutdown() }
            scope.cancel()
        }
    }
}
