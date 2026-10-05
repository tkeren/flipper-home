"""OAuth + Google fulfillment + authenticated live phone transport."""
import asyncio
import base64
from dataclasses import dataclass
from html import escape
import hmac
import json
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlencode

from aiohttp import web, WSMsgType

from .storage import Store, digest

ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
SCENE = "action.devices.commands.ActivateScene"


@dataclass(frozen=True)
class Settings:
    public_url: str
    project_id: str
    client_id: str
    client_secret: str
    database: str = "data/bridge.sqlite3"
    execute_timeout: float = 8.0

    @property
    def redirect(self):
        return f"https://oauth-redirect.googleusercontent.com/r/{self.project_id}"


class Phone:
    def __init__(self, ws, subscription):
        self.ws, self.subscription = ws, subscription
        self.actions = {}
        self.available = False
        self.last_seen = time.monotonic()
        self.pending = {}

    def online(self):
        return self.available and not self.ws.closed and time.monotonic()-self.last_seen <= 45

    async def press(self, action, timeout):
        if action not in self.actions or not self.online():
            return "deviceOffline"
        if self.pending:
            return "deviceBusy"
        request = secrets.token_hex(16)
        future = asyncio.get_running_loop().create_future()
        self.pending[request] = future
        try:
            await self.ws.send_json({"id": self.subscription, "type": "event", "event": {
                "request_id": request, "action_id": action, "issued_at_ms": int(time.time()*1000)}})
            ok = await asyncio.wait_for(future, timeout)
            return None if ok else "sceneCannotBeApplied"
        except (TimeoutError, ConnectionError):
            return "deviceOffline"
        finally:
            self.pending.pop(request, None)
            if not future.done() or future.cancelled():
                if not self.ws.closed:
                    try:
                        await self.ws.send_json({"id": self.subscription, "type": "event", "event": {"cancel_request_id": request}})
                    except ConnectionError:
                        pass

    def close(self):
        self.available = False
        for future in self.pending.values():
            if not future.done():
                future.set_exception(ConnectionError("Phone disconnected"))


