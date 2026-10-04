# Architecture

The Kotlin/Compose app has no backend. `HomeApp` coordinates rooms, remotes, Favorites, mapping and manual automations. `HomeStore` keeps a versioned JSON model in private preferences; schema 6 adds network devices/commands with defaults for older data.

Layout controls reference saved action or automation IDs. Rocker/pad parts can hold separate bindings. `UniversalSender` routes each action to its own Flipper or TV transport. Automation steps preserve action order and optional per-occurrence hold duration; relearning keeps references stable.

## Flipper

`FlipperConnection`/`FlipperLink` provide authenticated Android GATT and length-prefixed protobuf RPC, negotiate MTU, obey receive-buffer credits, serialize operations, reassemble replies and check response statuses. Playback uses a start/load/press/release/exit firmware-app lifecycle per action. Cancellation attempts release/app exit. Infrared taps use named one-shot commands; holds use PRESS/RELEASE. Sub-GHz taps have a configured duration.

Application-owned BLE and a connected-device foreground service retain the connection across activity changes. A bounded reconnect attempt runs on reopen; explicit disconnect suppresses it. Active playback stops when the UI goes into the background even if BLE remains connected.

Capture verifies/installs the bundled FAP and launches it over RPC. A random correlation token derives a companion-owned SD-card path; unrelated replies are rejected and saved-file bytes are read back before preview. The companion exits before normal firmware playback. FAP source/build instructions and its exact SDK are in `flipper_capture`.

## TV

`TvSetup` discovers `_androidtvremote2._tcp.` through Android NSD and supports manual IP and six-character PIN entry. `TvProtocol` implements bounded Remote v2/Polo messages. Control defaults to TCP 6466, pairing to the advertised control port plus one.

An Android Keystore RSA client key provides mutual TLS. During explicit pairing, a displayed-code proof binds client/server public keys; successful pairing saves the TV public-key fingerprint. Ordinary connections require that fingerprint. The custom certificate verifier accepts a self-signed certificate only for explicit pairing before code verification, never as a normal-control trust bypass. Pairing codes/private keys are absent from home JSON.

`TvConnection` maintains sockets, answers protocol pings, reconnects and sends SHORT or START_LONG/END_LONG key events. Cancellation releases held keys; input already released during startup does not transmit later. Microphone/IME features are not advertised.

JVM tests check models, references, wire fixtures, routing, timing/cancellation and pairing proof. Native tests use real Android Keystore/Conscrypt against a localhost TLS fixture and isolated persistence. CI performs software checks, not physical-device acceptance.
