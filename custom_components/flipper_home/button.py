"""Buttons for explicitly enabled signals; each press waits for a phone result."""
import asyncio
from homeassistant.components.button import ButtonEntity
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers import entity_registry as er

from .bridge import BridgeError
from .const import DOMAIN, SIGNAL_UPDATED


async def async_setup_entry(hass, entry, async_add_entities):
    registry, _ = hass.data[DOMAIN]
    entities = {}
    lock = asyncio.Lock()

    async def sync():
        async with lock:
            await sync_locked()

    async def sync_locked():
        wanted = {(p.id, action_id) for p in registry.phones.values() for action_id in p.actions}
        for key in list(entities):
            if key not in wanted:
                entity = entities.pop(key)
                if entity.entity_id:
                    er.async_get(hass).async_remove(entity.entity_id)
                await entity.async_remove()
        added = []
        for bridge_id, action_id in wanted:
            if (bridge_id, action_id) not in entities:
                entity = FlipperAction(registry, bridge_id, action_id)
                entities[bridge_id, action_id] = entity
                added.append(entity)
            elif entities[bridge_id, action_id].hass:
                entities[bridge_id, action_id].async_write_ha_state()
        if added:
            async_add_entities(added)

    @callback
    def changed():
        hass.async_create_task(sync())

    entry.async_on_unload(async_dispatcher_connect(hass, SIGNAL_UPDATED, changed))
    await sync()


class FlipperAction(ButtonEntity):
    _attr_should_poll = False
    _attr_icon = "mdi:remote"

    def __init__(self, registry, bridge_id, action_id):
        self.registry, self.bridge_id, self.action_id = registry, bridge_id, action_id
        self._attr_unique_id = f"{bridge_id}_{action_id}"

    @property
    def name(self):
        phone = self.registry.phones[self.bridge_id]
        return phone.actions.get(self.action_id, {}).get("name", "Removed action")

    @property
    def available(self):
        return self.registry.is_available(self.bridge_id, self.action_id)

    @property
    def device_info(self):
        return DeviceInfo(identifiers={(DOMAIN, self.bridge_id)}, name=self.registry.phones[self.bridge_id].name,
                          manufacturer="Flipper Home", model="Android bridge")

    async def async_press(self):
        try:
            await self.registry.press(self.bridge_id, self.action_id)
        except BridgeError as err:
            raise HomeAssistantError(str(err)) from err
