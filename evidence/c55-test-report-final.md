# ZQ Settle — C55 combined test report (final)

**Redacted test evidence, published with Zubin Qayam's approval (23 Nov 2026). Zubin Qayam is the sole Release Authority. Nothing in this report is approval to release, and this report does not say the build is ready to release.**

Written 23 Nov 2026, 00:00 Asia/Muscat, from a read-only review of the 22 Nov 2026 C55 run. Labels: **device-verified** / **file-verified** / **reported** / **pending**.

Revised 23 Nov 2026, 04:03 Asia/Muscat: wording clarification only. The 00:29 and 00:34 runs were added after the original 00:00 review. The 60-second threshold used in this report is Zubin Qayam's internal QA bar, not a contest rule.

## Verdict in one paragraph

There was **one** SmartSpectra SDK session on the C55, not two. The guided-breathing screen in screenshot `1004113800.jpg` (clock 23:4x, "Pace 5/5 · 22 s left", pulse 54 bpm) is mid-run of that same session. `start_requested` at 23:47:06.349 to `stop_requested` at 23:48:36.337 is 89.988 s, which matches the app's 90 s guided-breathing step at pace 5/5. SDK `RUNNING` lasted 88.052 s (23:47:08.285–23:48:36.337). Continuous genuine SDK pulse ran for 66.194 s (first logged `pulseSamples>0` at 23:47:30.143 through `stop_requested`). No crash, `NETWORK_ERROR` or `PROCESSING_FAILED`. The on-screen wording was "Camera check-in running." with a fresh pulse estimate, not the timer fallback. Against the criterion "at least 60 s of genuine SDK signal, not the timer fallback, with no crash", the run meets our internal 60-second QA bar, with `FRAME_RATE_TOO_LOW` recorded as a **warning, not a fail**. Stage 2 is not a separate measurement; it is the same session viewed mid-run, so it does not get a second independent pass.

## Device and build

| Item | Value | Label |
|---|---|---|
| Device | Realme RMX3710 (realme C55), Android 15 (SDK 35), abi `arm64-v8a` (`abilist=arm64-v8a,armeabi-v7a,armeabi`) | device-verified (getprop over adb from the developer laptop, 22 Nov 2026 23:54 Asia/Muscat). Device serial not recorded. |
| Installed package | `com.zq.commprep`, versionName `0.2.0-prep2-fpsgate-lowres`, versionCode 8, `primaryCpuAbi=arm64-v8a`, flags include `DEBUGGABLE`, camera permission granted, `installed=true` | device-verified (`dumpsys package`, 23:54) |
| APK under test | `ZQ-Settle-KEYED-PRIVATE-arm64.apk`, sha256 `36ee9d2879f0f2c14622c92069ef5c7cc77c4baba2cc6576816a5ec342cbdbd9` (78,181,397 bytes), built from public repo commit `217ab8f` (`217ab8f8fd7649b31e7ce61c46421356b5a752b3`) with arm64-v8a abiFilters in a scratch copy | file-verified (earlier build and private share record, 21:19); lastUpdateTime 22 Nov 2026 23:43:02 is device-verified and matches the 23:43 adb install. The APK itself was not re-pulled or re-hashed in this review. It contains a private API key, is kept private and is not in this repository. |
| Install path | adb / `com.android.shell`, firstInstallTime 19 Nov 2026 17:53:30, updated in place at 23:43:02 | device-verified |
| Camera / input | custom_input, requested 640×480, AE fps `[30, 30]`, analysis 640×480, preview 640×480; fps windows reported `size=480x640` (portrait) | file-verified (SETTLE_SDK `camera_bound`) |

## Evidence used (all read-only)

- `c55-run-2346.txt` (developer laptop) — complete SETTLE_SDK capture for this run (written 23:48:38). Redacted copy: `c55-run-2347-log-redacted.txt`.
- `therm-1934.txt` (developer laptop) — 23:46:26–23:49:21 battery level and battery-service temperature. `c55-therm-2346.txt` was 0 bytes and was not used.
- Read-only adb on the still-connected C55 at 23:54: `logcat -d` (not cleared), `dumpsys battery`, `dumpsys thermalservice`, `dumpsys package`, `run-as com.zq.commprep` directory listing. No uninstall, no data clear, no new measurement, no reboot, no Presage connector call, no credit-consuming action.
- App DataStore `files/datastore/commprep.preferences_pb` (606 bytes, mtime 23:48): preference key names and history JSON were read after a strings pass; no key-like values were present. `shared_prefs/presage_emd_secure_storage.xml` was listed and **was not opened or copied**.
- Screenshot: original `1004113800.jpg` shows a face and is excluded from this folder. Redacted copy: `c55-run-2347-screen-redacted.jpg` (camera preview covered).
- App source at public commit 217ab8f used only to interpret UI strings and history fields (file-verified against that tree).

