# Direct Google Home bridge

This server connects selected Flipper buttons to a **Google Home cloud-to-cloud developer integration**, usable from Google Assistant on a phone and Google/Nest speakers. **Home Assistant is not required.** The phone maintains an outbound authenticated WebSocket and performs Flipper transmission locally. Signal paths/data remain on the phone/Flipper.

Implemented: OAuth authorization-code linking, refresh tokens, SYNC/QUERY/EXECUTE/DISCONNECT, persisted selected-action metadata, phone sign-in, authenticated live transport, completion/failure acknowledgments, deadline cancellation and persistent duplicate-request suppression. No offline queues or automatic transmission retries.

## Run locally

Python 3.12+:

```sh
pip install -r google_bridge/requirements.txt
python -m unittest discover -s tests -v
python -m google_bridge create-user your-username
```

The account command prompts for a password without echoing it. Use a unique password of at least 12 characters. It stores an scrypt password hash in `data/bridge.sqlite3`; phone/OAuth tokens and authorization codes are hashed. Google credentials cannot authenticate a phone socket and phone credentials cannot call Google fulfillment.

Set the environment variables below, then run `python -m google_bridge serve`. The HTTP listener defaults to loopback port 8080 and must be behind a trusted HTTPS proxy for phone/Google access. Unencrypted local HTTP is for server tests/proxy traffic, not Android connections.

| Variable | Value |
| --- | --- |
| `BRIDGE_PUBLIC_URL` | Public HTTPS origin, for example `https://voice.example.com` |
| `GOOGLE_PROJECT_ID` | Project ID from Google Home Developer Console |
| `GOOGLE_CLIENT_ID` | Client ID entered in Google Home account-linking setup |
| `GOOGLE_CLIENT_SECRET` | Random secret of at least 32 characters, shared only with Google |
| `BRIDGE_DATABASE` | Persistent SQLite path; default `data/bridge.sqlite3` |
| `BRIDGE_BIND` / `PORT` | Default `127.0.0.1` / `8080` |

## Deploy with HTTPS

Use one Linux host with Docker Compose, a persistent disk, and a domain pointing at it. Do not deploy the SQLite server with multiple replicas or ephemeral storage. `compose.yaml` includes Caddy for certificates/HTTPS; ports 80/443 must reach the host. Serverless deployment needs a different persistence/session design.

1. Create an ignored `.env` next to `compose.yaml`, containing `BRIDGE_DOMAIN`, `GOOGLE_PROJECT_ID`, `GOOGLE_CLIENT_ID`, and `GOOGLE_CLIENT_SECRET`. Generate the secret with `python -c "import secrets; print(secrets.token_urlsafe(48))"` locally and keep it private.
2. From `google_bridge`, run `docker compose up -d --build`.
3. Run `docker compose exec bridge python -m google_bridge create-user your-username` and enter your bridge password.
4. Check `https://YOUR_DOMAIN/health` returns `{"status":"ok"}`.
5. Complete [Google project and phone setup](../docs/google-home.md).

The operator controls hosting, accounts and costs. The repository does not supply a shared hosted service. Access logging is disabled in the Python server; do not configure the proxy to log authorization headers, request bodies, OAuth codes or credentials. Restrict access to persistent database backups. Unlinking Google revokes that OAuth grant; other household grants and phone credentials remain valid. Phone tokens expire after 90 days; sign in again to renew them.

## Limits

- Actions are Google **SCENE** devices: “Hey Google, activate Bedroom lights off.” Use Google Home routines for other phrases. They do not report a light's on/off state or expose brightness percentages.
- Google-only integrations using Scene are currently **ineligible for public certification/release**. This implementation targets a personal/developer test project. Publishing the APK/source does not certify or enable a public Works with Google Home service. [Google's Scene rule](https://developers.home.google.com/cloud-to-cloud/traits/scene).
- Direct Google holds are limited in the UI to **0.1–5 seconds**. The backend waits up to 8 seconds for confirmation and cancels the matching phone command on timeout; Bluetooth startup/cleanup contributes to this budget. Longer manual/Home Assistant holds remain available.
- Flipper must remain connected to the phone. The Android connected-device service retains voice transport while the UI is closed, subject to phone/network/battery restrictions. Google requests received while offline/busy fail immediately.
- Local protocol tests and native TLS tests do not prove real Google/Nest acceptance. Operator deployment, Google test account linking and physical voice testing are required.
