# Architecture

The Kotlin/Compose app has no hosted backend. `HomeApp` coordinates rooms, remotes, Favorites, mapping and manual automations. `HomeStore` keeps a versioned JSON model in private preferences; schema 7 adds opt-in voice names/durations to signals with disabled defaults for older data.

Layout controls reference saved action or automation IDs. Rocker/pad parts can hold separate bindings. `UniversalSender` routes each action to its own Flipper or TV transport. Automation steps preserve action order and optional per-occurrence hold duration; relearning keeps references stable.

## Flipper

`FlipperConnection`/`FlipperLink` provide authenticated Android GATT and length-prefixed protobuf RPC, negotiate MTU, obey receive-buffer credits, serialize operations, reassemble replies and check response statuses. Playback uses a start/load/press/release/exit firmware-app lifecycle per action. Cancellation attempts release/app exit. Infrared taps use named one-shot commands; holds use PRESS/RELEASE. Sub-GHz taps have a configured duration.

Application-owned BLE and a connected-device foreground service retain the connection across activity changes. A bounded reconnect attempt runs on reopen; explicit disconnect suppresses it. Manual playback stops when the UI goes into the background even if BLE remains connected. Explicitly enabled voice actions use the application/service scope and can run with the UI closed.

Capture verifies/installs the bundled FAP and launches it over RPC. A random correlation token derives a companion-owned SD-card path; unrelated replies are rejected and saved-file bytes are read back before preview. The companion exits before normal firmware playback. FAP source/build instructions and its exact SDK are in `flipper_capture`.

## TV

`TvSetup` discovers `_androidtvremote2._tcp.` through Android NSD and supports manual IP and six-character PIN entry. `TvProtocol` implements bounded Remote v2/Polo messages. Control defaults to TCP 6466, pairing to the advertised control port plus one.

An Android Keystore RSA client key provides mutual TLS. During explicit pairing, a displayed-code proof binds client/server public keys; successful pairing saves the TV public-key fingerprint. Ordinary connections require that fingerprint. The custom certificate verifier accepts a self-signed certificate only for explicit pairing before code verification, never as a normal-control trust bypass. Pairing codes/private keys are absent from home JSON.

`TvConnection` maintains sockets, answers protocol pings, reconnects and sends SHORT or START_LONG/END_LONG key events. Cancellation releases held keys; input already released during startup does not transmit later. Microphone/IME features are not advertised.

JVM tests check models, references, wire fixtures, routing, timing/cancellation and pairing proof. Native tests use real Android Keystore/Conscrypt against a localhost TLS fixture and isolated persistence. CI performs software checks, not physical-device acceptance.

## Optional Home Assistant voice bridge

`VoiceBridge` connects to Home Assistant's HTTPS-authenticated WebSocket API using OkHttp. `VoiceSettings` encrypts the user's token with Android Keystore AES-GCM. Only selected Flipper action IDs, names and room labels leave the phone; paths and signal contents are resolved locally. Phone-side execution checks timestamps, duplicates, opt-in status, live BLE and manual playback availability before acquiring the Flipper operation lock. A bounded wake lock covers transmission. Disconnect/cancellation attempts the normal release/exit cleanup.

The `custom_components/flipper_home` integration registers authenticated WebSocket commands and creates button entities. Phone records are bound to the authenticated Home Assistant user and active socket. Heartbeats expire availability after 45 seconds; requests wait for an explicit completion/failure acknowledgment and never queue or retry transmission. Metadata persists without tokens/sockets. Google Assistant exposure uses Home Assistant's existing button-to-scene support, with routines for custom phrases. Setup is documented in [voice-control.md](voice-control.md).

## Direct Google Home bridge

`google_bridge` is a standalone Python/aiohttp server with SQLite persistence, OAuth authorization-code linking/refresh, separate phone and Google token purposes, and Google SYNC/QUERY/EXECUTE/DISCONNECT endpoints. It exposes selected buttons as stateless Scene devices for a developer test project. Account passwords use scrypt, opaque credentials/codes are hashed at rest, redirects are restricted to the project's Google OAuth redirect, and login forms require a short-lived cookie/form nonce.

The phone signs in over HTTPS, keeps its credential in Android Keystore and subscribes to the bridge's live socket. Google requests wait for transmission confirmation within an 8-second deadline. Timeouts send a request-specific cancellation to the phone. Request IDs/results persist to suppress duplicate transmission across retries/restarts; interrupted requests fail rather than replaying. Direct Google holds are limited to 5 seconds in the UI and executor. Background transport uses the existing application/service scope. Provider selection defaults to direct Google for new installs; legacy configured Home Assistant connections retain their provider.

Deployment targets a single host with persistent disk behind HTTPS; Docker/Caddy configuration is supplied. A shared public service, multi-instance state, production Google certification and appliance state feedback are outside this developer-test implementation. See [google-home.md](google-home.md).
