# TapStop for Android

Language: English | [Deutsch](docs/i18n/README.de.md)

## 1. Project Overview

TapStop is system-wide Android dictation. **Tap → speak → tap → text.** A floating bubble starts and stops recording. TapStop inserts the result at the cursor in the focused editor of another app. If direct insertion fails, TapStop copies the result to the clipboard.

## 2. Features

- Movable accessibility overlay with a four-mode picker.
- Independent speech-to-text (STT) and rewrite provider selection.
- Personal dictionary, local usage statistics, and independently opt-in audio and text diagnostics.
- Experimental on-device transcription on two validated Qualcomm targets in the full build, with a separate model import.

## 3. Screenshots

Planned screenshots cover the dashboard, provider settings, and the four-mode bubble. Public screenshots are pending.

## 4. Speech-to-Text Providers

| Provider | Speech to text | Rewrite in Plus, Chat, and Formal |
| --- | --- | --- |
| OpenAI | `whisper-1` | `gpt-4o-mini`, temperature 0.3 |
| Groq | `whisper-large-v3` or `whisper-large-v3-turbo` | `openai/gpt-oss-120b`, temperature 0.2 for Plus and Formal, 0.4 for Chat |
| Google Gemini, selected manually | `gemini-3.5-transcribe`, verbatim with the `de-DE` language hint | OpenAI or Groq, selected separately |
| Local, Qualcomm NPU | Whisper Large V3 Turbo FP16 on device | OpenAI or Groq, selected separately |

STT and rewrite can be combined independently, for example Gemini transcription with Groq rewriting. Automatic rewrite follows the selected STT provider for OpenAI and Groq. For Gemini or Local, it uses the most recent OpenAI or Groq STT choice. New profiles start with OpenAI. TapStop fixes the STT provider when recording starts and reads mode and rewrite settings when recording stops.

