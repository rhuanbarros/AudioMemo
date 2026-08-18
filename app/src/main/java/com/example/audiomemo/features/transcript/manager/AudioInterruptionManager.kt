package com.example.audiomemo.features.transcript.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Handles audio source-change observability and (am-hotfix, never-stop-recording) phone-state
 * observability. Recording itself is never paused/stopped by anything in this class anymore —
 * per the owner's permanent rule ("a gravação nunca deve desistir por escolha própria"), audio
 * focus loss, microphone hardware mute, and phone calls are all *policy*, never a real Android
 * restriction that actually makes the microphone unavailable, so none of them pause recording:
 *  1. Phone call (RINGING / OFFHOOK / IDLE) → logged for observability only, never pauses. If the
 *     Android system genuinely revokes microphone access during a call (rare, OEM-dependent),
 *     that surfaces as a real `MediaRecorder` hardware error instead, which
 *     [AudioRecordingService] now recovers from immediately rather than treating as an
 *     intentional pause.
 *  2. Wired headset plug/unplug and ACTION_AUDIO_BECOMING_NOISY → source-changed notification
 *  3. Bluetooth SCO connect/disconnect → source-changed notification + SCO lifecycle
 *  4. USB audio device attach/detach → source-changed notification
 *
 * Audio focus requests and microphone-hardware-mute detection were removed entirely (not just
 * disabled) — see `am-hotfix-never-stop-recording.md`'s Code Map. Neither one is a real Android
 * restriction: another app taking audio focus never makes the microphone physically unavailable,
 * and a muted mic just means the captured audio is quiet (the existing silent-chunk skip already
 * handles that case), not that recording should stop.
 */
class AudioInterruptionManager(
    private val context: Context,
    private val onSourceChanged: (sourceName: String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // ── Phone State (observability only — never pauses recording) ─────────────

    private var legacyPhoneListener: PhoneStateListener? = null
    private var modernPhoneCallback: TelephonyCallback? = null

    private fun registerPhoneStateListener() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModernCallback()
        } else {
            registerLegacyListener()
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handleCallState(state)
        }
        modernPhoneCallback = cb
        telephonyManager.registerTelephonyCallback(context.mainExecutor, cb)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerLegacyListener() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                handleCallState(state)
        }
        legacyPhoneListener = listener
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    /**
     * (am-hotfix, never-stop-recording): deliberately logs only, never pauses. A phone call is a
     * real Android restriction ONLY in the rare case the system yanks microphone access away from
     * this app during the call — and that surfaces as a genuine `MediaRecorder` hardware error
     * (via [AudioRecorderManager.onHardwareError]), which [AudioRecordingService] already recovers
     * from immediately. This listener stays registered purely so call-state transitions are
     * visible in logcat while investigating recording behavior, per the story's Code Map
     * ("o listener de TelephonyManager pode continuar existindo só pra fins de log/observabilidade").
     */
    private fun handleCallState(state: Int) {
        val label = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN($state)"
        }
        Log.d(TAG, "Phone call state changed to $label — recording continues uninterrupted")
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernPhoneCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
                modernPhoneCallback = null
            }
        } else {
            legacyPhoneListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                legacyPhoneListener = null
            }
        }
    }

    // ── Headset / Bluetooth source changes ────────────────────────────────────

    private var headsetReceiverRegistered = false

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    onSourceChanged("device microphone")
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    if (isInitialStickyBroadcast) return
                    val state = intent.getIntExtra("state", -1)
                    val name = intent.getStringExtra("name") ?: "headset"
                    when (state) {
                        1 -> onSourceChanged("wired $name")
                        0 -> onSourceChanged("device microphone")
                    }
                }
            }
        }
    }

    private fun registerHeadsetReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(headsetReceiver, filter)
        }
        headsetReceiverRegistered = true
    }

    // ── Bluetooth SCO ──────────────────────────────────────────────────────────

    private var scoReceiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: return
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED ->
                    onSourceChanged("Bluetooth headset")
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    audioManager.stopBluetoothSco()
                    onSourceChanged("device microphone")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerScoReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
        scoReceiverRegistered = true
        // Start SCO if a Bluetooth SCO input device is already connected
        val hasBtSco = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (hasBtSco) {
            audioManager.startBluetoothSco()
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterScoReceiver() {
        if (scoReceiverRegistered) {
            try { context.unregisterReceiver(scoReceiver) } catch (_: Exception) {}
            scoReceiverRegistered = false
        }
        audioManager.stopBluetoothSco()
    }

    // ── USB audio device attach/detach ─────────────────────────────────────────

    private var usbReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Only notify if the newly attached device has an audio input
                    val hasUsbInput = audioManager
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .any {
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                    if (hasUsbInput) onSourceChanged("USB microphone")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // If the detached device was an audio input, fall back to device mic
                    val stillHasUsbInput = audioManager
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .any {
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                    if (!stillHasUsbInput) onSourceChanged("device microphone")
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // USB attach/detach are system broadcasts — RECEIVER_NOT_EXPORTED is fine.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (usbReceiverRegistered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
    }

    // ── Audio device add/remove (API 23+) ─────────────────────────────────────

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val micDevice = addedDevices.firstOrNull { it.isMicInput() }
            if (micDevice != null) onSourceChanged(micDevice.toSourceName())
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val micRemoved = removedDevices.any { it.isMicInput() }
            if (micRemoved) {
                val remaining = audioManager
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.isMicInput() }
                onSourceChanged(remaining?.toSourceName() ?: "device microphone")
            }
        }
    }

    private fun AudioDeviceInfo.isMicInput(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> false
    }

    private fun AudioDeviceInfo.toSourceName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC   -> "device microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET   -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE    -> "USB microphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        else -> productName?.toString()?.takeIf { it.isNotBlank() } ?: "external microphone"
    }

    private fun registerAudioDeviceCallback() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun unregisterAudioDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        registerPhoneStateListener()
        registerHeadsetReceiver()
        registerScoReceiver()
        registerUsbReceiver()
        registerAudioDeviceCallback()
    }

    fun stop() {
        unregisterPhoneStateListener()
        if (headsetReceiverRegistered) {
            try { context.unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
            headsetReceiverRegistered = false
        }
        unregisterScoReceiver()
        unregisterUsbReceiver()
        unregisterAudioDeviceCallback()
    }

    private companion object {
        private const val TAG = "AudioInterruptionMgr"
    }
}
