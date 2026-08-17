# AudioMemo

AudioMemo is a native Android application that records audio, transcribes it using the OpenAI Whisper API, and provides AI-powered summaries. Built with modern Android development practices, it features a sleek intuitive UI and robust background processing capabilities.

## Features

- **Audio Recording:** High-quality voice recording with a real-time audio wavelength visualization UI.
- **Smart Chunking:** Automatically splits long audio recordings into manageable 2-minute chunks and saves them to local storage.
- **AI Transcription:** Integrates with OpenAI's `whisper-1` model to provide accurate transcriptions of recorded audio.
- **Intelligent Summarization:** Uses OpenAI's `gpt-4o-mini` model to generate concise summaries from the transcriptions.
- **Local Storage:** Securely stores audio chunks and transcriptions locally using Room Database.
- **Background Processing:** Ensures seamless transcription and summarization tasks run efficiently in the background using WorkManager.

## Tech Stack

The app is built using the latest Android development technologies and architecture patterns:

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Architecture:** MVVM (Model-View-ViewModel)
- **Concurrency:** Coroutines & Flow
- **Dependency Injection:** Dagger Hilt
- **Networking:** Retrofit2 & OkHttp
- **JSON Serialization:** Kotlinx Serialization
- **Local Database:** Room
- **Background Work:** WorkManager

## Prerequisites

- **Android Studio:** Latest version recommended (Giraffe or newer).
- **Minimum SDK:** API 24 (Android 7.0)
- **Target SDK:** API 34 (Android 14)
- **OpenAI API Key:** Required for transcription and summarization features.

