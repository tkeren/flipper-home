# Contributing

Bug reports and compatibility observations are welcome in GitHub Issues. Include Android/app version, exact Flipper firmware/API or TV model, reproduction and error text. Do not include pairing codes, captured private commands, device addresses or full app-data backups.

Discuss larger changes first. Keep changes focused, preserve existing action/automation references and migration defaults, and state which physical devices you tested. Original project code does not yet have a general reuse license; see README before distributing derivatives.

## Checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: use `gradlew.bat`. SDK 35 and JDK 21 are required. Rebuild capture changes against the exact SDK in `flipper_capture/README.md` and verify exported symbols.

Native checks use a localhost TLS TV fixture and isolated test preferences; they do not send IR/Sub-GHz or connect to a physical TV. Prefer a disposable emulator:

```sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.flipperhome.test/com.flipperhome.TvChecksInstrumentation
```

This custom runner prints its own success/failure result, not an AndroidX/JUnit suite result.

## Review considerations

- Keep BLE operations serialized and obey framing/receive-buffer credit limits.
- Release held commands and exit firmware apps after completion, cancellation and failure. Retained native playback sessions previously triggered a firmware crash and were removed.
- Preserve TV code authentication, Keystore private keys and normal-connection identity pinning.
- Preserve saved action IDs, automation bindings and per-step hold durations during edits/migrations.
- Mock/native protocol tests are distinct from physical appliance acceptance.

Never commit SDK paths, private home data, signing keys, captured signals, build/cache output, `.tooling` or downloaded signal collections.
