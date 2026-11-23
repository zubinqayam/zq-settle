# ZQ Settle — test evidence (redacted)

Published 23 Nov 2026 with Zubin Qayam's approval. Zubin Qayam is the sole Release Authority; nothing here says the app is ready to release. Times are Asia/Muscat (UTC+4).

ZQ Settle's camera check-in is an **experimental, non-medical informational signal**. It is not a medical device and does not diagnose or monitor anything. During a check-in the Presage SmartSpectra SDK communicates with Presage's servers (authentication, model delivery, usage records); see the README for data handling.

Labels: **file-verified** (checked in log files), **device-verified** (read from the phone over adb), **reported** (read from a screenshot or screen recording).

## Device and build

- Phone: Realme RMX3710 (realme C55), Android 15. Single physical device. Emulator results are not counted. *(device-verified)*
- App: `com.zq.commprep`, versionName `0.2.0-prep2-fpsgate-lowres`, versionCode 8, arm64 build from public commit `217ab8f`. *(device-verified / file-verified)*
- The tested APK contains a private Presage API key, so it is kept private and is **not** in this repository. Its SHA-256 is `36ee9d2879f0f2c14622c92069ef5c7cc77c4baba2cc6576816a5ec342cbdbd9`. The public source builds without a key (timer-only).

## Files

| File | What it shows | Label |
|---|---|---|
| [c55-test-report-final.md](c55-test-report-final.md) | Full test report: the 22 Nov 23:47 run, plus the 23 Nov 00:29 and 00:34 follow-up runs | file-verified / device-verified / reported |
| [c55-run-2347-log-redacted.txt](c55-run-2347-log-redacted.txt) | SDK event log (tag `SETTLE_SDK` only) and battery temperature for the 22 Nov 23:47 run | file-verified |
| [c55-run-2347-screen-redacted.jpg](c55-run-2347-screen-redacted.jpg) | Mid-run screenshot of the 23:47 run (Guided breathing, "Camera check-in running.", pulse estimate 54 bpm with the frame-rate warning). Camera preview covered for privacy | reported (screenshot) |
| [c55-run-0034-log-redacted.txt](c55-run-0034-log-redacted.txt) | SDK event log (tag `SETTLE_SDK` only) for the 23 Nov 00:34 Guided-breathing run shown in the demo video | file-verified |
| [video/zq-settle-demo-c55-0034-blurred.mp4](video/zq-settle-demo-c55-0034-blurred.mp4) | Demo video of the 00:34 run: 1:52, H.264 720×1600, no audio. Camera preview (person and background) blurred; app text, pulse line, warnings and breathing guide unblurred. **Live SDK pulse shown, with frame-rate warning — not a 60-second pass.** | reported (screen recording); redaction checked |
| [start-date-evidence.md](start-date-evidence.md) | Redacted start-date evidence: earliest work 13 Nov 2026, first code commit 14 Nov 2026; Entry Period opened 5 Nov 2026, 02:00 | file-verified; Zubin's confirmation pending |

## Summary of results

| Run | Step | SDK RUNNING | Genuine SDK pulse | Warnings | Internal 60 s QA check |
|---|---|---|---|---|---|
| 22 Nov 23:47:08–23:48:36 | Guided breathing, 90 s, pace 5/5 | 88 s | ~66 s continuous (first pulse 23:47:30) | FRAME_RATE_TOO_LOW (fps 24.6–25.0); one brief early NO_FACE_FOUND | **PASS** (meets internal 60 s QA check, log-verified). No video of this run exists; evidence is the log and the redacted screenshot |
| 23 Nov 00:29 | Quiet baseline, 60 s | log not captured | ~24 s on screen, erratic 40–88 bpm | under-25-fps warning; face often out of frame | FAIL |
| 23 Nov 00:34:30–00:35:56 | Guided breathing, 90 s, pace 5/5 | 86 s | ~56 s by log (first pulse about 00:35:00); on screen intermittent, then steady for the last ~25 s (60, 72, 82, 75, 65, 54, 58, 55, 56 bpm) | FRAME_RATE_TOO_LOW throughout (fps 24.0–25.0); early NO_FACE_FOUND, EXCESSIVE_MOTION, one MULTIPLE_FACES_FOUND | FAIL on its own; used as the demo video |

Other details for the 23:47 run: 17 breathing samples logged (count only, not validated), in-run battery temperature 40.7–42.7 °C, no crash, NETWORK_ERROR or PROCESSING_FAILED. No crash or network error in the 00:34 run either.

## Limitations

- The camera ran just under the SDK's 25 fps target, so the FRAME_RATE_TOO_LOW warning appears in every run.
- One phone, a handful of runs. Results show the check-in worked under the recorded conditions, not in general.
- Presage credit cost per run is not visible (account-level total only): 72 credits remained at 22 Nov 23:56; the 23 Nov runs have not been re-read.
- On-screen bpm values are read from the screen; the SDK log records sample counts, not bpm.

## What is deliberately not here

The APK (contains a private key), unredacted screenshots and recordings (face visible), the full device logcat, device serials, and account or credit logs.
