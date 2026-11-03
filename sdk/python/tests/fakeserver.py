"""A stand-in for the client's ScriptServer: speaks the wire framing on a real localhost socket.

Tests register op handlers; the server records every frame it receives so tests can assert on
exactly what the SDK sent.
"""
import json
import os
import socket
import struct
import sys
import threading
import time

# Make "import nurgling" work however the tests are started.
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

TOKEN = "a" * 64
API = "1.0"
RUN = "r1"
NO_REPLY = object()


class Err(Exception):
    """Raise from a handler to answer with an error object."""

    def __init__(self, type_, msg="failed"):
        super().__init__(msg)
        self.type = type_
        self.msg = msg


def default_session(character="Tester"):
    return {"id": "s1", "character": character, "username": "tester", "headless": False, "active": True}


class FakeServer:
    """One listening socket; serves one connection at a time on a background thread."""

    def __init__(self, token=TOKEN, api=API, params=None):
        self.token = token
        self.api = api
        self.params = params or {}
        self.handlers = {}
        self.received = []          # every frame after (and including) hello
        self.received_lock = threading.Lock()
        self.got_frame = threading.Condition(self.received_lock)
        self.conn = None
        self._wlock = threading.Lock()
        self.ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.ls.bind(("127.0.0.1", 0))
        self.ls.listen(4)
        self.port = self.ls.getsockname()[1]
        self.closed = False
        self.thread = threading.Thread(target=self._accept_loop, name="fake-server", daemon=True)
        self.thread.start()

    # -------------------------------------------------------------- plumbing

    def _accept_loop(self):
        while not self.closed:
            try:
                c, _ = self.ls.accept()
            except OSError:
                return
            self.conn = c
            threading.Thread(target=self._serve, args=(c,), name="fake-conn", daemon=True).start()

    def _recv_exact(self, c, n):
        buf = b""
        while len(buf) < n:
            chunk = c.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return buf

    def _serve(self, c):
        try:
            while True:
                (n,) = struct.unpack(">I", self._recv_exact(c, 4))
                frame = json.loads(self._recv_exact(c, n).decode("utf-8"))
                with self.got_frame:
                    self.received.append(frame)
                    self.got_frame.notify_all()
                self._handle(c, frame)
        except (EOFError, OSError, ValueError):
            pass
        finally:
            c.close()  # like the client: a closed or broken connection is closed from our side too

    def send(self, frame, c=None):
        c = c or self.conn
        data = json.dumps(frame).encode("utf-8")
        with self._wlock:
            c.sendall(struct.pack(">I", len(data)) + data)

    def _handle(self, c, f):
        op = f.get("op")
        rid = f.get("id")
        if op == "hello":
            self._hello(c, f)
            return
        h = self.handlers.get(op)
        if h is None:
            self.send({"id": rid, "err": {"type": "UnknownOp", "msg": f"no op '{op}'"}}, c)
            return
        try:
            res = h(f.get("args", {}), f)
        except Err as e:
            self.send({"id": rid, "err": {"type": e.type, "msg": e.msg}}, c)
            return
        if res is NO_REPLY:
            return
        self.send({"id": rid, "ok": res}, c)

    def _hello(self, c, f):
        a = f.get("args", {})
        if a.get("token") != self.token:
            self.send({"id": f["id"], "err": {"type": "AuthFailed", "msg": "bad token"}}, c)
            self.drop()
            return
        if str(a.get("api", "")).split(".")[0] != self.api.split(".")[0]:
            self.send({"id": f["id"], "err": {"type": "NotSupported",
                                              "msg": f"SDK speaks API {a.get('api')}, client speaks {self.api}"}}, c)
            self.drop()
            return
        self.send({"id": f["id"], "ok": {
            "api": self.api, "client": "test-build", "run": a.get("run") or RUN,
            "session": default_session(a.get("session") or "Tester"),
            "java": bool(a.get("java")), "params": self.params}}, c)

    # -------------------------------------------------------------- test helpers

    def on(self, op, fn):
        """Registers ``fn(args, frame) -> result``; raise Err for an error, return NO_REPLY for silence."""
        self.handlers[op] = fn

    def frames(self, op=None):
        with self.received_lock:
            return [f for f in self.received if op is None or f.get("op") == op]

    def wait_frame(self, op, count=1, timeout=5.0):
        """Blocks until ``count`` frames of ``op`` have arrived; returns them."""
        deadline = time.monotonic() + timeout
        with self.got_frame:
            while True:
                got = [f for f in self.received if f.get("op") == op]
                if len(got) >= count:
                    return got
                left = deadline - time.monotonic()
                if left <= 0:
                    raise AssertionError(f"server did not receive {count} x {op} (got {len(got)})")
                self.got_frame.wait(left)

    def drop(self):
        """Closes the current connection, like the client does when a run ends or on shutdown."""
        c = self.conn
        if c is not None:
            try:
                c.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            c.close()

    def close(self):
        self.closed = True
        self.drop()
        try:
            self.ls.close()
        except OSError:
            pass
