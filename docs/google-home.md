# Google Home voice setup — no Home Assistant

Use the direct Flipper Home bridge to activate selected Flipper buttons from **your phone or Google/Nest speakers**. This is currently a developer-test integration; it is not a certified public Google Home service. There is no Google Home access token to paste into the app.

## Host the bridge once

Deploy the included [Google bridge](../google_bridge/README.md) on one HTTPS host with persistent storage, and create a bridge username/password. Hosting and a Google Home developer project are required; this repository does not yet provide a shared hosted service.

The backend receives Google commands; your phone keeps an outbound connection and sends the selected signal through Flipper. It never uploads your signal files. Keep bridge passwords, OAuth secrets and database backups private.

## Configure your Google developer project

1. Open the [Google Home Developer Console](https://console.home.google.com/) and create/select your project. Use [Google's cloud-to-cloud project instructions](https://developers.home.google.com/cloud-to-cloud/project/create).
2. Add a **Cloud-to-cloud integration** named **Flipper Home** and configure account linking with **OAuth authorization code**.
3. Enter the client ID/secret you configured on the server.
4. Use these URLs, replacing `https://YOUR_DOMAIN` with your bridge origin:

| Field | URL |
| --- | --- |
| Authorization | `https://YOUR_DOMAIN/oauth/authorize` |
| Token | `https://YOUR_DOMAIN/oauth/token` |
| Cloud fulfillment | `https://YOUR_DOMAIN/google/fulfillment` |

5. Add scopes `email` and `name`; leave App Flip/local fulfillment disabled for this first version. The bridge's redirect is exactly `https://oauth-redirect.googleusercontent.com/r/YOUR_PROJECT_ID`.
6. Save and enable the integration for developer testing in the console's **Test** section. Use the same Google account on your phone/Google Home. Google console labels may change; follow its displayed testing prerequisites.

[Official account-linking requirements](https://developers.home.google.com/cloud-to-cloud/primer/account-linking).

## Connect the phone and choose buttons

1. Install Flipper Home 0.3.0+ and connect Flipper by Bluetooth.
2. Open **⋮ → Voice control → Google Home**.
3. Enter the bridge's **HTTPS URL**, **bridge username** and **bridge password**. These are the bridge credentials you created, not your Google password.
4. Enable voice and choose **Save and connect**. Credentials are encrypted using Android Keystore; the password is not saved.
5. Open a remote → **Edit** → select a learned Flipper button → **Set up voice command**. Enable it, give it a unique name, choose **Tap** or **Timed hold** up to 5 seconds, and save.

Existing buttons remain disabled until explicitly selected. Only Flipper signals are exposed; Google TV buttons and automations are excluded. The Google Home bridge and Home Assistant are alternative connection providers; only the chosen one runs.

## Link Google Home

1. Open **Google Home → Add → Works with Google Home**.
2. Find your **[test] Flipper Home** integration. If missing, verify testing is enabled and you are using the project developer/test account.
3. Sign in on the bridge page with the **same bridge username/password** as the phone and approve linking.
4. Say **“Hey Google, sync my devices.”** Repeat sync when you add, rename or remove voice actions.
5. Say **“Hey Google, activate Bedroom lights off.”** Test on both the phone and a Nest speaker linked to that Google Home.

For **“Hey Google, turn off bedroom lights”**, create a Google Home routine with that starter and an action activating **Bedroom lights off**. Google may present these actions under scene controls/routine options rather than ordinary light tiles. This first version exposes named button actions, not light-state feedback.

Google's [Scene trait](https://developers.home.google.com/cloud-to-cloud/traits/scene) supports this action model but Scene-only integrations cannot currently receive public certification. A later public service would need eligible device traits, production hosting/account management and Google's approval.

## Testing and failures

Keep Flipper connected to the phone and the phone online. Offline/busy commands fail without being queued. A command is successful only after the phone confirms transmission/cleanup; this does not verify the light's state. The bridge cancels a command if it exceeds Google's response budget. Use shorter voice holds if a 5-second hold plus Bluetooth startup times out.

Local tests simulate OAuth linking, Google intents and an authenticated phone socket. Android native tests use real Keystore/TLS with fake transmission. Google account linking and physical voice acceptance need your deployed project; those are not completed by installing the APK alone.