## Session identity: one SDK run, during guided breathing

**There was no separate second SDK session.** File-verified in `c55-run-2346.txt` (exactly one `start_requested`, one `RUNNING`, one `stop_requested`, one `stopped`/`IDLE`; same PID 30291 throughout). Device-verified at 23:54: the same PID was still alive, the 256 KiB main logcat buffer had **0** remaining `SETTLE_SDK` lines, the crash buffer had **0** hits for this package, and the app had been backgrounded at 23:48:46 — nine seconds after IDLE — with no later start.

App history in DataStore (device-verified) records one preparation session starting **22 Nov 2026 23:44:17.573 Asia/Muscat**, with two completed steps:

1. `BASELINE`, not skipped, `ranSeconds: 60`
2. `BREATHING`, not skipped, `ranSeconds: 90`, `settingsLabel: "5/5"`

`last_settings` was `{baselineSeconds: 60, paceIn: 5, paceOut: 5, breathingSeconds: 90, rehearsalSeconds: 30}`. There is no rehearsal entry, so the rehearsal step did not run (or was not recorded). An older history session from 19 Nov 2026 23:05:25 Asia/Muscat (two baseline entries) is from a previous install of the same package and is not part of tonight's run.

The captured SDK interval aligns with the **breathing** step, not with a second camera run:

- `start_requested` 23:47:06.349 → `stop_requested` 23:48:36.337 = **89.988 s** (90 s breathing timer).
- Screenshot "Pace 5/5 · 22 s left" plus a brief focus interruption at 23:48:13–23:48:20 (consistent with taking a screenshot) plus 22 s lands on 23:48:36, the logged stop.
- In source, `LiveSignalPanel` starts the SDK only while that step's timer is running, and baseline and breathing are separate screens, so a new `start_requested` is expected when breathing starts. That is the event at 23:47:06.

The 60 s Quiet-pause / baseline timer step is therefore a **preceding step in the same preparation session**, not a second SDK stage in this evidence. Camera check-in *can* also run during baseline (source + consent was `ACCEPTED`, camera permission granted, keyed build). No `SETTLE_SDK` lines for a baseline camera run are in the 23:48:38 capture, so a baseline SDK session is **not device-verified** here and is **not claimed**. It is possible the log capture was started after baseline ended. This report does not invent that run.

## Stage 1 — SDK pulse run (the only SDK session)

Times are Asia/Muscat, 22 Nov 2026.

