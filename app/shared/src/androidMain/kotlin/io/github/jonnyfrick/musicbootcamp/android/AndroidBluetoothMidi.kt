package io.github.jonnyfrick.musicbootcamp.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import io.github.jonnyfrick.musicbootcamp.platform.BluetoothMidiConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bluetooth LE MIDI on Android. The system's companion-device dialog looks for devices offering
 * the MIDI service and lets the user choose one, so the app needs no location or scan permission,
 * only the one to connect (Android 12+). [launchChooser] shows that dialog and returns its result.
 */
class AndroidBluetoothMidi(
    private val context: Context,
    private val midi: AndroidMidiBackend,
    private val requestPermission: suspend (String) -> Boolean,
    private val launchChooser: suspend (IntentSender) -> Intent?,
) : BluetoothMidiConnector {

    override suspend fun connect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw IOException("Bluetooth MIDI needs Android 8 or later.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !requestPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw IOException("Without the permission for nearby devices the app cannot connect.")
        }
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: throw IOException("This device cannot look for Bluetooth devices.")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(MIDI_SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()

        val chooser = suspendCancellableCoroutine<IntentSender> { continuation ->
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    // Before Android 13 this is called; from 13 on onAssociationPending, whose
                    // default implementation calls this one.
                    @Deprecated("Deprecated in Java")
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        if (continuation.isActive) continuation.resume(chooserLauncher)
                    }

                    override fun onFailure(error: CharSequence?) {
                        if (continuation.isActive) continuation.resumeWithException(IOException(error?.toString() ?: "No device found."))
                    }
                },
                null,
            )
        }
        val result = launchChooser(chooser) ?: return // cancelled
        val device = chosenDevice(result) ?: throw IOException("The chosen device is not available.")
        withContext(Dispatchers.IO) { midi.openBluetooth(device) }
    }

    @Suppress("DEPRECATION")
    private fun chosenDevice(result: Intent): BluetoothDevice? {
        when (val extra = result.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> return extra.device
            is BluetoothDevice -> return extra
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val association = result.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            return association?.associatedDevice?.bleDevice?.device
        }
        return null
    }

    companion object {
        /** The Bluetooth LE service every MIDI device offers (MIDI over Bluetooth LE specification). */
        const val MIDI_SERVICE = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700"

        /** Whether this device can use Bluetooth LE MIDI at all. */
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)
    }
}
