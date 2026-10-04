"""Optional framework checks: install homeassistant==2024.12.5 on Python 3.12."""
import asyncio
import importlib.util
import os
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

HAS_HA = importlib.util.find_spec("homeassistant") is not None


@unittest.skipUnless(HAS_HA, "Home Assistant framework is not installed")
class HomeAssistantChecks(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from homeassistant.core import HomeAssistant
        from homeassistant.helpers.storage import Store
        from custom_components.flipper_home import async_setup
        from custom_components.flipper_home.bridge import BridgeRegistry
        self.directory = tempfile.TemporaryDirectory()
        # Home Assistant runs on Linux; Windows lacks its file-permission syscall.
        # Keep real storage serialization/writes, substituting only that syscall.
        self.permissions = None
        if not hasattr(os, "fchmod"):
            self.permissions = patch("os.fchmod", lambda fd, mode: None, create=True)
            self.permissions.start()
        self.hass = HomeAssistant(self.directory.name)
        self.registry = BridgeRegistry(lambda: None)
        self.store = Store(self.hass, 1, "flipper_home")
        self.hass.data["flipper_home"] = (self.registry, self.store)
        await async_setup(self.hass, {})
        self.connection = SimpleNamespace(user=SimpleNamespace(id="test-user"), subscriptions={},
            send_event=MagicMock(), send_result=MagicMock(), send_error=MagicMock())

    async def asyncTearDown(self):
        await self.hass.async_block_till_done()
        await self.hass.async_stop()
        self.directory.cleanup()
        if self.permissions:
            self.permissions.stop()

    async def subscribe(self):
        from custom_components.flipper_home import subscribe
        data = subscribe._ws_schema({"id": 1, "type": "flipper_home/subscribe", "bridge_id": "phone",
            "name": "Android", "actions": [{"id": "off", "name": "Bedroom lights off"}], "available": True})
        await subscribe.__wrapped__(self.hass, self.connection, data)
        self.connection.send_result.assert_called_with(1)
        self.connection.send_error.assert_not_called()

    async def test_registered_schemas_and_saved_subscription(self):
        import voluptuous as vol
        from custom_components.flipper_home import subscribe
        self.assertEqual(len(self.hass.data["websocket_api"]), 4)
        with self.assertRaises(vol.Invalid):
            subscribe._ws_schema({"id": 1, "type": "flipper_home/subscribe", "bridge_id": "../invalid"})
        await self.subscribe()
        saved = await self.store.async_load()
        self.assertEqual(saved[0]["owner"], "test-user")
        self.assertEqual(saved[0]["actions"]["off"]["room"], "")
        self.connection.subscriptions[1]()
        self.assertFalse(self.registry.is_available("phone", "off"))

    async def test_entity_press_waits_for_authenticated_result(self):
        from custom_components.flipper_home import result
        from custom_components.flipper_home.button import FlipperAction
        await self.subscribe()
        entity = FlipperAction(self.registry, "phone", "off")
        self.assertEqual(entity.name, "Bedroom lights off")
        self.assertTrue(entity.available)
        press = asyncio.create_task(entity.async_press())
        await asyncio.sleep(0)
        self.assertFalse(press.done())
        event = self.connection.send_event.call_args.args[1]
        result(self.hass, self.connection, {"id": 2, "request_id": event["request_id"],
            "bridge_id": "phone", "success": True, "message": ""})
        await press
        self.registry.disconnect("phone", self.connection)
        self.assertFalse(entity.available)

    async def test_dynamic_entity_add_rename_disable(self):
        from custom_components.flipper_home.button import async_setup_entry
        await self.subscribe()
        added = []
        callbacks = []
        entry = SimpleNamespace(async_on_unload=callbacks.append)
        await async_setup_entry(self.hass, entry, lambda entities: added.extend(entities))
        self.assertEqual(len(added), 1)
        entity = added[0]
        entity.async_remove = AsyncMock()
        self.registry.update("phone", self.connection, [{"id": "off", "name": "New name"}], True)
        self.assertEqual(entity.name, "New name")
        from homeassistant.helpers.dispatcher import async_dispatcher_send
        self.registry.update("phone", self.connection, [], True)
        async_dispatcher_send(self.hass, "flipper_home_updated")
        await self.hass.async_block_till_done()
        entity.async_remove.assert_awaited_once()
        for cleanup in callbacks:
            cleanup()