| Metric | Value | Label |
|---|---|---|
| `start_requested` | 23:47:06.349, `status=STARTING`, `preview=true` | file-verified |
| `status=RUNNING` | 23:47:08.285 | file-verified |
| `stop_requested` | 23:48:36.337, still `RUNNING`, samples=1639, pulseSamples=54, breathingSamples=17 | file-verified |
| `status=STOPPING` | 23:48:36.410 | file-verified |
| `status=IDLE` / `event=stopped` | 23:48:37.693 / 23:48:37.695, samples=1645, pulseSamples=55, breathingSamples=17 | file-verified |
| Duration RUNNING → stop_requested | **88.052 s** | file-verified |
| Duration start_requested → stop_requested | 89.988 s | file-verified |
| Duration RUNNING → IDLE | 89.410 s | file-verified |
| First logged pulse | 23:47:30.143, `pulseSamples=3` (still 0 at 23:47:25.340, so the first pulse arrived in that ~5 s window; "first pulse 23:47:30" is the first metrics line that shows it) | file-verified |
| Pulse sample counts per metrics window, then stop, then IDLE | **3, 6, 10, 13, 17, 21, 26, 30, 33, 37, 42, 47, 52, 54, 55** — monotonically increasing, no dropout | file-verified |
| Continuous pulse | **66.194 s** from 23:47:30.143 to `stop_requested` 23:48:36.337; 67.552 s if counted through IDLE (when the 55th sample was logged) | file-verified |
| Breathing samples | 17 at stop and at IDLE (SDK-logged only; the app does not show a breathing rate on the main status line). Not separately validated. | file-verified (count) |
| Camera fps windows (17×, ~5006 ms, 0 rejected) | 25.0, 25.0, 25.0, 25.0, 24.8, 25.0, 25.0, 25.0, 25.0, 25.0, 25.0, 24.8, **24.6**, 24.8, 24.8, 25.0, 24.8. Range **24.6–25.0**, mean 24.92. Sum of window frames = 2135. | file-verified |
| `lastIssue` | `FRAME_RATE_TOO_LOW` from 23:47:13.887 through stop. The warning also appeared while logged `cameraFps` was 25.0; it is treated as a **warning, not a fail**. Hint matches the on-screen text: "Use a camera mode that provides at least 25 frames per second." | file-verified; also reported on the screenshot |
| Early `NO_FACE_FOUND` | one hit at 23:47:08.900, cleared by `OK` at 23:47:09.119 (~211 ms). Metrics at 23:47:09.561 still note it as `lastIssue` before the fps warning takes over. | file-verified |
| Errors / crash | No `NETWORK_ERROR`, no `PROCESSING_FAILED`, no `FATAL` / AndroidRuntime for this package. Crash buffer empty for this app at 23:54. Process did not die. | file-verified (run log) and device-verified (crash buffer / pid at 23:54) |
| Timer fallback? | No. Logs are `SETTLE_SDK` events with SDK `RUNNING` and incrementing `pulseSamples`. Screenshot status is "Camera check-in running." and "Pulse estimate: 54 bpm (updated just now)", which is the live-signal wording in `LiveSignal.plainStatus` / `vitalsText`. The fallback string is "Timer only - no live signal" and is not what the screen showed. | file-verified (log + source) and reported (screenshot) |
| Pulse 54 bpm | On-screen value in the screenshot, mid-run. The SETTLE_SDK log records sample **counts**, not bpm, so 54 bpm is not independently numeric-verified from logcat. 54 at `stop_requested` is the sample count, coincidentally the same number, and is not the bpm. | reported (screenshot); consistent with a live SDK estimate |
| Battery | 69% on USB throughout 23:46:26–23:49:21; screenshot also shows 69%. Still 69%, USB powered, charging (`status: 2`) at 23:54. | file-verified (therm log); reported (screenshot); device-verified at 23:54 |
| Temperature (`dumpsys battery` `temperature`, tenths °C) | During RUNNING (17 samples, 23:47:13–23:48:35): **40.7–42.7 °C, average 41.57 °C**. Pre-run 23:46:26–23:47:07: 40.9–41.4 °C. After stop, temperature kept rising and **peaked at 43.2 °C at 23:49:11**, then 42.9 °C at 23:49:21. See discrepancies. | file-verified |
| Credit usage | Not present in SETTLE_SDK logs, DataStore history, or dumpsys. | **pending** — "not visible on device; portal re-read pending Zubin's double yes". No Presage connector call was made. |

### Stage 1 pass/fail

**PASS** (file-verified / device-verified), with an fps warning.

Criterion: at least 60 s of genuine SDK signal, not the timer fallback, with no crash. Continuous SDK pulse 66.194 s (to stop) / 67.552 s (to IDLE); genuine SDK path as above; no crash. `FRAME_RATE_TOO_LOW` is a warning, not a fail.

## Stage 2 — guided-breathing view (same session, not a second run)

This is the screen in `1004113800.jpg` / `c55-run-2347-screen-redacted.jpg`.

| Item | Value | Label |
|---|---|---|
| Screen | "Guided breathing"; "Camera check-in available: SmartSpectra (camera pulse + breathing)"; "Camera check-in running."; "Hide camera view"; "Signal details"; "Breathe out ... 4"; "Pace 5/5 · 22 s left"; battery 69%; status-bar clock 23:4x | reported (original screenshot). The redacted file covers the camera preview; the original is excluded because it shows a face. |
| Same session as Stage 1? | **Yes.** "22 s left" of a 90 s, 5/5 breathing step, taken around 23:48:13–23:48:20 (focus briefly left the activity, then returned, while SDK metrics continued at 23:48:14 and 23:48:20), ends at the logged stop 23:48:36. One `start_requested` only. | file-verified (log timestamps) + reported (screenshot) |
| Pulse on screen | 54 bpm, "updated just now", with the FRAME_RATE_TOO_LOW warning visible | reported (screenshot) |
| Metrics | All Stage 1 SDK metrics apply. At the likely screenshot moment (~23:48:14): pulseSamples=37, breathingSamples=11, cameraFps=24.6, lastIssue=FRAME_RATE_TOO_LOW, battery 69%, battery temperature 42.2 °C. | file-verified |
| Pass/fail | **Not a separate stage.** The criterion is already answered by Stage 1: **PASS**, same warning. This view is mid-run evidence of that pass (live SDK pulse on screen, timer still running, no crash). It is not a second 60 s measurement and is not scored again. | file-verified |

