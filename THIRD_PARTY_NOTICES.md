# Third-party notices and references

Flipper Home is independent software, not endorsed by Flipper Devices or Google. Product names identify compatible devices.

## Dependencies

- AndroidX / Jetpack Compose / Material: https://android.googlesource.com/platform/frameworks/support/ — Apache License 2.0.
- Kotlin / kotlinx.coroutines: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines — Apache License 2.0.
- JUnit 4 (tests): https://github.com/junit-team/junit4 — Eclipse Public License 1.0.
- OkHttp / MockWebServer (tests): https://github.com/square/okhttp — Apache License 2.0.
- JSON-java (JVM tests): https://github.com/stleary/JSON-java — public domain declaration.
- Gradle wrapper/build tooling: https://github.com/gradle/gradle — Apache License 2.0. Android/JetBrains SDKs and tooling retain their respective terms.

Versions are in the Gradle build/wrapper files. Dependency notices packaged with components remain applicable to the APK.

The optional custom integration uses your installed Home Assistant APIs: https://github.com/home-assistant/core — Apache License 2.0. Home Assistant and Google services are not bundled with the APK.

The optional direct Google server uses aiohttp: https://github.com/aio-libs/aiohttp — Apache License 2.0 and MIT, and Python's standard library. The deployment example uses Caddy: https://github.com/caddyserver/caddy — Apache License 2.0. Neither server runtime is bundled with the APK.

## Protocol references

Kotlin codecs/client code are maintained here; the Python reference library is not bundled.

- Android TV Remote v2 / androidtvremote2: https://github.com/tronikos/androidtvremote2 — Apache License 2.0.
- Google TV pairing protocol: https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/refs/heads/master/proto/polo.proto — Apache License 2.0.
- Flipper protobuf schemas: https://github.com/flipperdevices/flipperzero-protobuf — RPC interface reference.
- Official Flipper Android app: https://github.com/flipperdevices/Flipper-Android-App — BLE behavior reference.
- Flipper firmware / Momentum SDK: https://github.com/flipperdevices/flipperzero-firmware and https://github.com/Next-Flip/Momentum-Firmware — GPL-3.0.

## Capture companion

The bundled `app/src/main/assets/flipper_home_capture.fap` is compiled from `flipper_capture/capture.c`, configured by `application.fam`. Its Momentum f7 SDK is `d3f89dfe`, API 87.1. Full source and rebuild instructions are supplied here; no firmware image is distributed. SDK/firmware components retain upstream terms. See `flipper_capture/README.md`.

Downloaded signal collections, private captures, logs and personal home data are excluded. No general license for original project code has been selected; these notices do not replace upstream license terms or grant a new license for original code.
