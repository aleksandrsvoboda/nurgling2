"""The client object scripts talk to (conventionally called ``nc``)."""
from __future__ import annotations

import json
import os
import queue
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional

from . import _generated
from ._transport import Transport
from .errors import NotFound, NurglingError, Stopped
from .types import Event, wrap

API_VERSION = "1.0"


def _plain(v: Any) -> Any:
    """Snapshots go back to the client as plain dicts (their handle field is what matters)."""
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in dict.items(v)}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


class Subscription:
    """An event stream. Iterate it, or call :meth:`get`; close it (or use ``with``) to stop delivery."""

    def __init__(self, client: "Client", sub: int, q: "queue.Queue"):
        self._client = client
        self.id = sub
        self._q = q
        self.closed = False

    def get(self, timeout: Optional[float] = None) -> Optional[Event]:
        """The next event, or None after ``timeout`` seconds (or when the stream closed)."""
        try:
            f = self._q.get(timeout=timeout)
        except queue.Empty:
            return None
        if f is None:
            self.closed = True
            return None
        return Event(f, self._client)

    def __iter__(self) -> Iterator[Event]:
        while not self.closed:
            ev = self.get()
            if ev is None:
                if self._client._transport.closed:
                    raise self._client._transport.close_reason or Stopped("connection closed")
                return
            yield ev

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        self._client._transport.remove_subscription(self.id)
        if not self._client._transport.closed:
            try:
                self._client._transport.call("events.unsubscribe", {"sub": self.id})
            except NurglingError:
                pass

    def __enter__(self) -> "Subscription":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()


class Client(_generated.Namespaces):
    """A connection bound to one run (one bot thread on one session).

    Every namespace of the API is an attribute: ``nc.gobs.find(...)``, ``nc.actions.path_to(...)``.
    ``nc.session(name)`` returns another Client for a different character on the same connection.
    """

    def __init__(self, transport: Transport, run: Optional[str], hello: dict):
        self._transport = transport
        self._run = run
        self.hello = hello
        self.session_info: dict = hello.get("session", {})
        _generated.Namespaces.__init__(self)

    # ------------------------------------------------------------------ calls

    def call(self, op: str, args: Optional[Dict[str, Any]] = None, rtype: str = "", timeout: Optional[float] = None) -> Any:
        """Calls any op by name. Arguments that are None are left out (the op's default applies)."""
        clean = {k: _plain(v) for k, v in (args or {}).items() if v is not None}
        run = self._run if self._run is not None else self.hello.get("run")
        if run is not None and run in self._transport.ended_runs:
            raise Stopped("this run has ended", op)
        res = self._transport.call(op, clean, self._run, timeout)
        return wrap(res, rtype, self)

    # ------------------------------------------------------------------ sessions

    def session(self, name: str) -> "Client":
        """A client for another logged-in character, sharing this connection (multi-session scripts).

        The client starts a separate bot thread on that session; stop it from that session's Bot Status
        strip, or with :meth:`close_session`."""
        r = self._transport.call("runs.open", {"session": name})
        return Client(self._transport, r["run"], {"session": r["session"], "run": r["run"]})

    def close_session(self) -> None:
        """Ends this client's run if it was opened with :meth:`session`."""
        if self._run is not None:
            self._transport.call("runs.close", {"run": self._run})

    @property
    def character(self) -> Optional[str]:
        return self.session_info.get("character")

    @property
    def stopped(self) -> bool:
        """True once the run has been stopped or the connection closed."""
        t = self._transport
        return t.closed or (self._run is not None and self._run in t.ended_runs) or \
            (self._run is None and self.hello.get("run") in t.ended_runs)

    # ------------------------------------------------------------------ events

    def events(self, *kinds: str, **filter: Any) -> Subscription:
        """Subscribes to events. ``nc.events("ui.new", caption="Cupboard*")``.

        Keyword arguments filter on fields of the event data; a string ending in ``*`` matches a prefix."""
        if not kinds:
            raise ValueError("give at least one event kind, e.g. 'ui.msg' or 'ui.*'")
        sub = self._transport.call("events.subscribe", {"kinds": list(kinds), "filter": filter}, self._run)
        q = self._transport.add_subscription(sub)
        return Subscription(self, sub, q)

    def on(self, kind: str, fn: Callable[[Event], Any], **filter: Any) -> Subscription:
        """Calls ``fn(event)`` on a background thread for every matching event. Returns the subscription."""
        sub = self.events(kind, **filter)

        def pump() -> None:
            try:
                for ev in sub:
                    try:
                        fn(ev)
                    except Stopped:
                        return
                    except Exception:  # a handler bug must not kill the event thread silently
                        import traceback
                        traceback.print_exc()
            except Stopped:
                return  # the run ended or the connection closed: the normal way out

        threading.Thread(target=pump, name=f"nurgling-on-{kind}", daemon=True).start()
        return sub

    # ------------------------------------------------------------------ conveniences

    def msg(self, text: str) -> None:
        """Shows a message in the game's message area."""
        self.chat.notify(text)

    def sleep(self, seconds: float) -> None:
        """Waits in game time (stops promptly if the run is stopped)."""
        self.wait.seconds(seconds)

    def close(self) -> None:
        """Closes the connection: the client ends this script's runs."""
        self._transport.close()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"<nurgling.Client {self.character!r} run={self._run or self.hello.get('run')}>"


