"""Loopback Google OAuth/HTTP/WebSocket tests; no credentials or physical devices."""
import asyncio
import importlib.util
import json
import re
import unittest
from urllib.parse import parse_qs, urlsplit

HAS_AIOHTTP = importlib.util.find_spec("aiohttp") is not None


@unittest.skipUnless(HAS_AIOHTTP, "Install google_bridge/requirements.txt for direct Google tests")
class GoogleBridgeChecks(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from aiohttp.test_utils import TestClient, TestServer
        from aiohttp import TCPConnector, ThreadedResolver
        from google_bridge.server import Settings, create_app
        from google_bridge.storage import Store
        self.store = Store(":memory:")
        self.owner = self.store.create_user("alice", "example-password-not-real")
        self.settings = Settings("https://bridge.example", "example-project", "example-client", "s"*32, execute_timeout=0.3)
        self.client = TestClient(TestServer(create_app(self.settings, self.store)), connector=TCPConnector(resolver=ThreadedResolver()))
        await self.client.start_server()
        self.access = self.store.issue("access", self.owner, 3600, grant="test-grant")
        self.header = {"Authorization": "Bearer " + self.access}
        self.sockets = []

    async def asyncTearDown(self):
        for ws in self.sockets:
            await ws.close()
        await self.client.close()

    async def login(self, phone="phone"):
        response = await self.client.post("/api/phone/login", json={"username": "alice", "password": "example-password-not-real", "bridge_id": phone, "name": "Android"})
        self.assertEqual(response.status, 200, await response.text())
        return (await response.json())["access_token"]

    async def phone(self, phone="phone", token=None):
        token = token or await self.login(phone)
        ws = await self.client.ws_connect("/api/phone/ws")
        self.sockets.append(ws)
        self.assertEqual((await ws.receive_json())["type"], "auth_required")
        await ws.send_json({"type": "auth", "access_token": token})
        self.assertEqual((await ws.receive_json())["type"], "auth_ok")
        await ws.send_json({"id": 1, "type": "flipper_home/subscribe", "bridge_id": phone,
            "name": "Android", "available": True, "actions": [{"id": "off", "name": "Bedroom lights off", "room": "Bedroom"}]})
        self.assertTrue((await ws.receive_json())["success"])
        return ws

    def command(self, request="first", device="phone:off", command="action.devices.commands.ActivateScene"):
        return {"requestId": request, "inputs": [{"intent": "action.devices.EXECUTE", "payload": {"commands": [
            {"devices": [{"id": device}], "execution": [{"command": command, "params": {"deactivate": False}}]}]}}]}

    async def execute(self, data):
        response = await self.client.post("/google/fulfillment", json=data, headers=self.header)
        self.assertEqual(response.status, 200, await response.text())
        return await response.json()

    async def acknowledge(self, ws, event, success=True):
        await ws.send_json({"id": 2, "type": "flipper_home/result", "bridge_id": "phone",
            "request_id": event["request_id"], "success": success})
        self.assertTrue((await ws.receive_json())["success"])

    async def test_login_hashes_credentials_and_separates_token_purposes(self):
        token = await self.login()
        dump = "".join(self.store.db.iterdump())
        self.assertNotIn("example-password-not-real", dump)
        self.assertNotIn(token, dump)
        response = await self.client.post("/google/fulfillment", json={"requestId": "sync", "inputs": [{"intent": "action.devices.SYNC"}]}, headers={"Authorization": "Bearer " + token})
        self.assertEqual(response.status, 401)
        bad = await self.client.post("/api/phone/login", json={"username": "alice", "password": "wrong", "bridge_id": "phone"})
        self.assertEqual(bad.status, 401)

    async def test_oauth_link_code_refresh_and_single_use(self):
        params = {"client_id": self.settings.client_id, "redirect_uri": self.settings.redirect, "response_type": "code", "state": "google-state"}
        response = await self.client.get("/oauth/authorize", params=params)
        self.assertEqual(response.status, 200)
        nonce = re.search(r'name="nonce" value="([^"]+)"', await response.text()).group(1)
        self.assertIn("Secure", response.headers["Set-Cookie"])
        form = {"nonce": nonce, "username": "alice", "password": "example-password-not-real"}
        missing_cookie = await self.client.post("/oauth/authorize", data=form, allow_redirects=False)
        self.assertEqual(missing_cookie.status, 403)
        linked = await self.client.post("/oauth/authorize", data=form, headers={"Cookie": "flipper_link=" + nonce}, allow_redirects=False)
        self.assertEqual(linked.status, 302)
        query = parse_qs(urlsplit(linked.headers["Location"]).query)
        self.assertEqual(query["state"], ["google-state"])
        data = {"grant_type": "authorization_code", "code": query["code"][0], "redirect_uri": self.settings.redirect,
            "client_id": self.settings.client_id, "client_secret": self.settings.client_secret}
        exchanged = await self.client.post("/oauth/token", data=data)
        self.assertEqual(exchanged.status, 200)
        tokens = await exchanged.json()
        self.assertIsNotNone(self.store.token(tokens["access_token"], "access"))
        self.assertIsNone(self.store.token(tokens["access_token"], "phone"))
        replay = await self.client.post("/oauth/token", data=data)
        self.assertEqual(replay.status, 400)
        refresh = await self.client.post("/oauth/token", data={"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"],
            "client_id": self.settings.client_id, "client_secret": self.settings.client_secret})
        self.assertEqual(refresh.status, 200)
        invalid = await self.client.get("/oauth/authorize", params=params | {"redirect_uri": "https://attacker.example"})
        self.assertEqual(invalid.status, 400)

    async def test_sync_exposes_only_selected_actions_and_queries_availability(self):
        ws = await self.phone()
        sync = await self.execute({"requestId": "sync", "inputs": [{"intent": "action.devices.SYNC"}]})
        device = sync["payload"]["devices"][0]
        self.assertEqual(device["id"], "phone:off")
        self.assertEqual(device["type"], "action.devices.types.SCENE")
        self.assertNotIn("path", json.dumps(sync))
        query = {"requestId": "query", "inputs": [{"intent": "action.devices.QUERY", "payload": {"devices": [{"id": "phone:off"}]}}]}
        self.assertTrue((await self.execute(query))["payload"]["devices"]["phone:off"]["online"])
        await ws.close()
        self.assertFalse((await self.execute(query))["payload"]["devices"]["phone:off"]["online"])

    async def test_execute_waits_for_phone_and_retries_do_not_retransmit(self):
        ws = await self.phone()
        task = asyncio.create_task(self.execute(self.command()))
        event = (await ws.receive_json())["event"]
        self.assertEqual(event["action_id"], "off")
        self.assertFalse(task.done())
        await self.acknowledge(ws, event)
        result = await task
        self.assertEqual(result["payload"]["commands"][0]["status"], "SUCCESS")
        self.assertEqual(await self.execute(self.command()), result)
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(ws.receive(), 0.03)

    async def test_failures_and_busy_commands_are_reported_without_queue(self):
        ws = await self.phone()
        first = asyncio.create_task(self.execute(self.command()))
        event = (await ws.receive_json())["event"]
        second = await self.execute(self.command(request="second"))
        self.assertEqual(second["payload"]["commands"][0]["errorCode"], "deviceBusy")
        await self.acknowledge(ws, event, False)
        self.assertEqual((await first)["payload"]["commands"][0]["errorCode"], "sceneCannotBeApplied")

    async def test_timeout_cancels_phone_and_never_reports_success(self):
        ws = await self.phone()
        task = asyncio.create_task(self.execute(self.command()))
        event = (await ws.receive_json())["event"]
        cancelled = (await ws.receive_json())["event"]
        self.assertEqual(cancelled["cancel_request_id"], event["request_id"])
        self.assertEqual((await task)["payload"]["commands"][0]["errorCode"], "deviceOffline")
        await self.acknowledge(ws, event, False)  # late ACK does not break the socket

    async def test_offline_unknown_and_unsupported_commands_never_send(self):
        await self.login()
        offline = await self.execute(self.command())
        self.assertEqual(offline["payload"]["commands"][0]["errorCode"], "deviceOffline")
        ws = await self.phone()
        unknown = await self.execute(self.command(request="unknown", device="phone:unknown"))
        self.assertEqual(unknown["payload"]["commands"][0]["errorCode"], "deviceOffline")
        unsupported = await self.execute(self.command(request="unsupported", command="action.devices.commands.OnOff"))
        self.assertEqual(unsupported["payload"]["commands"][0]["errorCode"], "notSupported")
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(ws.receive(), 0.03)

    async def test_another_account_cannot_control_or_register_phone(self):
        await self.phone()
        other = self.store.create_user("bob", "another-password-not-real")
        self.header = {"Authorization": "Bearer " + self.store.issue("access", other, 3600, grant="other")}
        result = await self.execute(self.command())
        self.assertEqual(result["payload"]["commands"][0]["errorCode"], "deviceOffline")
        with self.assertRaises(ValueError):
            self.store.phone("phone", other, "Other")

    async def test_invalid_mapping_and_wrong_phone_identity_are_rejected(self):
        ws = await self.phone()
        await ws.send_json({"id": 2, "type": "flipper_home/update", "bridge_id": "other", "actions": [], "available": True})
        self.assertFalse((await ws.receive_json())["success"])
        await ws.send_json({"id": 3, "type": "flipper_home/update", "bridge_id": "phone", "actions": [{"id": "bad", "name": "Bad", "path": "/ext/private.sub"}], "available": True})
        self.assertFalse((await ws.receive_json())["success"])
        from google_bridge.server import BRIDGE_KEY
        self.assertEqual(len(self.client.server.app[BRIDGE_KEY].phones["phone"].actions), 1)

    async def test_disabling_removes_scene_and_restart_does_not_replay(self):
        ws = await self.phone()
        await ws.send_json({"id": 2, "type": "flipper_home/update", "bridge_id": "phone", "actions": [], "available": True})
        self.assertTrue((await ws.receive_json())["success"])
        sync = await self.execute({"requestId": "sync", "inputs": [{"intent": "action.devices.SYNC"}]})
        self.assertEqual(sync["payload"]["devices"], [])
        data = self.command(request="interrupted")
        self.store.execution(self.owner, "interrupted", json.dumps(data, sort_keys=True))
        result = await self.execute(data)
        self.assertEqual(result["payload"]["errorCode"], "deviceBusy")

    async def test_unlink_revokes_google_grant_but_keeps_phone_credential(self):
        token = await self.login()
        result = await self.execute({"requestId": "unlink", "inputs": [{"intent": "action.devices.DISCONNECT"}]})
        self.assertEqual(result, {})
        self.assertIsNone(self.store.token(self.access, "access"))
        self.assertIsNotNone(self.store.token(token, "phone"))

    async def test_malformed_later_command_is_rejected_before_any_transmission(self):
        ws = await self.phone()
        data = self.command()
        data["inputs"][0]["payload"]["commands"].append({"devices": [{"id": "phone:off"}], "execution": ["bad"]})
        result = await self.client.post("/google/fulfillment", json=data, headers=self.header)
        self.assertEqual(result.status, 400)
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(ws.receive(), 0.03)
