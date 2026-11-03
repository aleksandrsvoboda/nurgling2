"""Event subscriptions: the subscribe request, delivery by subscription id, callbacks, closing."""
import threading
import time
import unittest

from base import SHORT, ServerTest

import nurgling
from nurgling import ConnectionLost, Event


class EventsTest(ServerTest):
    def setUp(self):
        super().setUp()
        self.next_sub = iter(range(7, 100))
        self.server.on("events.subscribe", lambda a, f: next(self.next_sub))
        self.server.on("events.unsubscribe", lambda a, f: True)

    def push(self, sub, kind="ui.msg", **data):
        self.server.send({"ev": kind, "sub": sub, "data": data})

    def test_filter_is_passed_through(self):
        nc = self.connect()
        nc.events("ui.new", "ui.destroy", caption="Cupboard*", wdg=12)
        f = self.server.frames("events.subscribe")[0]
        self.assertEqual(f["args"], {"kinds": ["ui.new", "ui.destroy"], "filter": {"caption": "Cupboard*", "wdg": 12}})
        self.assertNotIn("run", f)

    def test_no_kinds(self):
        nc = self.connect()
        with self.assertRaises(ValueError):
            nc.events()

    def test_delivery_by_subscription_id(self):
        nc = self.connect()
        a = nc.events("ui.msg")
        b = nc.events("chat.msg")
        self.assertEqual((a.id, b.id), (7, 8))
        self.push(8, "chat.msg", text="hi")
        self.push(7, msg="tt", wdg=3)
        self.push(99, msg="nobody listens")  # unknown subscription: dropped
        ev = a.get(timeout=SHORT)
        self.assertIsInstance(ev, Event)
        self.assertEqual(ev.kind, "ui.msg")
        self.assertEqual(ev.data, {"msg": "tt", "wdg": 3})
        self.assertEqual(ev.sub, 7)
        self.assertIsNone(a.get(timeout=0.2), "b's event and the unknown one must not reach a")
        self.assertEqual(b.get(timeout=SHORT).data["text"], "hi")

    def test_iteration_in_order(self):
        nc = self.connect()
        sub = nc.events("ui.msg")
        for i in range(20):
            self.push(sub.id, n=i)
        got = []
        for ev in sub:
            got.append(ev.data["n"])
            if len(got) == 20:
                break
        self.assertEqual(got, list(range(20)))

    def test_close_unsubscribes_and_ends_iteration(self):
        nc = self.connect()
        with nc.events("ui.msg") as sub:
            pass
        self.assertTrue(sub.closed)
        self.assertEqual(self.server.frames("events.unsubscribe")[0]["args"], {"sub": sub.id})
        self.assertEqual(list(sub), [])

    def test_on_calls_back_on_another_thread(self):
        nc = self.connect()
        got, done = [], threading.Event()

        def handler(ev):
            got.append((ev.data["n"], threading.current_thread() is threading.main_thread()))
            if len(got) == 3:
                done.set()

        sub = nc.on("ui.msg", handler, msg="tt")
        self.assertEqual(self.server.frames("events.subscribe")[0]["args"]["filter"], {"msg": "tt"})
        for i in range(3):
            self.push(sub.id, n=i, msg="tt")
        self.assertTrue(done.wait(SHORT))
        self.assertEqual(got, [(0, False), (1, False), (2, False)])
        sub.close()

    def test_handler_exception_does_not_stop_delivery(self):
        nc = self.connect()
        got, done = [], threading.Event()

        def handler(ev):
            if ev.data["n"] == 0:
                raise RuntimeError("bug in handler (expected in this test)")
            got.append(ev.data["n"])
            done.set()

        import io
        import contextlib
        with contextlib.redirect_stderr(io.StringIO()):
            sub = nc.on("ui.msg", handler)
            self.push(sub.id, n=0)
            self.push(sub.id, n=1)
            self.assertTrue(done.wait(SHORT))
            sub.close()
        self.assertEqual(got, [1])

    def test_on_thread_ends_quietly_when_the_connection_closes(self):
        nc = self.connect()
        escaped, ended = [], threading.Event()
        old_hook = threading.excepthook

        def hook(args):
            if args.thread is not None and args.thread.name.startswith("nurgling-on-"):
                escaped.append(args.exc_type)
                ended.set()
            else:
                old_hook(args)

        threading.excepthook = hook
        self.addCleanup(setattr, threading, "excepthook", old_hook)
        nc.on("ui.msg", lambda ev: None)
        threads = [t for t in threading.enumerate() if t.name.startswith("nurgling-on-")]
        nc.close()  # what every script does on exit, and what a stop does to the socket
        for t in threads:
            t.join(SHORT)
        self.assertEqual(escaped, [], "the event thread printed an uncaught traceback")

    def test_connection_loss_ends_iteration_with_connection_lost(self):
        nc = self.connect()
        sub = nc.events("ui.msg")
        threading.Timer(0.1, self.server.drop).start()
        t0 = time.monotonic()
        with self.assertRaises(ConnectionLost):
            for _ in sub:
                pass
        self.assertLess(time.monotonic() - t0, SHORT)

    def test_dropped_counter_is_visible(self):
        nc = self.connect()
        sub = nc.events("ui.msg")
        self.server.send({"ev": "ui.msg", "sub": sub.id, "data": {}, "dropped": 12})
        self.assertEqual(sub.get(timeout=SHORT)["dropped"], 12)


if __name__ == "__main__":
    unittest.main()
