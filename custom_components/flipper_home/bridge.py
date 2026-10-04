"""A phone-owned action registry with acknowledgements and no offline queue."""
import asyncio
from dataclasses import dataclass, field
import time
from typing import Callable
from uuid import uuid4


class BridgeError(Exception):
    """An action cannot be completed by the phone."""


@dataclass
class Phone:
    id: str
    owner: str
    name: str
    actions: dict[str, dict]
    connection: object | None = None
    send: Callable | None = None
    available: bool = False
    last_seen: float = 0
    pending: dict[str, asyncio.Future] = field(default_factory=dict)


class BridgeRegistry:
    def __init__(self, notify: Callable, clock: Callable = time.time):
        self.phones: dict[str, Phone] = {}
        self.notify = notify
        self.clock = clock

    def restore(self, data: list[dict]):
        for item in data:
            self.phones[item["id"]] = Phone(item["id"], item["owner"], item["name"], item["actions"])

    def saved(self):
        return [{"id": p.id, "owner": p.owner, "name": p.name, "actions": p.actions} for p in self.phones.values()]

    def register(self, bridge_id: str, owner: str, name: str, actions: list[dict], connection, send: Callable, available: bool):
        if len({a["id"] for a in actions}) != len(actions):
            raise BridgeError("Duplicate action IDs")
        phone = self.phones.get(bridge_id)
        if phone and phone.owner != owner:
            raise BridgeError("This phone belongs to another Home Assistant user")
        if phone:
            self.disconnect(bridge_id, phone.connection)
            phone.name = name
            phone.actions = {a["id"]: a for a in actions}
        else:
            phone = Phone(bridge_id, owner, name, {a["id"]: a for a in actions})
            self.phones[bridge_id] = phone
        phone.connection, phone.send = connection, send
        phone.available, phone.last_seen = available, self.clock()
        self.notify()

    def update(self, bridge_id: str, connection, actions: list[dict], available: bool):
        phone = self._owned(bridge_id, connection)
        if len({a["id"] for a in actions}) != len(actions):
            raise BridgeError("Duplicate action IDs")
        phone.actions = {a["id"]: a for a in actions}
        phone.available, phone.last_seen = available, self.clock()
        self.notify()

    def heartbeat(self, bridge_id: str, connection, available: bool):
        phone = self._owned(bridge_id, connection)
        phone.available, phone.last_seen = available, self.clock()
        self.notify()

    def _owned(self, bridge_id: str, connection):
        phone = self.phones.get(bridge_id)
        if phone is None or phone.connection is not connection or connection is None:
            raise BridgeError("This phone connection is no longer active")
        return phone

    def is_available(self, bridge_id: str, action_id: str):
        phone = self.phones.get(bridge_id)
        return bool(phone and action_id in phone.actions and phone.send and phone.available and self.clock() - phone.last_seen <= 45)

    async def press(self, bridge_id: str, action_id: str):
        if not self.is_available(bridge_id, action_id):
            raise BridgeError("Flipper Home or the Flipper is offline")
        phone = self.phones[bridge_id]
        if phone.pending:
            raise BridgeError("Flipper is busy")
        request_id = str(uuid4())
        future = asyncio.get_running_loop().create_future()
        phone.pending[request_id] = future
        try:
            phone.send({"request_id": request_id, "action_id": action_id, "issued_at_ms": int(self.clock() * 1000)})
            async with asyncio.timeout(80):
                await future
        except TimeoutError as err:
            raise BridgeError("The phone did not confirm this command; it will not be retried") from err
        finally:
            phone.pending.pop(request_id, None)

    def result(self, bridge_id: str, connection, request_id: str, success: bool, message: str):
        phone = self._owned(bridge_id, connection)
        future = phone.pending.get(request_id)
        if future is None or future.done():
            raise BridgeError("This request is no longer pending")
        if success:
            future.set_result(None)
        else:
            future.set_exception(BridgeError(message or "Flipper could not send the command"))

    def disconnect(self, bridge_id: str, connection):
        phone = self.phones.get(bridge_id)
        if phone is None or phone.connection is not connection:
            return
        phone.connection, phone.send, phone.available = None, None, False
        for future in phone.pending.values():
            if not future.done():
                future.set_exception(BridgeError("The phone disconnected during this command"))
        self.notify()

    def expire(self):
        for phone in self.phones.values():
            if phone.available and self.clock() - phone.last_seen > 45:
                self.disconnect(phone.id, phone.connection)