class Bridge:
    def __init__(self, settings, store):
        self.settings, self.store = settings, store
        self.phones = {}
        self.forms = {}
        self.attempts = {}
        self.password_lock = asyncio.Semaphore(4)

    def rate_limit(self, request, key):
        now = time.monotonic()
        # Bound memory without trusting spoofable forwarding headers.
        self.attempts = {k: v for k, v in self.attempts.items() if v[0] > now-900}
        if len(self.attempts) > 5000:
            raise web.HTTPTooManyRequests()
        identity = (request.remote, key)
        started, count = self.attempts.get(identity, (now, 0))
        self.attempts[identity] = (started, count+1)
        if count >= 15:
            raise web.HTTPTooManyRequests(text="Try again later")

    async def password(self, request, data):
        name, password = data.get("username", ""), data.get("password", "")
        if not isinstance(name, str) or not isinstance(password, str) or len(name) > 80 or not 1 <= len(password) <= 256:
            raise web.HTTPBadRequest(text="Enter your bridge username and password")
        self.rate_limit(request, "login")
        credentials = self.store.credentials(name)
        async with self.password_lock:
            owner = await asyncio.to_thread(Store.verify_password, credentials, password)
        if not owner:
            raise web.HTTPUnauthorized(text="Incorrect username or password")
        return owner

    def bearer(self, request, kind):
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer ") or len(header) > 1000:
            raise web.HTTPUnauthorized()
        token = self.store.token(header[7:], kind)
        if not token:
            raise web.HTTPUnauthorized()
        return token

    async def login(self, request):
        data = await request.json()
        owner = await self.password(request, data)
        phone = data.get("bridge_id", "")
        if not isinstance(phone, str) or not ID.fullmatch(phone):
            raise web.HTTPBadRequest(text="Invalid phone ID")
        self.store.phone(phone, owner, str(data.get("name", "Android"))[:80])
        token = self.store.issue("phone", owner, 90*86400, phone=phone)
        return web.json_response({"access_token": token, "project_id": self.settings.project_id})

    def authorize_params(self, data):
        if data.get("client_id") != self.settings.client_id or data.get("redirect_uri") != self.settings.redirect or data.get("response_type") != "code":
            raise web.HTTPBadRequest(text="Invalid Google account-linking request")
        state = data.get("state", "")
        if not isinstance(state, str) or not 1 <= len(state) <= 2048:
            raise web.HTTPBadRequest(text="Missing OAuth state")
        return {"redirect_uri": self.settings.redirect, "state": state}

    async def authorize(self, request):
        params = self.authorize_params(request.query)
        now = time.monotonic()
        self.forms = {k: v for k, v in self.forms.items() if v[0] > now}
        self.rate_limit(request, "authorize")
        nonce = secrets.token_urlsafe(32)
        self.forms[digest(nonce)] = (now+600, params)
        body = f"""<!doctype html><html lang="en"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Connect Flipper Home</title>
        <style>body{{background:#0c1117;color:#edf2f6;font:18px system-ui;max-width:420px;margin:10vh auto;padding:24px}}input,button{{box-sizing:border-box;width:100%;padding:16px;margin:8px 0;border-radius:12px;font:inherit}}button{{background:#9ce4ce;border:0}}input{{background:#17212c;color:white;border:1px solid #66717c}}label{{display:block;margin-top:16px}}</style>
        <h1>Connect Flipper Home</h1><p>Allow Google Home to activate the Flipper buttons you selected on your phone.</p>
        <form method="post" action="/oauth/authorize"><input type="hidden" name="nonce" value="{escape(nonce)}">
        <label>Bridge username<input name="username" autocomplete="username" maxlength="80" required></label>
        <label>Bridge password<input type="password" name="password" autocomplete="current-password" maxlength="256" required></label>
        <button>Connect to Google Home</button></form><p>Use your Flipper Home bridge account, not your Google password.</p></html>"""
        response = web.Response(text=body, content_type="text/html")
        response.set_cookie("flipper_link", nonce, secure=True, httponly=True, samesite="Lax", path="/oauth", max_age=600)
        return response

    async def authorize_post(self, request):
        data = await request.post()
        nonce = data.get("nonce", "")
        cookie = request.cookies.get("flipper_link", "")
        if not nonce or not hmac.compare_digest(nonce, cookie):
            raise web.HTTPForbidden(text="Open account linking again")
        form = self.forms.get(digest(nonce))
        if not form or form[0] < time.monotonic():
            raise web.HTTPForbidden(text="Account-linking session expired")
        owner = await self.password(request, data)
        self.forms.pop(digest(nonce), None)
        code = self.store.code(owner, form[1]["redirect_uri"])
        response = web.HTTPFound(form[1]["redirect_uri"] + "?" + urlencode({"code": code, "state": form[1]["state"]}))
        response.del_cookie("flipper_link", path="/oauth")
        raise response

    async def token(self, request):
        data = await request.post()
        client, secret = data.get("client_id", ""), data.get("client_secret", "")
        authorization = request.headers.get("Authorization", "")
        if authorization.startswith("Basic "):
            try:
                client, secret = base64.b64decode(authorization[6:], validate=True).decode().split(":", 1)
            except (ValueError, UnicodeDecodeError):
                raise web.HTTPUnauthorized()
        if not hmac.compare_digest(client, self.settings.client_id) or not hmac.compare_digest(secret, self.settings.client_secret):
            raise web.HTTPUnauthorized()
        if data.get("grant_type") == "authorization_code":
            owner = self.store.consume_code(data.get("code", ""), data.get("redirect_uri", ""))
            if not owner:
                return web.json_response({"error": "invalid_grant"}, status=400)
            grant = secrets.token_hex(16)
            refresh = self.store.issue("refresh", owner, 365*86400, grant=grant)
        elif data.get("grant_type") == "refresh_token":
            stored = self.store.token(data.get("refresh_token", ""), "refresh")
            if not stored:
                return web.json_response({"error": "invalid_grant"}, status=400)
            owner, grant = stored["owner"], stored["grant"]
            refresh = None
        else:
            return web.json_response({"error": "unsupported_grant_type"}, status=400)
        response = {"access_token": self.store.issue("access", owner, 3600, grant=grant), "token_type": "Bearer", "expires_in": 3600}
        if refresh:
            response["refresh_token"] = refresh
        return web.json_response(response)

    async def websocket(self, request):
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=64000)
        await ws.prepare(request)
        phone = None
        phone_id = None
        try:
            await ws.send_json({"type": "auth_required"})
            auth = await asyncio.wait_for(ws.receive_json(), 10)
            credential = self.store.token(str(auth.get("access_token", "")), "phone") if auth.get("type") == "auth" else None
            if not credential:
                await ws.send_json({"type": "auth_invalid"})
                return ws
            phone_id, owner = credential["phone"], credential["owner"]
            await ws.send_json({"type": "auth_ok"})
            async for message in ws:
                if message.type != WSMsgType.TEXT:
                    break
                data = json.loads(message.data)
                msg_id = data.get("id")
                try:
                    if type(msg_id) is not int or msg_id < 1 or data.get("bridge_id") != phone_id:
                        raise ValueError("Invalid phone request")
                    kind = data.get("type")
                    if kind == "flipper_home/subscribe":
                        if phone:
                            raise ValueError("Already subscribed")
                        phone = Phone(ws, msg_id)
                        previous = self.phones.get(phone_id)
                        if previous:
                            previous.close()
                            await previous.ws.close()
                        self.phones[phone_id] = phone
                    elif not phone or self.phones.get(phone_id) is not phone:
                        raise ValueError("Phone connection is no longer active")
                    if kind in ("flipper_home/subscribe", "flipper_home/update"):
                        actions = validate_actions(data.get("actions"))
                        if type(data.get("available")) is not bool:
                            raise ValueError("Invalid availability")
                        phone.actions = {a["id"]: a for a in actions}
                        phone.available = data["available"]
                        self.store.actions(phone_id, actions)
                    elif kind == "flipper_home/heartbeat":
                        if type(data.get("available")) is not bool:
                            raise ValueError("Invalid availability")
                        phone.available = data["available"]
                    elif kind == "flipper_home/result":
                        if type(data.get("success")) is not bool:
                            raise ValueError("Invalid result")
                        pending = phone.pending.get(data.get("request_id"))
                        if pending and not pending.done():
                            pending.set_result(data["success"])
                    else:
                        raise ValueError("Unknown phone command")
                    phone.last_seen = time.monotonic()
                    await ws.send_json({"id": msg_id, "type": "result", "success": True})
                except ValueError as error:
                    await ws.send_json({"id": msg_id, "type": "result", "success": False, "error": {"code": "bridge_error", "message": str(error)}})
        except (TimeoutError, ValueError, TypeError):
            pass
        finally:
            if phone:
                phone.close()
                if self.phones.get(phone_id) is phone:
                    self.phones.pop(phone_id)
            await ws.close()
        return ws

    def sync_devices(self, owner):
        return [{"id": f"{p['id']}:{a['id']}", "type": "action.devices.types.SCENE", "traits": ["action.devices.traits.Scene"],
                 "name": {"name": a["name"]}, "roomHint": a.get("room", ""), "willReportState": False,
                 "attributes": {"sceneReversible": False}, "deviceInfo": {"manufacturer": "Flipper Home", "model": "Selected Flipper button"}}
                for p in self.store.phones(owner) for a in p["actions"]]

    def device(self, owner, value):
        if not isinstance(value, str) or value.count(":") != 1:
            return None, None
        phone_id, action = value.split(":")
        allowed = next((p for p in self.store.phones(owner) if p["id"] == phone_id), None)
        if not allowed or not any(a["id"] == action for a in allowed["actions"]):
            return None, None
        return self.phones.get(phone_id), action

    async def fulfillment(self, request):
        credential = self.bearer(request, "access")
        owner = credential["owner"]
        data = await request.json()
        request_id = data.get("requestId")
        if not isinstance(request_id, str) or not 1 <= len(request_id) <= 128:
            raise web.HTTPBadRequest()
        inputs = data.get("inputs")
        if not isinstance(inputs, list) or len(inputs) != 1 or not isinstance(inputs[0], dict):
            raise web.HTTPBadRequest()
        intent = inputs[0].get("intent")
        if intent == "action.devices.SYNC":
            return web.json_response({"requestId": request_id, "payload": {"agentUserId": owner, "devices": self.sync_devices(owner)}})
        if intent == "action.devices.DISCONNECT":
            self.store.revoke_grant(credential["grant"])
            return web.json_response({})
        if intent == "action.devices.QUERY":
            devices = inputs[0].get("payload", {}).get("devices", [])
            if not isinstance(devices, list) or len(devices) > 200:
                raise web.HTTPBadRequest()
            states = {}
            for item in devices:
                device_id = item.get("id", "")
                phone, action = self.device(owner, device_id)
                states[device_id] = {"online": bool(phone and phone.online() and action in phone.actions)}
            return web.json_response({"requestId": request_id, "payload": {"devices": states}})
        if intent != "action.devices.EXECUTE":
            return web.json_response({"requestId": request_id, "payload": {"errorCode": "notSupported"}})
        commands = inputs[0].get("payload", {}).get("commands", [])
        validate_commands(commands)
        cached = self.store.execution(owner, request_id, json.dumps(data, sort_keys=True))
        if cached:
            return web.json_response(cached)
        response_commands = []
        deadline = time.monotonic() + self.settings.execute_timeout
        for group in commands:
            targets, executions = group.get("devices", []), group.get("execution", [])
            if not isinstance(targets, list) or not 1 <= len(targets) <= 50 or not isinstance(executions, list) or not 1 <= len(executions) <= 10:
                raise web.HTTPBadRequest()
            for target in targets:
                device_id = target.get("id", "")
                phone, action = self.device(owner, device_id)
                error = None
                for execution in executions:
                    if execution.get("command") != SCENE or execution.get("params", {}).get("deactivate") is not False:
                        error = "notSupported"
                    elif not phone or not phone.online() or action not in phone.actions:
                        error = "deviceOffline"
                    elif time.monotonic() >= deadline:
                        error = "deviceBusy"
                    else:
                        error = await phone.press(action, deadline-time.monotonic())
                    if error:
                        break
                response = {"ids": [device_id], "status": "ERROR" if error else "SUCCESS"}
                if error:
                    response["errorCode"] = error
                response_commands.append(response)
        response = {"requestId": request_id, "payload": {"commands": response_commands}}
        self.store.finish_execution(owner, request_id, response)
        return web.json_response(response)


