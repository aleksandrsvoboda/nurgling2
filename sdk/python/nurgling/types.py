"""Snapshot types returned by the client.

Every snapshot is a ``dict`` (so it prints, compares and serializes like the JSON it came from)
with attribute access on top: ``gob.name`` is ``gob["name"]``. Snapshots are copies taken at the
time of the call; pass one back to any op and the client uses its handle (``gob``, ``item``,
``wdg`` or ``area`` field) to find the live object.
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, List, Optional, Tuple

if TYPE_CHECKING:  # pragma: no cover
    from .client import Client

Coord = Tuple[float, float]
Tile = Tuple[int, int]
Rect = Tuple[Coord, Coord]


class Snapshot(dict):
    """Base class: a dict with attribute access and a reference to the client that produced it."""

    _handle_key = ""

    def __init__(self, data: dict, client: "Optional[Client]" = None):
        super().__init__(data)
        object.__setattr__(self, "_client", client)

    def __getattr__(self, key: str) -> Any:
        try:
            return self[key]
        except KeyError:
            raise AttributeError(f"{type(self).__name__} has no field '{key}'") from None

    def __setattr__(self, key: str, value: Any) -> None:
        self[key] = value

    @property
    def id(self) -> int:
        """The handle id (the value of the ``gob``, ``item``, ``wdg`` or ``area`` field)."""
        return self[self._handle_key]

    def _snapshot(self) -> dict:
        return dict(self)

    def __repr__(self) -> str:
        label = self.get("name") or self.get("caption") or self.get("class")
        return f"<{type(self).__name__} {self.id} {label!r}>"

    def __hash__(self) -> int:  # handles can live in sets and dict keys
        return hash((type(self).__name__, self.id))

    def __eq__(self, other: object) -> bool:
        if isinstance(other, Snapshot):
            return type(self) is type(other) and self.id == other.id
        return dict.__eq__(self, other)


class Gob(Snapshot):
    """A game object. Fields: gob, name, hash, rc, a, pose, attr, overlays, dist."""

    _handle_key = "gob"

    def refresh(self) -> "Optional[Gob]":
        """A fresh snapshot, or None if the object is gone."""
        return self._client.gobs.get(self)

    def exists(self) -> bool:
        return self.refresh() is not None

    def has_overlay(self, text: str) -> bool:
        """True if any overlay resource name contains ``text``."""
        return any(text in o for o in self.get("overlays", []))


class Item(Snapshot):
    """An inventory item. Fields: item, name, res, q, num, contents, stack, slot, size, parent."""

    _handle_key = "item"

    def refresh(self) -> "Optional[Item]":
        return self._client.inv.item(self)

    def take(self) -> None:
        """Picks the item up into the hand and waits until it is there."""
        self._client.actions.take_to_hand(self)

    def transfer(self) -> None:
        """Shift-click: move it to the other open inventory."""
        self._client.raw.item_transfer(self)

    def drop(self) -> None:
        """Drop it on the ground."""
        self._client.raw.item_drop(self)

    def interact(self, option: str) -> None:
        """Right-click the item and choose a flower-menu option, e.g. "Eat" or "Split"."""
        self._client.actions.flower_item(self, option)


class Inventory(Snapshot):
    """An open inventory. Fields: wdg, window, size."""

    _handle_key = "wdg"

    # Not called items(): that would shadow dict.items(), which json uses to send the snapshot.
    def contents(self, name: Any = None) -> List[Item]:
        """The items in this inventory, optionally only those matching ``name``."""
        return self._client.inv.items(inventory=self, name=name)

    def free_space(self) -> int:
        return self._client.inv.free_space(inventory=self)


class Widget(Snapshot):
    """Any UI widget. Fields: wdg, class, caption (windows), parent, visible, c, sz."""

    _handle_key = "wdg"

    def close(self) -> None:
        self._client.ui.wdgmsg(self, "close")


class Area(Snapshot):
    """A saved area. Fields: area, name, specs, rect, in, out."""

    _handle_key = "area"

    def gobs(self, name: Any = None) -> List[Gob]:
        return self._client.gobs.find(name=name, area=self)


class Event(Snapshot):
    """A pushed event. Fields: ev (kind), sub (subscription id), data."""

    _handle_key = "sub"

    @property
    def kind(self) -> str:
        return self["ev"]

    def __repr__(self) -> str:
        return f"<Event {self['ev']} {self.get('data')!r}>"


def _pick(value: Any, parts: List[str]) -> str:
    """Chooses the member of a union return type that a value is, by its handle field."""
    if isinstance(value, dict):
        for key, name in (("gob", "Gob"), ("item", "Item"), ("area", "Area")):
            if key in value and name in parts:
                return name
        if "wdg" in value:
            if "Inventory" in parts and "class" not in value:
                return "Inventory"
            for name in ("Widget", "Window"):
                if name in parts:
                    return name
    if isinstance(value, list):
        for name in parts:
            if name.startswith("list[") or name in ("Coord", "Tile", "Rect"):
                return name
    return ""


TYPES = {"Gob": Gob, "Item": Item, "Inventory": Inventory, "Widget": Widget, "Window": Widget, "Area": Area}


def wrap(value: Any, rtype: str, client: "Client") -> Any:
    """Converts a decoded JSON result to SDK types following the schema's return type notation."""
    if value is None or not rtype:
        return value
    t = rtype.replace(" ", "")
    while t.endswith("|null"):
        t = t[:-5]
    if "|" in t and not t.startswith("list["):
        parts = [p for p in t.split("|") if p != "null"]
        t = parts[0] if len(parts) == 1 else _pick(value, parts)
    if t.startswith("list[") and t.endswith("]") and isinstance(value, list):
        inner = t[5:-1]
        return [wrap(v, inner, client) for v in value]
    cls = TYPES.get(t)
    if cls is not None and isinstance(value, dict):
        return cls(value, client)
    if t in ("Coord", "Tile") and isinstance(value, list):
        return tuple(value)
    return value
