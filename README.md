# ZQ Settle

ZQ Settle is an Android app that helps you prepare for a difficult conversation in a few short, optional steps: a quiet pause, guided breathing, a brief camera-off rehearsal and a conversation card you write in your own words. An optional camera check-in uses the **Presage SmartSpectra Android SDK 3.4.0** to show a pulse estimate during the steps. Every step also works on the timer alone, without the camera.

> **Not a medical device.** ZQ Settle is a personal preparation tool. It does not diagnose, treat, monitor or assess any health condition, and it does not assess stress, emotions, confidence or readiness. Any pulse estimate shown is for general, non-medical information only. Do not use it to make health decisions.

Built for the Presage SmartSpectra SDK challenge (#presagechallenge).

## Demo

* Demo video (in this repository): [`evidence/video/zq-settle-demo-c55-0034-blurred.mp4`](evidence/video/zq-settle-demo-c55-0034-blurred.mp4) — 1:52, no audio, camera preview blurred for privacy. Recorded on a Realme C55 (Android 15) during a 90 s Guided breathing step. **Live SDK pulse shown, with frame-rate warning** (the camera ran at 24–25 fps); this clip is not by itself a 60-second pass.
* Online video: `[DEMO VIDEO LINK – to be added]`

## Requirements

* **JDK 17** (Android Studio bundles one; or set `JAVA_HOME` to a JDK 17).
* **Android SDK** with Platform 36 (compileSdk/targetSdk 36) and Build-Tools; minSdk 28.
* Gradle is provided by the wrapper (`gradlew`, Gradle 8.11.1); Android Gradle Plugin 8.10.1, Kotlin 2.2.10.
* **Device:** an Android 9+ (API 28+) phone with a front camera. The camera check-in needs a network connection and works best with a sustained camera rate of about 25–30 fps, bright even light, and face and upper chest in frame.
* **Optional:** your own Presage API key from the Presage Developer Portal, only if you want the camera check-in. Without one the app builds and runs timer-only.

## Build

```bash
# macOS / Linux
./gradlew assembleDebug
# Windows (PowerShell)
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` (ignored by git). A keyless release build: `./gradlew assembleRelease` (unsigned).

Or open the repository root (the folder with `settings.gradle.kts`) in Android Studio, let Gradle sync, and press *Run*.

## Tests

```bash
./gradlew testDebugUnitTest
```

JVM unit tests cover the rules, consent versioning and 18+ gate, camera gate, live-signal state and freshness rules, storage, encrypted backup and the step clock.

## Presage key: local setup

The key is **never** committed. It is read at build time from one of two places, in this order:

1. `PRESAGE_API_KEY` in the gitignored `local.properties` file:
   ```bash
   cp local.properties.example local.properties
   # then edit local.properties and replace the placeholder with your own key
   ```
2. The `PRESAGE_API_KEY` environment variable:
   ```bash
   PRESAGE_API_KEY=your-own-key ./gradlew assembleDebug
   ```

The placeholder value in `local.properties.example` is treated as "no key".

* Only **debug** builds can carry a key. **Release** builds always have an empty key (`BuildConfig.PRESAGE_API_KEY = ""`).
* **A key built into an APK may be extractable. It is not secure.** Keep keyed APKs private, never share or publish them, and never show the key in screenshots or recordings.

### Keyless behaviour

With no key (the default for anyone cloning this repository), the build succeeds and the app runs timer-only:

* the SDK is never initialised and the camera is never requested;
* Home and the step screens say: *"Camera check-in isn't available in this build (no Presage key is configured). Every step runs on the timer only."*;
* every step (quiet pause, breathing, rehearsal, conversation card, timeline, settings) works normally.

### Live-signal indicator

The app only says a camera check-in is running, or shows a pulse estimate, when the SmartSpectra SDK itself reports `RUNNING` and has delivered a fresh metrics update (within 5 seconds, SDK confidence above zero, 30–220 BPM). In timer-only mode, or when the SDK reports an error, the screen shows *"Timer only - no live signal"* or *"Camera check-in unavailable right now. The timer keeps going."* The timer never produces a live indicator.

## How the camera check-in works

* The app owns the front camera with CameraX and feeds frames to the SDK through its public custom-input API (`useCustomInput(FrameTransform.NONE)` and `sendFrame`), requesting 640×480 at a fixed 30 fps auto-exposure range. The delivered frame rate is logged every ~5 s (counts and sizes only).
* The SDK's breathing and cardio metric sets are requested; by default only a pulse estimate is shown.
* Camera frames are processed on the phone by the SDK. ZQ Settle does not record, save or upload video or images. The on-screen preview is display-only and can be hidden.
* The camera stops when a step ends, is skipped or paused, or the app goes to the background. Rehearsal is camera-off.

## Consent and Data Handling

The consent screen shows three points before any camera use (consent notice version 3):

1. **Not medical, adults only.** Personal preparation only, not a medical device. The camera check-in is for adults 18+, confirmed by the user before Accept is enabled.
2. **Camera and data.** The camera stays off until the user accepts and then taps *Allow camera*. Images are processed on the phone and never recorded, saved or uploaded by ZQ Settle. During a check-in the SDK sends Presage derived measurements, session and usage records, app/device authentication data (including hashed device identifiers) and the IP address. SDK diagnostic telemetry is off (`enableTelemetry = false`); per Presage's policy this does not stop authentication, model delivery or Insights.
3. **AI use and retention.** See below.

Declining keeps every step available on the timer. Consent can be withdrawn at any time in Settings.

* **Permissions:** `CAMERA` (requested only after consent, 18+ confirmation and a tap on *Allow camera*) and `INTERNET` (used only by the SmartSpectra SDK). A library dependency adds `ACCESS_NETWORK_STATE`. No microphone permission.
* **No account, analytics, crash reporting or advertising.**
* **Backup:** Android/Google system backup is disabled. The only backup is an optional, passphrase-encrypted file the user chooses to create and save; export (unencrypted) and restore happen only when the user acts.
* **Conversation text:** the opening, request and card exist only in memory for the current session and are never stored, backed up or exported. The card offers Copy only.

### AI-use disclosure

* ZQ Settle itself uses **no AI**. The conversation opening is filled from fixed local templates on the phone, with no network call.
* Presage's current SmartSpectra SDK privacy policy states that Presage automatically generates **AI Insights** from SDK measurements using Anthropic. ZQ Settle supplies no prompt text and does not show or store those Insights. Whether SDK 3.4.0 triggers Insights has **not** been verified; the app discloses the policy rather than claiming either way.
* AI coding assistants helped generate and review portions of this project. Final product decisions, testing, and release approval remain with Zubin Qayam.

### Retention statement

* Settings, the session timeline and the consent choice are kept only in the app's private storage on the phone, until the user deletes them (*Delete all*) or withdraws consent, which deletes stored data on the phone. Uninstalling the app also removes them.
* Camera images are never stored by ZQ Settle.
* Deleting data in ZQ Settle does **not** delete data held by Presage or files the user exported. Data sent to Presage is retained under Presage's own privacy policy; contact Presage about it.

## Code map

| Area | File |
|---|---|
| Defaults and rules | `app/src/main/java/com/zq/commprep/domain/Rules.kt` |
| Data model, versioned consent (`ConsentPolicy`), export/backup JSON | `domain/Model.kt` |
| Local storage (DataStore), delete all, withdraw consent, restore | `data/AppRepository.kt` |
| Passphrase encryption (PBKDF2-HMAC-SHA256 → AES-256-GCM) | `backup/BackupCrypto.kt` |
| SmartSpectra 3.4.0 wrapper + timer-only `NoOpHeartRateSource` | `sensors/HeartRateSource.kt` |
| Camera/consent gate | `domain/CameraGate.kt` |
| Live-signal state, freshness rule, plain status | `domain/LiveSignal.kt`, `ui/LiveSignalPanel.kt` |
| Consent, Home, Timeline, Settings | `ui/App.kt` |
| Preparation journey and conversation card | `domain/OpeningBuilder.kt`, `ui/PrepScreens.kt` |
| Pause, breathing and rehearsal screens | `ui/StepScreens.kt` |
| Unit tests | `app/src/test/java/com/zq/commprep/` |

The Android package and class names (`com.zq.commprep`, `CommPrepApp`) are kept from the project's working name; the app shows as **ZQ Settle** everywhere a user can see.

## Status

Version `0.2.0-prep2-fpsgate-lowres` (versionCode 8). The camera check-in is an **experimental**, non-medical informational signal. On one test phone (Realme C55, Android 15) a 90 s Guided-breathing run on 22 Nov 2026 kept the SDK running for 88 s with about 66 s of continuous genuine SDK pulse and no crash, with the SDK's frame-rate warning (24.6–25.0 fps). It worked under the recorded conditions; it has not been tested across devices. See [Evidence](#evidence).

## Evidence

Redacted test evidence is in [`evidence/`](evidence/INDEX.md): the C55 test report, SDK event logs (tag `SETTLE_SDK` only), a redacted screenshot, the blurred demo video and start-date evidence. No APKs, keys, device serials or unblurred images are included.

## Licence

The original ZQ Settle source code is licensed under the **Apache License 2.0**, Copyright 2026 Zubin Qayam (see `LICENSE` and `NOTICE`).

The Apache licence does **not** apply to the Presage SmartSpectra SDK or other third-party dependencies, which are downloaded at build time and remain under their own licences and terms. Using the SDK requires your own Presage API key and acceptance of Presage's terms.
