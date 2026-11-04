"""Socket transport: framing, request/response matching and event routing. Stdlib only."""
from __future__ import annotations

import itertools
import json
import queue
import socket
import struct
import threading
from typing import Any, Callable, Dict, Optional

from .errors import ConnectionLost, NurglingError, Stopped, from_wire

_MAX = 16 << 20


class _Pending:
    __slots__ = ("event", "result", "error", "op")

    def __init__(self, op: str):
        self.event = threading.Event()
        self.result: Any = None
        self.error: Optional[NurglingError] = None
        self.op = op


class Transport:
    """One TCP connection to the client. Thread-safe: calls may come from any thread."""

    def __init__(self, host: str, port: int, timeout: float = 10.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(None)
        self._wlock = threading.Lock()
        self._ids = itertools.count(1)
        self._pending: Dict[int, _Pending] = {}
        self._plock = threading.Lock()
        self._subs: Dict[int, "queue.Queue"] = {}
        self._run_listeners: list = []
        self.closed = False
        self.close_reason: Optional[NurglingError] = None
        self.ended_runs: set = set()
        self._reader = threading.Thread(target=self._read_loop, name="nurgling-reader", daemon=True)
        self._reader.start()

    # ------------------------------------------------------------------ frames

    def _send(self, frame: dict) -> None:
        data = json.dumps(frame, separators=(",", ":"), default=_json_default).encode("utf-8")
        with self._wlock:
            self.sock.sendall(struct.pack(">I", len(data)) + data)

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return bytes(buf)

    def _read_loop(self) -> None:
        try:
            while True:
                (n,) = struct.unpack(">I", self._recv_exact(4))
                if n > _MAX:
                    raise ValueError(f"frame too large: {n}")
                frame = json.loads(self._recv_exact(n).decode("utf-8"))
                self._dispatch(frame)
        except (EOFError, OSError, ValueError) as e:
            self._shutdown(ConnectionLost(f"connection to the client closed ({e.__class__.__name__})"))

    def _dispatch(self, f: dict) -> None:
        if "ev" in f:
            kind = f["ev"]
            if kind == "run.ended":
                self.ended_runs.add(f["data"]["run"])
                for fn in list(self._run_listeners):
                    fn(f["data"])
                return
            sub = f.get("sub")
            q = self._subs.get(sub) if sub is not None else None
            if q is not None:
                q.put(f)
            elif kind == "error":
                import sys
                print(f"[nurgling] client error: {f.get('data')}", file=sys.stderr)
            return
        rid = f.get("id")
        with self._plock:
            p = self._pending.pop(rid, None)
        if p is None:
            return
        if "err" in f:
            p.error = from_wire(f["err"], p.op)
        else:
            p.result = f.get("ok")
        p.event.set()

    def _shutdown(self, reason: NurglingError) -> None:
        if self.closed:
            return
        self.closed = True
        self.close_reason = reason
        with self._plock:
            pend = list(self._pending.values())
            self._pending.clear()
        for p in pend:
            p.error = reason
            p.event.set()
        for q in list(self._subs.values()):
            q.put(None)
        try:
            self.sock.close()
        except OSError:
            pass

    # ------------------------------------------------------------------ API

    def call(self, op: str, args: Optional[dict] = None, run: Optional[str] = None,
             timeout: Optional[float] = None) -> Any:
        """Sends a request and blocks until its response arrives."""
        if self.closed:
            raise self.close_reason or ConnectionLost("not connected")
        rid = next(self._ids)
        p = _Pending(op)
        with self._plock:
            self._pending[rid] = p
        frame: Dict[str, Any] = {"id": rid, "op": op, "args": args or {}}
        if run is not None:
            frame["run"] = run
        try:
            self._send(frame)
        except OSError as e:
            with self._plock:
                self._pending.pop(rid, None)
            self._shutdown(ConnectionLost(str(e)))
            raise self.close_reason
        if not p.event.wait(timeout):
            with self._plock:
                self._pending.pop(rid, None)
            from .errors import Timeout
            raise Timeout(f"no response within {timeout} s", op)
        if p.error is not None:
            raise p.error
        return p.result

    def add_subscription(self, sub: int) -> "queue.Queue":
        q: "queue.Queue" = queue.Queue()
        self._subs[sub] = q
        return q

    def remove_subscription(self, sub: int) -> None:
        q = self._subs.pop(sub, None)
        if q is not None:
            q.put(None)

    def on_run_ended(self, fn: Callable[[dict], None]) -> None:
        self._run_listeners.append(fn)

    def close(self) -> None:
        self._shutdown(Stopped("closed by the script"))


def _json_default(o: Any) -> Any:
    # Tuples, sets and objects with a snapshot dict (handles) serialize naturally.
    if isinstance(o, (set, frozenset, tuple)):
        return list(o)
    if hasattr(o, "_snapshot"):
        return o._snapshot()
    raise TypeError(f"cannot send {type(o).__name__} to the client")
