"""Exceptions raised by the SDK. Each protocol error type maps to the class of the same name."""
from __future__ import annotations


class NurglingError(Exception):
    """Base class of every error the client reports."""

    type = "Internal"

    def __init__(self, msg: str = "", op: str | None = None):
        super().__init__(msg)
        self.msg = msg
        self.op = op

    def __str__(self) -> str:
        return f"{self.op}: {self.msg}" if self.op else self.msg


class Stopped(NurglingError):
    """The run was stopped (stop button, session closed, or the connection dropped). The script should exit."""
    type = "Stopped"


class Timeout(NurglingError):
    """A wait or blocking call did not finish in time."""
    type = "Timeout"


class GobGone(NurglingError):
    """The game object no longer exists."""
    type = "GobGone"


class ItemGone(NurglingError):
    """The item no longer exists."""
    type = "ItemGone"


class WidgetGone(NurglingError):
    """The window or widget no longer exists."""
    type = "WidgetGone"


class PathNotFound(NurglingError):
    """The pathfinder found no route."""
    type = "PathNotFound"


class NotFound(NurglingError):
    """Something the op needed was not found."""
    type = "NotFound"


class NoSuchArea(NurglingError):
    """No such area or specialisation."""
    type = "NoSuchArea"


class BadArgs(NurglingError):
    """Missing, unknown or wrongly typed arguments."""
    type = "BadArgs"


class ActionFailed(NurglingError):
    """A game action ran but reported failure."""
    type = "ActionFailed"


class NotSupported(NurglingError):
    """The op cannot run in this context."""
    type = "NotSupported"


class Cancelled(NurglingError):
    """The player dismissed a form or picker."""
    type = "Cancelled"


class AuthFailed(NurglingError):
    """Wrong or missing token."""
    type = "AuthFailed"


class UnknownOp(NurglingError):
    """The client does not have this op (it is older than the SDK)."""
    type = "UnknownOp"


class JavaError(NurglingError):
    """A Java exception inside a java.* call."""
    type = "JavaError"


class Internal(NurglingError):
    """An unexpected error inside the client."""
    type = "Internal"


class ConnectionLost(Stopped):
    """The socket to the client closed."""
    type = "ConnectionLost"


_BY_TYPE = {c.type: c for c in [
    Stopped, Timeout, GobGone, ItemGone, WidgetGone, PathNotFound, NotFound, NoSuchArea, BadArgs,
    ActionFailed, NotSupported, Cancelled, AuthFailed, UnknownOp, JavaError, Internal,
]}


def from_wire(err: dict, op: str | None = None) -> NurglingError:
    cls = _BY_TYPE.get(err.get("type"), Internal)
    return cls(err.get("msg", ""), op)