## Discrepancies with the previously stated "known facts"

The following were checked against the logs and corrected where needed:

1. **Temperature peak and average.** The earlier summary "40.7 to a peak of 43.2 °C, average about 41.9" mixes post-run samples into the run. During `RUNNING`, the range is **40.7–42.7 °C**, average **41.57 °C**. 43.2 °C is the peak of the whole 23:46:26–23:49:21 series, at **23:49:11**, about 34 s after IDLE. The average of all 35 C55 samples (pre, during and after) is 41.83 °C; during+after is 42.07 °C. None of those is "about 41.9" for the run itself.
2. **88 s duration** is correct for `RUNNING` → `stop_requested` (88.052 s). The process reached `IDLE` 1.358 s later. The breathing timer itself is 90 s (`start_requested` → `stop_requested` = 89.988 s).
3. **Pulse counts 3,6,10,13,17,21,26,30,33,37,42,47,52,54,55** — confirmed exactly, including 54 at `stop_requested` and 55 at IDLE.
4. **First pulse 23:47:30, about 66 s continuous pulse, 17 breathing samples, fps 24.6–25.0, lastIssue FRAME_RATE_TOO_LOW, one early NO_FACE_FOUND, no crash / NETWORK_ERROR / PROCESSING_FAILED, battery 69% on USB** — confirmed.
5. **No second SDK stage** — confirmed. The guided-breathing screenshot is the same session.

## Limitations

- Frame rate sat at or just under 25 fps (24.6–25.0). The SDK raised `FRAME_RATE_TOO_LOW` even in some 25.0 windows. This is a warning, not a fail, but it is a real limitation of this device/run.
- Presage credit cost of this session is unknown on the device and is pending a portal re-read after Zubin gives a double yes. No connector call was made for this report.
- Single physical device (Realme RMX3710 / C55). The earlier Poco run on 22 Nov (19:35, 59 s, gate not met) is a different phone and is not counted here. Emulator results do not count and were not used.
- Pulse 54 bpm is a screenshot reading; the log does not store bpm values.
- SDK breathing sample count is logged and not independently validated; the app does not present a breathing-rate vital on the main line.
- A baseline 60 s timer step ran earlier in the same preparation session; a camera check-in during that step is not evidenced in the captured SETTLE_SDK log and is not claimed.
- A SmartSpectra glog warning at 23:44:25 (app PID 30291, before this measurement) said model load was already in progress for `phasic-bp-inference` and timed out waiting after retries. That is a startup note only; it is not treated as a run failure, and no blood-pressure claim is made.
- Battery temperature continued to rise after the camera stopped; this report does not treat the 43.2 °C post-run peak as the in-run maximum.
- This is one successful run after the 23:43 install, not a multi-device or multi-run study.

## What this report does not do

- It does not say the build is ready to release. Zubin Qayam is the sole Release Authority.
- It does not itself update the DEV post. The redacted files are published in the public repo's `evidence/` folder.
- It does not include the original screenshot (face). Use only `c55-run-2347-screen-redacted.jpg`.
- It does not record, paste or describe the Presage API key, or the C55 serial.

## Credit usage (updated 23 Nov 2026 00:03 Muscat)
Presage connector usage.get_plan at 22 Nov 23:56 (after double yes): 72 credits remaining, free tier, renews 16 Dec 2026. 79 at 14:09, so 7 used today across the Poco session (19:29-19:36) and this C55 run. The per-run split is not visible (account-level total only). Status: file-verified from the connector reading.

## Follow-up runs on 23 Nov 2026 (same phone, same build)

Added 23 Nov 2026, 00:55 Asia/Muscat. Same Realme RMX3710 (C55), same arm64 build (versionName `0.2.0-prep2-fpsgate-lowres`, versionCode 8, from `217ab8f`). Both follow-up runs were screen-recorded on the phone so a demo video could be made. **Neither meets the 60 s continuous-pulse bar on its own.** The 22 Nov 23:47 run above remains the only log-verified run with at least 60 s of continuous genuine SDK pulse.

### 00:29 Quiet-baseline attempt — FAIL against the 60 s bar

