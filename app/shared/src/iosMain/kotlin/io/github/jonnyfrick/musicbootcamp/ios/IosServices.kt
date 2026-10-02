@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.jonnyfrick.musicbootcamp.ios

import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.platform.BluetoothMidiConnector
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreAudioKit.CABTMIDICentralViewController
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UINavigationController
import kotlin.experimental.ExperimentalNativeApi

/**
 * Services of the iOS app: setups in the app's Application Support folder, MIDI through CoreMIDI
 * (USB with an adapter, network sessions, Bluetooth after connecting it here), the microphone and
 * the app's own piano through one `AVAudioEngine`.
 */
@OptIn(ExperimentalNativeApi::class)
fun iosServices(): PlatformServices {
    val audio = IosAudioEngine()
    val piano = IosPianoSynth(audio)
    return PlatformServices(
        midi = IosMidiBackend(piano),
        documents = FileDocumentStore(dataDirectory()),
        legacyFiles = null,
        audio = IosAudioInput(audio),
        renderedSynth = piano,
        bluetoothMidi = IosBluetoothMidi(),
        // Debug builds from Xcode have the developer tools; there is no recording store on iOS yet.
        debugTools = Platform.isDebugBinary,
    )
}

private fun dataDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    val directory = "$base/MusicBootCamp"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    return directory
}

/** One file per document; Foundation writes through a temp file and a rename ("atomically"). */
private class FileDocumentStore(private val directory: String) : DocumentStore {
    override suspend fun read(name: String): String? = withContext(Dispatchers.Default) {
        NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)
    }

    override suspend fun write(name: String, content: String): Unit = withContext(Dispatchers.Default) {
        val written = NSString.create(string = content).writeToFile(path(name), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!written) throw IllegalStateException("Could not save $name")
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), null)
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)?.map { it as String }?.sorted() ?: emptyList()
    }

    private fun path(name: String): String {
        require('/' !in name && name != "..") { "Invalid document name $name" }
        return "$directory/$name"
    }
}

/**
 * Apple's own dialog for Bluetooth LE MIDI devices: it lists the devices nearby and connects the
 * one tapped, which then appears among the CoreMIDI devices. Swiping the sheet down closes it.
 */
private class IosBluetoothMidi : BluetoothMidiConnector {
    override suspend fun connect() = withContext(Dispatchers.Main) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: throw IllegalStateException("The app has no window to show the dialog in.")
        val navigation = UINavigationController(rootViewController = CABTMIDICentralViewController())
        navigation.modalPresentationStyle = UIModalPresentationPageSheet
        root.presentViewController(navigation, animated = true, completion = null)
    }
}