def validate_actions(actions):
    if not isinstance(actions, list) or len(actions) > 200:
        raise ValueError("Select up to 200 voice buttons")
    seen = set()
    for action in actions:
        if not isinstance(action, dict) or set(action) - {"id", "name", "room"}:
            raise ValueError("Only action IDs and names are accepted")
        if not isinstance(action.get("id"), str) or not ID.fullmatch(action["id"]) or action["id"] in seen:
            raise ValueError("Invalid or duplicate action ID")
        seen.add(action["id"])
        for key, required in (("name", True), ("room", False)):
            value = action.get(key, "")
            if not isinstance(value, str) or len(value) > 80 or (required and not value.strip()) or any(ord(c) < 32 for c in value):
                raise ValueError("Invalid action name")
    return actions


def validate_commands(commands):
    if not isinstance(commands, list) or not 1 <= len(commands) <= 50:
        raise web.HTTPBadRequest()
    total = 0
    for group in commands:
        if not isinstance(group, dict):
            raise web.HTTPBadRequest()
        targets, executions = group.get("devices"), group.get("execution")
        if not isinstance(targets, list) or not 1 <= len(targets) <= 50 or not isinstance(executions, list) or not 1 <= len(executions) <= 10:
            raise web.HTTPBadRequest()
        total += len(targets) * len(executions)
        if total > 200:
            raise web.HTTPBadRequest()
        if any(not isinstance(t, dict) or not isinstance(t.get("id"), str) or len(t["id"]) > 161 for t in targets):
            raise web.HTTPBadRequest()
        if any(not isinstance(e, dict) or not isinstance(e.get("command"), str) or not isinstance(e.get("params", {}), dict) for e in executions):
            raise web.HTTPBadRequest()