| Item | Value | Label |
|---|---|---|
| Step | Quiet baseline (60 s), not Guided breathing; on-screen countdown from 59 s, "sit still and breathe normally" | reported (screen recording, frame check) |
| Pulse on screen | "No face found" / "Looking for a steady signal" for about the first 40 s; pulse first shown about 42 s in, brief dropout near 1:00, ended about 1:06. Roughly 24 s of pulse, erratic at 40–88 bpm (47, 50, 40, 48, 57, 58, 77, 56, 88, 77, 67) | reported (frame check of the screen recording) |
| Warnings | Under-25-fps warning visible throughout; camera framing was off the face for much of the run | reported (frame check) |
| SDK log | Not captured for the run itself. Only the final stop record survives (00:32:22, `pulseSamples=22`, `breathingSamples=7`, `lastIssue=FRAME_RATE_TOO_LOW`, logged when the app was closed before the redo; see the first 3 lines of `c55-run-0034-log-redacted.txt`) | file-verified (stop record only) |
| Video | Not published (face visible, not used as the demo) | — |

### 00:34 Guided-breathing redo — FAIL against the 60 s bar; used as the demo video

Log: `c55-run-0034-log-redacted.txt` (SETTLE_SDK lines only, extracted from a full logcat captured on the developer laptop). Video: `video/zq-settle-demo-c55-0034-blurred.mp4`.

| Metric | Value | Label |
|---|---|---|
| `start_requested` | 00:34:26.692, `preview=true` | file-verified |
| `status=RUNNING` | 00:34:30.725 | file-verified |
| `stop_requested` | 00:35:56.596, samples=892, pulseSamples=35, breathingSamples=17 | file-verified |
| `status=IDLE` / `stopped` | 00:35:57.996 / 00:35:58.001, pulseSamples=36, breathingSamples=17 | file-verified |
| Duration RUNNING → stop_requested | **85.9 s** (about 86 s) | file-verified |
| First logged pulse | `pulseSamples=0` at 00:34:56.072, `pulseSamples=2` at 00:35:05.154, so the first pulse arrived in that window (about 00:35:00) | file-verified |
| Pulse by log | About 51–60 s before stop_requested, roughly **56 s**. Pulse sample counts per metrics window: 2, 3, 10, 16, 23, 30, 35, then 36 at IDLE. Only one sample was added between 00:35:05 and 00:35:14, which matches the on-screen dropout | file-verified |
| Camera fps windows | 24.0, 24.8, 24.2, 24.4, 24.7, 24.2, 24.6, 24.6, 25.0, 25.0, 24.6, 24.6, 24.4, 24.8, 24.0, 24.8 (range **24.0–25.0**), 0 rejected frames | file-verified |
| Issues | `FRAME_RATE_TOO_LOW` from 00:34:37 through stop (warning). Early `NO_FACE_FOUND` (00:34:32.6), `EXCESSIVE_MOTION` (00:34:37.6) and one `MULTIPLE_FACES_FOUND` (00:34:58.6) | file-verified |
| Errors / crash | No crash, no `NETWORK_ERROR`, no `PROCESSING_FAILED` | file-verified (SETTLE_SDK log) |
| On-screen pulse | Shown briefly (82 bpm) about 0:51 into the recording, then back to "Looking for a steady signal"; returned about 1:18 and stayed for the last ~25 s: 60, 72, 82, 75, 65, 54, 58, 55, 56 bpm, each with the under-25-fps warning | reported (frame check of the screen recording, every ~3 s) |
| Screen | Guided breathing, pace 5/5, 90 s, countdown and "Breathe in / Breathe out" visible; "Camera check-in running." wording when a pulse is shown | reported (screen recording) |
| Screen recording | Phone screen recorder, MP4 (H.264 720×1600), 1:52 | file-verified |

**Pass/fail:** FAIL against the 60 s continuous genuine-pulse bar (about 56 s by log, and visibly intermittent on screen). It does show a genuine live SDK pulse with the frame-rate warning, so it is used as the demo video, captioned "live SDK pulse shown, with frame-rate warning", **not** as a 60-second pass.

### Demo video redaction

`video/zq-settle-demo-c55-0034-blurred.mp4`: 1:52, H.264 720×1600, **no audio track** (the recording's faint audio was removed). The whole camera-preview area (person and background) is heavily blurred for the full clip, including the floating preview bubble at the start; all app text, the pulse line, warnings and the breathing guide are left sharp. Faces were checked frame by frame at several points and are not recognisable. The unblurred recordings are kept private and are not published.

### Credits

72 credits remaining at 22 Nov 23:56 (79 at 14:09). The 00:29 and 00:34 runs used credits that have not been re-read yet; the per-run cost is not visible (account-level total only).