## Getting Started

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   ```

2. **Set up the OpenAI API Key:**
   To securely provide your API key to the application without checking it into version control, add it to your `local.properties` file:

   Open the `local.properties` file in the project's root directory (create it if it doesn't exist) and add the following line:
   ```properties
   OPENAI_API_KEY=your_openai_api_key_here
   ```

3. **Build the project:**
   Sync the project with Gradle files in Android Studio to download all required dependencies.

4. **Run the app:**
   Select an emulator or a physical device and click the Run button in Android Studio.

## Permissions

The app requires the following permissions to function correctly:

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Capture voice recordings (runtime — requested before recording starts) |
| `INTERNET` | Communicate with the OpenAI API |
| `FOREGROUND_SERVICE` | Keep the recording service alive in the background |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14 foreground service type for microphone access |
| `POST_NOTIFICATIONS` | Show the persistent recording notification (API 33+) |
| `READ_PHONE_STATE` | Detect incoming / active calls to pause recording |
| `MODIFY_AUDIO_SETTINGS` | Start Bluetooth SCO for headset microphone support |
| `BLUETOOTH` | Bluetooth headset detection (API ≤ 30) |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart recording after a device reboot, if it was active before shutdown (see "Known Behavior" below) |

## Interruption Handling

AudioMemo is built to survive all common audio interruptions during a recording session. The table below describes every scenario, where it is handled, and what happens.

| Scenario | Status | Mechanism | Behaviour |
|---|---|---|---|
| **Process death** | ✅ Handled | `ChunkFinalizationWorker` (WorkManager) | A 15-second delayed WorkManager job is enqueued when recording starts. If the process dies before the user stops cleanly, the worker fires, marks all in-flight chunks `FAILED`, and re-queues `TranscriptRetryWorker` to retry uploads. |
| **Network failure during upload** | ✅ Handled | `WhisperUploadWorker` + `TranscriptRetryWorker` | `WhisperUploadWorker` uses WorkManager's built-in retry with exponential back-off. `TranscriptRetryWorker` can also re-enqueue failed chunks on demand. |
| **Long recordings** | ✅ Handled | 2-minute chunking + Room | `AudioRecorderManager` splits audio into 2-minute `.m4a` files. Each chunk is saved to Room and uploaded independently, so arbitrarily long sessions are supported without memory pressure. |
| **Audio focus loss** | ✅ Handled | `AudioInterruptionManager` | Requests `AUDIOFOCUS_GAIN` at start. On `AUDIOFOCUS_LOSS` / `AUDIOFOCUS_LOSS_TRANSIENT` / `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` → pauses recording and shows "Paused – Audio focus lost" notification. Resumes automatically on `AUDIOFOCUS_GAIN`. |
| **Phone calls** | ✅ Handled | `AudioInterruptionManager` | Listens via `TelephonyCallback` (API 31+) or `PhoneStateListener` (< API 31). `CALL_STATE_RINGING` / `CALL_STATE_OFFHOOK` → pauses and shows "Paused – Phone call" notification. `CALL_STATE_IDLE` → resumes automatically. Requires `READ_PHONE_STATE` permission. |
| **Android 14 foreground service type** | ✅ Handled | `AndroidManifest.xml` | `AudioRecordingService` is declared with `android:foregroundServiceType="microphone"` and the `FOREGROUND_SERVICE_MICROPHONE` permission, satisfying the Android 14 (API 34) requirement for microphone FGS. |
| **Mic hardware toggle (mute button)** | ✅ Handled | `AudioInterruptionManager` | Registers `AudioManager.ACTION_MICROPHONE_MUTE_CHANGED` (API 27+). When the hardware mic is muted → pauses recording and shows "Paused – Microphone muted" notification. Unmuting → resumes automatically. Initial mute state is checked at start. |
| **One-time permission** | ✅ Handled | `TranscriptScreen` (Compose) | Before starting the foreground service, `RECORD_AUDIO` permission is checked via `ActivityResultContracts.RequestPermission`. The service is only started after the permission is confirmed. The check runs every time the screen is composed, so Android 11+ one-time grants (revoked when the user leaves the app) are correctly re-requested on the next session. |
| **Bluetooth SCO connect/disconnect** | ✅ Handled | `AudioInterruptionManager` | Registers `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED`. On start, if a `TYPE_BLUETOOTH_SCO` input device is already connected, `startBluetoothSco()` is called to route audio through it. `SCO_AUDIO_STATE_CONNECTED` → shows "Microphone switched to Bluetooth headset" notification. `SCO_AUDIO_STATE_DISCONNECTED` / `SCO_AUDIO_STATE_ERROR` → stops SCO and shows "Microphone switched to device microphone" notification. SCO is stopped cleanly when recording ends. |
| **Mic device hot-plug (AudioDeviceCallback)** | ✅ Handled | `AudioInterruptionManager` | Registers `AudioDeviceCallback` (API 23+). When any mic-capable device (wired headset, USB mic/headset, BT SCO, built-in mic) is connected, shows "Microphone switched to \<device\>" notification. When one is removed, falls back to the next available mic input and shows the appropriate notification. |
| **Ghost session on process restart** | ✅ Handled | `SessionStateManager` + `SessionDao` | When `startSession()` is called, `abandonOrphanedSessions()` runs first and promotes any session still in `RECORDING` state to `PAUSED`. This prevents sessions orphaned by a process kill from appearing as live sessions on restart, ensuring `ChunkFinalizationWorker` handles them correctly via crash recovery. |
| **Audio focus not restored after call** | ✅ Handled | `AudioInterruptionManager` | When a call ends (`CALL_STATE_IDLE`) but audio focus was also lost (e.g., the ringtone took focus before the call connected and never returned it), the manager explicitly abandons and re-requests audio focus so the system can re-evaluate and grant it, allowing recording to resume. |
| **Wired headset plug/unplug & noisy audio** | ✅ Handled | `AudioInterruptionManager` | Registers `Intent.ACTION_HEADSET_PLUG` and `AudioManager.ACTION_AUDIO_BECOMING_NOISY`. On plug → shows "Microphone switched to wired \<name\>" notification. On unplug or noisy signal → shows "Microphone switched to device microphone" notification. Sticky broadcasts are ignored to avoid false triggers on registration. |
| **Low battery / Battery Saver mode** | ✅ Handled | `BatteryGuard` | Registers `ACTION_BATTERY_LOW` and `ACTION_POWER_SAVE_MODE_CHANGED`. On either event, recording is stopped cleanly, the transcription chain is enqueued, and a "Recording stopped – Battery too low" notification is shown. |
| **Low storage** | ✅ Handled | `AudioRecorderManager` | `MediaRecorder.OnInfoListener` fires `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`. Recording is stopped, the transcription chain is enqueued, and a "Recording stopped – Low storage" notification is shown. |
| **Microphone hardware error** | ✅ Handled | `AudioRecorderManager` | `MediaRecorder.OnErrorListener` fires on hardware faults, and `prepare()`/`start()` exceptions are caught. Recording is torn down, the transcription chain is enqueued, and a "Recording stopped – Microphone error" notification is shown. |
| **Microphone permission revoked mid-recording** | ✅ Handled | `SilenceDetector` | On every 1-second amplitude poll, `checkSelfPermission(RECORD_AUDIO)` is verified. If the permission has been revoked (possible with Android 11+ one-time grants), recording is stopped cleanly and a "Recording stopped – Microphone permission was revoked" notification is shown. |
| **Headset / media button pause** | ✅ Handled | `MediaButtonHandler` | Registers a `MediaSession` to intercept `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE` (toggle), `KEYCODE_MEDIA_PAUSE`, and `KEYCODE_MEDIA_PLAY`. Shows a "Paused – Headset button" notification. A Resume action in the notification (or a subsequent play key press) resumes recording. |
| **Duplicate service start** | ✅ Handled | `AudioRecordingService` | An `isRecordingActive` in-memory flag rejects re-initialization if `onStartCommand` is called while recording is already in progress (e.g., back-stack re-composition in Compose). The flag resets on process death, so a fresh start after a crash is always accepted. |
| **Wi-Fi-only upload** | ✅ Handled | `UploadPreferences` + WorkManager | A `SharedPreferences`-backed setting controls whether `WhisperUploadWorker` and `TranscriptRetryWorker` are constrained to `NetworkType.UNMETERED` (Wi-Fi only) or `NetworkType.CONNECTED` (any network). |

### Pause flag logic

Multiple interruptions can overlap (e.g., a phone call arrives while audio focus is also lost). `AudioInterruptionManager` tracks three independent boolean flags — `pausedForCall`, `pausedForFocus`, and `pausedForMicMute` — and only calls `onResumeRequested()` when **all three** are clear. A fourth flag, `isMediaButtonPaused` in `AudioRecordingService`, tracks user-initiated headset pauses separately; interruption resumes will not restart the recorder while this flag is set.

## Known Behavior

- **Auto-start after reboot requires one manual launch first.** `BootCompletedReceiver` restarts
  recording automatically after a device reboot, but only when it was already active before the
  shutdown (`recordingShouldBeActive` preference). Since Android 3.1, the system does **not**
  deliver `BOOT_COMPLETED` to a freshly installed app's manifest-registered receiver until the
  user has opened the app manually at least once — this is expected platform behavior, not a bug,
  and there is no app-level workaround for it. In practice: after a fresh install (or a
  reinstall), open the app once before relying on auto-start-after-reboot. (This is a separate,
  older restriction from Android 12's background-foreground-service-start limitation — the one the
  receiver's own `IllegalStateException`/`SecurityException` handling guards against once it *is*
  running.)
- **Supabase session is restored from disk asynchronously.** `gotrue-kt` loads the persisted
  session (access/refresh token) into memory in the background when `SupabaseClient` is created —
  it does not finish before the constructor returns. `Auth.currentSessionOrNull()` is a
  *synchronous* read of whatever is already in memory, so calling it immediately after a fresh
  process start (before that background load finishes) can incorrectly read "not signed in" even
  though a valid session is sitting on disk. Any code that checks auth state right after process
  start — background workers included — must call the SDK's `Auth.awaitInitialization()` first
  and only then read the session. See `SupabaseUploadWorker.doWork()` for the reference fix.

## Architecture Highlights

- **Dependency Injection:** Centralized using Dagger Hilt for components like Networking, Database, and Repositories.
- **Modular Data Sources:** Clean separation between local storage (Room) and remote APIs (Retrofit).
- **Declarative UI:** Completely built using Jetpack Compose with a reactive state management approach using StateFlow.

## License

This project is licensed under the MIT License.
