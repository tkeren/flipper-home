# Flipper Home capture companion

Receive-only Flipper FAP for the native Android mapping wizard. Built for **Momentum `mntm-dev[18-08-2026]`, SDK `d3f89dfe`, target f7, API 87.1**. The phone bundles this binary in `app/src/main/assets/flipper_home_capture.fap` and installs it on demand over authenticated Bluetooth RPC. No firmware flash or USB transfer is needed.

The companion takes one capture per launch. It receives IR (decoded, or raw with a 38 kHz/0.33 default carrier) or decoded replayable static Sub-GHz signals. It uses the internal CC1101 and supports AM650, AM270, FM238, FM476. Dynamic/rolling-code signals and RAW Sub-GHz capture are outside this version's scope.

## Build

Install `ufbt==0.2.6`, then select the exact firmware SDK:

```powershell
$env:UFBT_HOME = Join-Path (Get-Location) '.tooling/capture-ufbt'
ufbt update --url https://up.momentum-fw.dev/builds/firmware/dev/flipper-z-f7-sdk-mntm-dev-d3f89dfe.zip --hw-target f7
Set-Location flipper_capture
ufbt
Copy-Item dist/flipper_home_capture.fap ../app/src/main/assets/flipper_home_capture.fap
```

uFBT checks every imported symbol against the SDK's exported API. Changing the firmware may require selecting a new SDK, rebuilding the FAP and rebuilding the APK. Infrared signal helpers in this SDK are not exported, so the companion copies worker messages/timings and writes the standard `.ir` format directly.

Physical verification on October 3, 2026: phone installation over BLE with checksum verification, app startup on Momentum, READY notification, Princeton capture at 433.92 MHz/AM650, complete `.sub` file read-back, app exit, and the phone's Test/Save preview. Native IR and user confirmation of new-button playback remain pending.

## RPC protocol v1

Launched with `app_start_request` (tag 16), path to the FAP and args `RPC`. Register the RPC callback before sending the app-start event (58). The callback copies data-exchange bytes into a queue and acknowledges the command immediately.

Phone → Flipper, data exchange (65), field 1 bytes, ASCII:

```text
FH1 ARM <lowercase-UUID> <RF|IR> <frequency-Hz> <preset-index>
```

Preset indices are 0 AM650, 1 AM270, 2 FM238, 3 FM476. The FAP derives the path locally from the validated UUID, under `/ext/subghz/FlipperHome` or `/ext/infrared/FlipperHome`; it never accepts arbitrary file paths or overwrites saved captures.

Flipper → phone uses the same data-exchange tag, with five tab-separated fields:

```text
FH1<TAB><UUID><TAB><READY|CAPTURED|ERROR><TAB><owned-path><TAB><protocol-or-error>
```

`READY` is emitted after the receiver starts. `CAPTURED` is emitted after workers stop and the complete file is saved. The phone verifies the correlation token, owned path, and reads the file back before offering Test/Save. IR files use the signal name `Captured`; dashboard display labels are stored on the phone.

Capture times out after 30 seconds. App exit (47), Flipper Back, and session loss stop the receiver and free resources. The Android client exits the companion before invoking the regular Infrared/Sub-GHz apps for playback. Retrying a capture uses a fresh UUID; captures not assigned to a room remain ordinary files on the SD card and can be imported later.