@web.middleware
async def headers(request, handler):
    try:
        response = await handler(request)
    except web.HTTPException as error:
        response = web.Response(status=error.status, reason=error.reason, text=error.text, headers=error.headers)
        response.cookies.update(error.cookies)
    except (ValueError, TypeError, KeyError, AttributeError):
        response = web.Response(status=400, text="Invalid request")
    response.headers.update({"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer", "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"})
    return response


def create_app(settings, store=None):
    if not settings.public_url.startswith("https://") or not settings.project_id or len(settings.client_secret) < 32:
        raise ValueError("Set a public HTTPS URL, Google project ID and client secret of at least 32 characters")
    if store is None:
        Path(settings.database).parent.mkdir(parents=True, exist_ok=True)
        store = Store(settings.database)
    bridge = Bridge(settings, store)
    app = web.Application(client_max_size=64000, middlewares=[headers])
    app[BRIDGE_KEY] = bridge
    async def health(request):
        return web.json_response({"status": "ok"})
    app.router.add_get("/health", health)
    app.router.add_post("/api/phone/login", bridge.login)
    app.router.add_get("/api/phone/ws", bridge.websocket)
    app.router.add_get("/oauth/authorize", bridge.authorize)
    app.router.add_post("/oauth/authorize", bridge.authorize_post)
    app.router.add_post("/oauth/token", bridge.token)
    app.router.add_post("/google/fulfillment", bridge.fulfillment)
    async def shutdown(app):
        for phone in list(bridge.phones.values()):
            phone.close()
            await phone.ws.close()
    async def cleanup(app):
        store.close()
    app.on_shutdown.append(shutdown)
    app.on_cleanup.append(cleanup)
    return app


BRIDGE_KEY = web.AppKey("bridge", Bridge)
