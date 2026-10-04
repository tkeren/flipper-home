# Hey Google with Home Assistant

Enable voice for individual learned Flipper buttons. Home Assistant exposes these as button entities; its Google Assistant integration represents them as scenes. Say **“Hey Google, activate Bedroom lights off”**, or create a Google Home routine for a custom phrase.

This feature requires your own Home Assistant installation and its [Google Assistant connection](https://www.home-assistant.io/integrations/google_assistant/). Flipper Home does not register phrases directly with Google's assistant. Google TV commands and automation buttons cannot be exposed by this bridge.

## 1. Install the Home Assistant integration

1. Download the `flipper-home-home-assistant-*.zip` asset from [Releases](https://github.com/tkeren/flipper-home/releases), or download this repository.
2. Copy the supplied `custom_components/flipper_home` folder into your Home Assistant configuration directory. The final path must be `config/custom_components/flipper_home/manifest.json`.
3. Restart Home Assistant.
4. Open **Settings → Devices & services → Add integration**, search for **Flipper Home**, and add it once. It does not ask for your Flipper pairing code or signals.

The integration stores the phone ID and selected action names. Signals stay on your Flipper; the phone resolves action IDs to its saved buttons when a command arrives. Manual installation is supported; this release is not listed in HACS.

## 2. Connect the phone

1. In your Home Assistant profile, open **Security → Long-lived access tokens** and create a token for Flipper Home. Use a dedicated non-administrator Home Assistant account if practical.
2. In Flipper Home, open **⋮ → Voice control**. Enter your Home Assistant **HTTPS URL** and the token, enable voice control, then **Save and connect**.
3. Connect Flipper using the app's Bluetooth picker. The voice screen should show **Voice control connected**.

Use an HTTPS address your phone can reach with a certificate Android trusts. A Home Assistant Cloud remote URL works when remote access is enabled; an existing HTTPS reverse proxy works too. Plain `http://homeassistant.local:8123` is not accepted. Certificate validation is enabled; self-signed certificates require a properly trusted configuration. Keep tokens private; do not put them in screenshots, issues or chat. The app encrypts the token using Android Keystore. Forgetting the server removes the phone's copy; revoke the token in Home Assistant to invalidate it.

## 3. Choose buttons

1. Open the remote and choose **Edit**.
2. Select a learned Flipper button or a mapped rocker/pad part and choose **Set up voice command**.
3. Enable the button and enter a unique action name, for example **Bedroom lights off**.
4. Choose **Tap**, or **Timed hold** with a duration of **0.1–60 seconds** for dimming.
5. **Save voice command**. The selected action appears as a Home Assistant button under the phone's Flipper Home device.

All buttons start with voice disabled, including signals saved before this feature. Only the selected learned Flipper buttons become available. Relearning a button keeps its voice selection. Disabling voice or deleting the signal removes its exposed entity and cancels its active voice command.

## 4. Expose to Google

With **Home Assistant Cloud**, open Home Assistant's Google Assistant settings and expose the selected `button` entities. With the manual Google Assistant integration, add those entities to its exposure configuration using the [official setup instructions](https://www.home-assistant.io/integrations/google_assistant/). Ask Google to sync devices if needed.

Then say **“Hey Google, activate Bedroom lights off.”** This can work from your phone or a Nest speaker linked to that Google Home. For **“Hey Google, turn off bedroom lights”**, create a Google Home routine with that starter phrase and an action that activates **Bedroom lights off**. These are actions/scenes, not lights with reported on/off state or arbitrary brightness percentages.

## Availability and testing

Flipper must stay connected to the Android phone, and the phone must reach Home Assistant. The connection service keeps the bridge running when you leave the app. Force-stopping the app, disconnecting Flipper, Android stopping the service or losing connectivity makes actions unavailable. Phone battery settings can affect background reliability.

Commands run immediately and are never queued for later. A busy phone rejects a voice request; failures are reported to Home Assistant and are not automatically retransmitted. Timed holds release the signal on completion, cancellation or disconnect. Open the app to stop an active voice command. One Flipper's position/range still determines which appliances it can reach.

First test an enabled action using its **Press** control in Home Assistant, then try Google. Verify the light actually responds: transmitting does not confirm appliance state. If the phone reports the integration is missing, complete step 1; if a button is unavailable, check both phone connections. Existing Google exposure/routine setup and physical voice acceptance need testing on your own Home Assistant and Google Home.
