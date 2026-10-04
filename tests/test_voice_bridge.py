"""Run with python -m unittest discover -s tests; no HA instance or radio needed."""
import asyncio
import importlib.util
from pathlib import Path
import sys
import unittest

source = Path(__file__).resolve().parents[1] / "custom_components/flipper_home/bridge.py"
spec = importlib.util.spec_from_file_location("flipper_bridge", source)
bridge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bridge
spec.loader.exec_module(bridge)


class BridgeChecks(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.time = 1_000_000.0
        self.registry = bridge.BridgeRegistry(lambda: None, lambda: self.time)
        self.connection = object()
        self.events = []
        self.actions = [{"id": "off", "name": "Bedroom lights off", "room": "Bedroom"}]
        self.registry.register("phone", "owner", "Android", self.actions, self.connection, self.events.append, True)

    async def test_success_waits_for_actual_phone_confirmation(self):
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        event = self.events[-1]
        self.assertEqual(event["action_id"], "off")
        self.assertEqual(event["issued_at_ms"], int(self.time * 1000))
        self.registry.result("phone", self.connection, event["request_id"], True, "")
        await task
        self.assertFalse(self.registry.phones["phone"].pending)

    async def test_device_failure_reaches_caller(self):
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        self.registry.result("phone", self.connection, self.events[-1]["request_id"], False, "Flipper is busy")
        with self.assertRaisesRegex(bridge.BridgeError, "busy"):
            await task

    async def test_disconnected_commands_are_not_queued(self):
        self.registry.disconnect("phone", self.connection)
        with self.assertRaisesRegex(bridge.BridgeError, "offline"):
            await self.registry.press("phone", "off")
        self.assertEqual(self.events, [])

    async def test_unknown_or_removed_action_cannot_run(self):
        with self.assertRaises(bridge.BridgeError):
            await self.registry.press("phone", "other")
        self.registry.update("phone", self.connection, [], True)
        with self.assertRaises(bridge.BridgeError):
            await self.registry.press("phone", "off")
        self.assertEqual(self.events, [])

    async def test_disconnect_fails_pending_command(self):
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        self.registry.disconnect("phone", self.connection)
        with self.assertRaisesRegex(bridge.BridgeError, "disconnected"):
            await task

    async def test_concurrent_commands_reject_instead_of_waiting(self):
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        with self.assertRaisesRegex(bridge.BridgeError, "busy"):
            await self.registry.press("phone", "off")
        self.assertEqual(len(self.events), 1)
        self.registry.result("phone", self.connection, self.events[-1]["request_id"], True, "")
        await task

    async def test_other_socket_cannot_acknowledge_or_change_actions(self):
        with self.assertRaises(bridge.BridgeError):
            self.registry.update("phone", object(), [], True)
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        with self.assertRaises(bridge.BridgeError):
            self.registry.result("phone", object(), self.events[-1]["request_id"], True, "")
        self.assertFalse(task.done())
        self.registry.result("phone", self.connection, self.events[-1]["request_id"], True, "")
        await task

    async def test_other_ha_user_cannot_take_over_phone(self):
        with self.assertRaisesRegex(bridge.BridgeError, "another"):
            self.registry.register("phone", "intruder", "Other", [], object(), self.events.append, True)
        self.assertTrue(self.registry.is_available("phone", "off"))

    async def test_reconnect_rejects_old_connection_results_and_cleanup(self):
        old_connection = self.connection
        self.connection = object()
        self.registry.register("phone", "owner", "Android", self.actions, self.connection, self.events.append, True)
        self.registry.disconnect("phone", old_connection)
        self.assertTrue(self.registry.is_available("phone", "off"))
        with self.assertRaises(bridge.BridgeError):
            self.registry.heartbeat("phone", old_connection, True)

    async def test_restart_restores_names_but_not_online_state_or_requests(self):
        saved = self.registry.saved()
        self.assertNotIn("connection", str(saved))
        restored = bridge.BridgeRegistry(lambda: None)
        restored.restore(saved)
        self.assertFalse(restored.is_available("phone", "off"))
        self.assertEqual(restored.phones["phone"].actions, self.registry.phones["phone"].actions)

    async def test_expired_lease_is_offline(self):
        self.time += 46
        self.assertFalse(self.registry.is_available("phone", "off"))
        self.registry.expire()
        self.assertIsNone(self.registry.phones["phone"].connection)

    async def test_cancellation_removes_request_and_late_result_is_rejected(self):
        task = asyncio.create_task(self.registry.press("phone", "off"))
        await asyncio.sleep(0)
        request_id = self.events[-1]["request_id"]
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertFalse(self.registry.phones["phone"].pending)
        with self.assertRaises(bridge.BridgeError):
            self.registry.result("phone", self.connection, request_id, True, "")

    async def test_duplicate_action_ids_are_rejected(self):
        with self.assertRaises(bridge.BridgeError):
            self.registry.update("phone", self.connection, self.actions * 2, True)


if __name__ == "__main__":
    unittest.main()
