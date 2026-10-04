"""Opt-in Flipper actions over an authenticated Home Assistant WebSocket."""
from datetime import timedelta

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.const import Platform
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.storage import Store

from .bridge import BridgeError, BridgeRegistry
from .const import DOMAIN, SIGNAL_UPDATED

ID = vol.All(str, vol.Match(r"^[A-Za-z0-9_-]{1,80}$"))
NAME = vol.All(str, vol.Length(min=1, max=80))
ACTION = vol.Schema({vol.Required("id"): ID, vol.Required("name"): NAME, vol.Optional("room", default=""): vol.All(str, vol.Length(max=80))})
ACTIONS = vol.All([ACTION], vol.Length(max=200))


async def async_setup(hass, config):
    for handler in (subscribe, update, heartbeat, result):
        websocket_api.async_register_command(hass, handler)
    return True


async def async_setup_entry(hass, entry):
    store = Store(hass, 1, DOMAIN)
    registry = BridgeRegistry(lambda: async_dispatcher_send(hass, SIGNAL_UPDATED))
    registry.restore(await store.async_load() or [])
    hass.data[DOMAIN] = (registry, store)
    entry.async_on_unload(async_track_time_interval(hass, lambda now: registry.expire(), timedelta(seconds=15)))
    await hass.config_entries.async_forward_entry_setups(entry, [Platform.BUTTON])
    return True


async def async_unload_entry(hass, entry):
    if not await hass.config_entries.async_unload_platforms(entry, [Platform.BUTTON]):
        return False
    registry, _ = hass.data.pop(DOMAIN)
    for phone in registry.phones.values():
        registry.disconnect(phone.id, phone.connection)
    return True


def get_registry(hass):
    if DOMAIN not in hass.data:
        raise BridgeError("Add the Flipper Home integration in Devices & services first")
    return hass.data[DOMAIN]


@websocket_api.websocket_command({
    vol.Required("type"): "flipper_home/subscribe",
    vol.Required("bridge_id"): ID,
    vol.Required("name"): NAME,
    vol.Required("actions"): ACTIONS,
    vol.Required("available"): bool,
})
@websocket_api.async_response
async def subscribe(hass, connection, msg):
    try:
        registry, store = get_registry(hass)
        registry.register(msg["bridge_id"], connection.user.id, msg["name"], msg["actions"], connection,
                          lambda event: connection.send_event(msg["id"], event), msg["available"])
        connection.subscriptions[msg["id"]] = lambda: registry.disconnect(msg["bridge_id"], connection)
        await store.async_save(registry.saved())
        connection.send_result(msg["id"])
    except BridgeError as err:
        connection.send_error(msg["id"], "bridge_error", str(err))


@websocket_api.websocket_command({
    vol.Required("type"): "flipper_home/update",
    vol.Required("bridge_id"): ID,
    vol.Required("actions"): ACTIONS,
    vol.Required("available"): bool,
})
@websocket_api.async_response
async def update(hass, connection, msg):
    try:
        registry, store = get_registry(hass)
        registry.update(msg["bridge_id"], connection, msg["actions"], msg["available"])
        await store.async_save(registry.saved())
        connection.send_result(msg["id"])
    except BridgeError as err:
        connection.send_error(msg["id"], "bridge_error", str(err))


@callback
@websocket_api.websocket_command({
    vol.Required("type"): "flipper_home/heartbeat",
    vol.Required("bridge_id"): ID,
    vol.Required("available"): bool,
})
def heartbeat(hass, connection, msg):
    try:
        registry, _ = get_registry(hass)
        registry.heartbeat(msg["bridge_id"], connection, msg["available"])
        connection.send_result(msg["id"])
    except BridgeError as err:
        connection.send_error(msg["id"], "bridge_error", str(err))


@callback
@websocket_api.websocket_command({
    vol.Required("type"): "flipper_home/result",
    vol.Required("bridge_id"): ID,
    vol.Required("request_id"): ID,
    vol.Required("success"): bool,
    vol.Optional("message", default=""): vol.All(str, vol.Length(max=300)),
})
def result(hass, connection, msg):
    try:
        registry, _ = get_registry(hass)
        registry.result(msg["bridge_id"], connection, msg["request_id"], msg["success"], msg["message"])
        connection.send_result(msg["id"])
    except BridgeError as err:
        connection.send_error(msg["id"], "bridge_error", str(err))