# ---------------------------------------------------------------------- connecting

def info_file() -> Path:
    """Where the client writes its port and token: %APPDATA%/Haven and Hearth/scripting.json, else ~/.haven/scripting.json."""
    env = os.environ.get("NURGLING_INFO")
    if env:
        return Path(env)
    appdata = os.environ.get("APPDATA")
    if appdata and (Path(appdata) / "Haven and Hearth").is_dir():
        return Path(appdata) / "Haven and Hearth" / "scripting.json"
    return Path.home() / ".haven" / "scripting.json"


def connect(session: Optional[str] = None, *, java: Optional[bool] = None, name: Optional[str] = None,
            host: str = "127.0.0.1", port: Optional[int] = None, token: Optional[str] = None) -> Client:
    """Connects to the running client.

    Scripts started from the client's Scripts menu connect to the run the client prepared (the
    environment says which). Anything else attaches: it gets its own row in the Bot Status strip of
    ``session`` (a character name; default: the visible tab).

    ``java=True`` enables the layer-4 ``nc.java`` ops (no stability guarantees).
    """
    run_id = os.environ.get("NURGLING_RUN")
    if port is None and os.environ.get("NURGLING_PORT"):
        port = int(os.environ["NURGLING_PORT"])
        token = token or os.environ.get("NURGLING_TOKEN")
    if java is None:
        java = os.environ.get("NURGLING_JAVA") == "1"
    if port is None or token is None:
        f = info_file()
        if not f.exists():
            raise NotFound(f"{f} not found: is the client running with scripting enabled (Options > Scripting)?")
        info = json.loads(f.read_text(encoding="utf-8"))
        port = port or info["port"]
        token = token or info["token"]
        run_id = None
    if name is None:
        name = os.environ.get("NURGLING_SCRIPT") or Path(sys.argv[0]).name or "python"
    t = Transport(host, int(port))
    args: Dict[str, Any] = {"token": token, "api": API_VERSION, "client": name, "java": bool(java)}
    if run_id:
        args["run"] = run_id
    elif session:
        args["session"] = session
    hello = t.call("hello", args, timeout=15)
    return Client(t, None, hello)


def script(fn: Optional[Callable] = None, *, java: Optional[bool] = None) -> Callable:
    """Decorator for a script's entry point: ``@script def main(nc): ...``.

    When the file is run directly, connects, calls the function, reports the outcome and exits.
    A stop from the client ends the script quietly; an exception prints its traceback and marks
    the run as failed (so a scenario step running this script fails too)."""

    def deco(f: Callable) -> Callable:
        if f.__module__ == "__main__":
            _run_main(f, java)
        return f

    return deco(fn) if fn is not None else deco


def _run_main(f: Callable, java: Optional[bool]) -> None:
    nc = connect(java=java)
    code = 0
    try:
        result = f(nc)
        if result is False:
            nc.run.finish(success=False)
    except Stopped:
        code = 0
    except KeyboardInterrupt:
        code = 130
    except Exception:
        import traceback
        traceback.print_exc()
        code = 1
        try:
            nc.run.finish(success=False)
        except NurglingError:
            pass
    finally:
        nc.close()
    sys.exit(code)
