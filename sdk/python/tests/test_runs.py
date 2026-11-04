"""Runs ending, the connection dropping, and multi-session routing with the ``run`` field."""
import threading
import time
import unittest

from base import SHORT, ServerTest
from fakeserver import NO_REPLY, RUN, Err, default_session

import nurgling
from nurgling import ConnectionLost, Stopped


class PrimaryRunEndedTest(ServerTest):
    def test_pending_call_fails_with_stopped(self):
        # What the client does when the stop button is pressed mid-call: fail what it owed, announce, close.
        def walk(a, f):
            def stop():
                time.sleep(0.1)
                self.server.send({"id": f["id"], "err": {"type": "Stopped", "msg": "the run was stopped"}})
                self.server.send({"ev": "run.ended", "data": {"run": RUN, "stopped": True}})
                self.server.drop()
            threading.Thread(target=stop, daemon=True).start()
            return NO_REPLY
        self.server.on("test.walk", walk)
        nc = self.connect()
        with self.assertRaises(Stopped) as cm:
            nc.call("test.walk", timeout=SHORT)
        self.assertNotIsInstance(cm.exception, ConnectionLost, "the server's own Stopped error comes first")

    def test_stopped_property_after_run_ended(self):
        nc = self.connect()
        self.server.send({"ev": "run.ended", "data": {"run": RUN, "stopped": True}})
        deadline = time.monotonic() + SHORT
        while not nc.stopped and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(nc.stopped)

    def test_call_after_run_ended_raises_stopped(self):
        # The client announces run.ended before it closes the socket; a call in between must not hang.
        # nc.stopped is already True here, but the primary client still sends the request and waits.
        nc = self.connect()
        self.server.on("test.silent", lambda a, f: NO_REPLY)
        self.server.send({"ev": "run.ended", "data": {"run": RUN, "stopped": True}})
        deadline = time.monotonic() + SHORT
        while not nc.stopped and time.monotonic() < deadline:
            time.sleep(0.01)
        with self.assertRaises(Stopped):
            nc.call("test.silent", timeout=1.0)

    def test_run_ended_then_close_ends_everything(self):
        nc = self.connect()
        self.server.send({"ev": "run.ended", "data": {"run": RUN, "stopped": True}})
        self.server.drop()
        deadline = time.monotonic() + SHORT
        while not nc._transport.closed and time.monotonic() < deadline:
            time.sleep(0.01)
        with self.assertRaises(Stopped):
            nc.core.ping(_timeout=SHORT)


class ConnectionLostTest(ServerTest):
    def test_pending_call_gets_connection_lost(self):
        def hang_up(a, f):
            threading.Timer(0.1, self.server.drop).start()
            return NO_REPLY
        self.server.on("test.hang", hang_up)
        nc = self.connect()
        t0 = time.monotonic()
        with self.assertRaises(ConnectionLost):
            nc.call("test.hang", timeout=SHORT)
        self.assertLess(time.monotonic() - t0, SHORT)
        self.assertTrue(nc.stopped)

    def test_calls_after_loss_fail_fast(self):
        nc = self.connect()
        self.server.drop()
        deadline = time.monotonic() + SHORT
        while not nc._transport.closed and time.monotonic() < deadline:
            time.sleep(0.01)
        t0 = time.monotonic()
        with self.assertRaises(ConnectionLost):
            nc.core.ping()
        self.assertLess(time.monotonic() - t0, 0.5)

    def test_many_waiters_all_released(self):
        self.server.on("test.hang", lambda a, f: NO_REPLY)
        nc = self.connect()
        errors = []

        def waiter():
            try:
                nc.call("test.hang", timeout=SHORT)
            except nurgling.NurglingError as e:
                errors.append(type(e))

        ts = [threading.Thread(target=waiter) for _ in range(5)]
        for t in ts:
            t.start()
        self.server.wait_frame("test.hang", 5)
        self.server.drop()
        for t in ts:
            t.join(SHORT * 2)
        self.assertEqual(errors, [ConnectionLost] * 5)


class MultiSessionTest(ServerTest):
    def setUp(self):
        super().setUp()
        self.server.on("runs.open", lambda a, f: {"run": "r2", "session": default_session(a["session"])})
        self.server.on("runs.close", lambda a, f: True)

    def test_session_opens_a_run(self):
        nc = self.connect()
        alt = nc.session("Alt")
        opened = self.server.frames("runs.open")[0]
        self.assertEqual(opened["args"], {"session": "Alt"})
        self.assertNotIn("run", opened, "runs.open goes to the connection's primary run")
        self.assertEqual(alt.character, "Alt")
        self.assertIs(alt._transport, nc._transport, "sessions share one connection")

    def test_requests_carry_the_run_field(self):
        nc = self.connect()
        alt = nc.session("Alt")
        alt.core.ping()
        nc.core.ping()
        pings = self.server.frames("core.ping")
        self.assertEqual(pings[0].get("run"), "r2")
        self.assertNotIn("run", pings[1], "the primary client leaves run out")

    def test_subscription_is_bound_to_the_run(self):
        self.server.on("events.subscribe", lambda a, f: 3)
        nc = self.connect()
        alt = nc.session("Alt")
        alt.events("ui.msg")
        self.assertEqual(self.server.frames("events.subscribe")[0].get("run"), "r2")

    def test_ended_secondary_run_raises_stopped_locally(self):
        nc = self.connect()
        alt = nc.session("Alt")
        self.server.send({"ev": "run.ended", "data": {"run": "r2", "stopped": True}})
        deadline = time.monotonic() + SHORT
        while not alt.stopped and time.monotonic() < deadline:
            time.sleep(0.01)
        n = len(self.server.frames("core.ping"))
        with self.assertRaises(Stopped):
            alt.core.ping()
        self.assertEqual(len(self.server.frames("core.ping")), n, "nothing was sent for the ended run")
        self.assertFalse(nc.stopped)
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)

    def test_close_session(self):
        nc = self.connect()
        alt = nc.session("Alt")
        alt.close_session()
        self.assertEqual(self.server.frames("runs.close")[0]["args"], {"run": "r2"})

    def test_unknown_session(self):
        def no_session(a, f):
            raise Err("NotFound", "no logged-in session 'Nobody'")
        self.server.on("runs.open", no_session)
        nc = self.connect()
        with self.assertRaises(nurgling.NotFound):
            nc.session("Nobody")


if __name__ == "__main__":
    unittest.main()