Gemini sends the original M4A through the Google Interactions API with `store=false`. If the Base64-encoded request including JSON exceeds 20 MB, TapStop uploads the audio through the Files API and attempts to delete it after success, failure, or cancellation. A lost connection can prevent deletion. Google automatically deletes uploaded files after 48 hours. Gemini accepts recordings up to one hour. For Gemini dictation the personal dictionary is used during OpenAI or Groq rewriting. [Transcription](https://aistudio.google.com/docs/transcribe) · [Files API](https://aistudio.google.com/docs/files)

## 5. Local Speech Recognition

The experimental Local provider uses the Qualcomm NPU. Select **Modellordner importieren** in settings and choose the model package for your target. The roughly 2.2 GB package is separate from the APK. The importer verifies the target, runtime versions, sizes, and hashes. Prepare the model before dictation and allow additional time for the first initialization. Local needs no API key.

- **Local + Roh (Raw)** keeps recognition and text processing on the device. TapStop sends neither audio nor transcript to a cloud provider.
- **Local + Plus, Chat, or Formal** transcribes on device and sends the resulting text to the configured cloud rewrite provider.

Local stops automatically after 30 seconds. Tap again to end a shorter recording. This cap applies only to Local. The source-only build hides the Local provider and its model importer. [Qualcomm backend and model import](docs/QUALCOMM_LOCAL_STT.md) · [Qualcomm license inventory](docs/QUALCOMM_LICENSING.md)

## 6. Text Processing Modes

- **Roh (Raw)** inserts the STT transcript without a rewrite request.
- **Plus** corrects spelling, punctuation, and clear grammar errors and removes pure hesitation sounds. It aims to preserve word choice, sentence order, voice, and meaning.
- **Chat** turns the dictation into a natural, informal message with occasional appropriate emoji.
- **Formal** uses a factual style and paragraphs, without emoji.

Press and hold the idle bubble for **480 ms** to open the picker. Tap a mode to save it or drag the bubble to move it. Mode and position persist across app restarts.

## 7. Installation

TapStop requires Android 10 or later. The latest published APK and its release notes are listed on [GitHub Releases](https://github.com/krisauseu/tapstop-android/releases). The v0.12 full release candidate is named `TapStop-Android-0.12.apk`.

Version 0.12 uses application ID and Kotlin namespace `de.kf.tapstop`, version code `12`, and a dedicated release certificate. Android installs it alongside `de.kf.blitztext` from v0.11. Keep the older installation while checking the new one. Later updates for `de.kf.tapstop` require a compatible signing identity and use the same app data area. See the [migration guide](RELEASING.md#package-migration-from-v011).

Version 0.11 moved the bubble and picker to `TYPE_ACCESSIBILITY_OVERLAY`. Version 0.10 added Gemini 3.5 Transcribe and separate STT and rewrite selection.

## 8. Configuration

1. Select an STT provider in **Einstellungen**. Save the matching API key for OpenAI, Groq, or Gemini. For Local, import and prepare the matching model.
2. Choose a Groq STT model if applicable. For Plus, Chat, or Formal, configure OpenAI or Groq for rewriting.
3. Grant microphone access and enable **TapStop Texteingabe** in Android accessibility settings.
4. Start the bubble from the visible TapStop app. Focus an editor in another app, tap the bubble, speak, and tap again. The bubble turns red while recording and blue while processing.

The personal dictionary accepts one term or expression per line, up to 200 entries or 8 KiB. OpenAI and Groq STT use entries from the beginning of the list up to the prompt limit. With Gemini and Local, the dictionary is used during cloud rewriting. [Personal dictionary](docs/PERSONAL_DICTIONARY.md)

For v0.12, reselect providers and modes, enter keys again, copy dictionary entries manually, and position the new bubble. Reimport models from the original model folder. The old statistics and private diagnostic files remain in the old app's storage.

## 9. Permissions and Accessibility

TapStop uses microphone access, a microphone foreground service, vibration, network access, and notifications on supported Android versions. The accessibility service draws the bubble and mode picker, identifies the focused editor, and inserts text via InputConnection or an accessibility text action. The clipboard fallback covers editors where direct insertion fails. Since v0.11 the bubble uses the accessibility overlay permission path and needs no separate **Display over other apps** grant.

If accessibility disconnects, the bubble and picker disappear. If the microphone service is still running, the bubble returns when accessibility reconnects. Use **Blase beenden** to stop the service even when accessibility is disconnected. After a reboot or after Android stops the microphone service, restart the bubble from the visible app.

## 10. Privacy and Data Handling

- OpenAI, Groq, and Gemini keys are stored separately using AES-GCM with Android Keystore and are retained when switching providers within one installation. Android isolates the old package's Keystore and private files from v0.12.
- Cloud STT sends the recording to the selected provider. Cloud rewriting sends the transcript and applicable dictionary context. TapStop has no automatic cloud fallback.
- Temporary audio lives in private cache and is deleted after processing. TapStop disables Android backup and explicitly excludes app data from cloud backup and device transfer on Android 12 and later.
- **Temporäre Insert-Textdiagnose** captures transcripts, output, and later checks of inserted text. **Originalaudio für Diagnose speichern** retains input audio and associated text. Both switches are off on a fresh installation. With both off, TapStop writes no additional audio, text, or insertion diagnostics. Turning them off preserves existing files. Usage statistics run independently. These local diagnostics are available in release builds. ADB extraction via `run-as` requires a debuggable build. [Audio and STT](docs/AUDIO_STT_DIAGNOSTICS.md) · [Text and readbacks](docs/INSERT_DELAYED_DIAGNOSTICS.md) · [Insertion metadata](docs/INSERT_DIAGNOSTICS.md)

## 11. Usage Statistics

TapStop opens the dashboard. **Today**, **Week** (Monday–Sunday), **Month**, and **All** use the device's local time zone. Wide screens show the four main metrics side by side. The all-time heatmap covers the last 53 weeks, and totals use the entire available history. Daily activity bands are 0, 1–99, 100–499, and at least 500 raw words.

**Words** counts raw STT words. **Average WPM** divides total raw words by total recording minutes, including silence. Unicode letter and digit groups count as words, including internal hyphens and apostrophes. Punctuation and standalone emoji are excluded. Final word counts are also tracked and broken down by mode.

Successful processing counts even if TapStop supplies the text through the clipboard fallback. TapStop cannot measure a later manual paste. The success flag tracks processing, and target-app acceptance is measured separately where possible. The Android monotonic clock measures STT around the transcription call, rewrite around the rewrite call, and total latency from recording stop to final text. Failed attempts store only available metrics, with incomplete steps set to `NULL`. They are excluded from usage totals and latency medians.

The provider/model comparison reports **× real time** as the sum of recording durations divided by the sum of STT durations for successful dictations with positive values in both fields. For example, 30 seconds of audio and 1 second of STT yield 30×. A dash indicates no measurable data. Absolute STT time remains visible as a median. Local is listed separately as **Qualcomm NPU / Whisper Large V3 Turbo** (`qualcomm` / `whisper-large-v3-turbo`). Model preparation time is excluded from STT latency.

The private SQLite database `usage.db` uses schema version 1, table `dictations`, and index `dictations_timestamp(timestamp)`.

| Column | Type and meaning |
| --- | --- |
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `timestamp` | INTEGER, Unix milliseconds at the end of recording |
| `recording_ms` | INTEGER, actual recording duration |
| `raw_words`, `final_words` | INTEGER, nullable |
| `provider`, `model`, `mode` | TEXT, actual STT configuration and mode |
| `target_package` | TEXT, nullable when unknown |
| `stt_ms`, `rewrite_ms`, `total_ms` | INTEGER, nullable |
| `success` | INTEGER, 0 or 1 |

SQLite work runs on background threads. Statistics use no additional permission, network connection, or telemetry. The target package comes from the accessibility node selected for insertion. PackageManager resolves labels for visible apps via a launcher intent query, with the package ID as fallback. TapStop neither requests `QUERY_ALL_PACKAGES` nor stores an app inventory. Resetting statistics deletes only statistic rows after confirmation.

## 12. Building from Source

Use JDK 17 (`JAVA_HOME`), Android SDK Platform 36, and the checked-in Gradle wrapper. Open the project in Android Studio or run:

```sh
./gradlew :app:assembleDebug :app:lint :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

The default build uses free source and dependencies, supports all three cloud providers, and produces `app/build/outputs/apk/debug/app-debug.apk`. For the full build, pass `-PqualcommRuntimeDir=/absolute/path/to/runtime` with the pinned VoiceAI and QAIRT bundle. `packageTapStop` builds the signed full release with external credentials. [Release and signing procedure](RELEASING.md) · [Qualcomm staging tools](tools/qualcomm-whisper/README.md)

JVM and Robolectric cover gestures, the mode picker, persistence, Raw without rewrite, request models, temperatures, exact prompts, model import and target mapping, runtime lifecycle, provider separation, and Local statistics. Separate device instrumentation uses a disposable database and tests word counting, medians, weighted WPM, failure metrics, period boundaries, schema, persistence, reset, native InputConnection, and clipboard fallback. The runner rebinds the enabled TapStop accessibility service and preserves other enabled services. [Device tests](DEVICE_TESTS.md) · [Insertion tests](INSERT_DEVICE_TESTS.md) · [Running instrumentation](RELEASING.md#device-tests)

## 13. Device Compatibility

The historical device acceptance used actual microphone dictation on these Qualcomm targets.

| SoC and HTP target | Tested device |
| --- | --- |
| Snapdragon 8 Gen 3 · SM8650 · HTP V75 | Samsung Galaxy S24 Ultra (SM-S928B) |
| Snapdragon 8 Gen 5 · SM8845 / SM8845P · HTP V81 | HONOR MagicPad 4 (YLE-W09) |

TapStop detects the SoC automatically. Other devices with a matching target are marked as untested. The full build disables Local on unknown targets. The device records cover Raw, Plus with Groq, insertion, statistics, and HTP execution evidence. Research app results and testing on recent devices leave separate acceptance work for the integrated build and for Android 10–12. [Device acceptance records](DEVICE_TESTS.md)

## 14. Known Limitations

- Local is limited to the listed validated devices and targets. General Snapdragon support has yet to be established. Dictation over 30 seconds through chunking is a future development item.
- Some recordings near 30 seconds fail with VoiceAI `SPECTROGRAM FAIL`. TapStop reports an error, inserts no partial text, and skips cloud rewriting. A shorter retry can reuse the loaded model.
- Release 0.12 starts with a separate Android data area. Cloud keys, stats, models, and accessibility authorization require separate handling.
- The free build is intended for future F-Droid review and requires a user-supplied account or key for external STT services. [F-Droid considerations](RELEASING.md#future-f-droid-integration)

## 15. Contributing

Include the build variant, Android version, and reproduction steps in reports. Keep keys, recordings, transcripts, private diagnostics, SDK binaries, and model files out of commits and issues. For new Qualcomm targets, include on-device encoder and decoder execution evidence. Update this English README and its German translation together.

## 16. License and Third-Party Notices

The project license text for TapStop's own source is Apache License 2.0. See the release checklist for confirmation of rights before public distribution. TapStop is an independent Android implementation inspired by Christoph Magnussen's open-source macOS app Blitztext. Its MIT attribution is preserved. AndroidX and other dependencies retain their respective licenses. [Apache License 2.0](LICENSE) · [Blitztext MIT notice](THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt)

The full APK includes proprietary Qualcomm VoiceAI and QAIRT object code under the applicable SDK terms, together with the unchanged notices. Model weights and target-specific compiled packages have separate terms and are imported independently. The repository contains the integration code and tools. [Qualcomm license inventory](docs/QUALCOMM_LICENSING.md)
