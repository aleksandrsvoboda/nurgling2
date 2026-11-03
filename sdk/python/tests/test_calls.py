"""Requests and responses: matching under concurrency, argument cleaning, errors, timeouts."""
import threading
import time
import unittest

from base import SHORT, ServerTest
from fakeserver import NO_REPLY, Err

import nurgling
from nurgling import errors as E


class ConcurrencyTest(ServerTest):
    def test_out_of_order_responses_reach_the_right_caller(self):
        # The server holds requests and answers them newest first, so ids must be matched, not order.
        held = []
        lock = threading.Lock()
        threads_n, calls_n = 8, 25
        total = threads_n * calls_n

        def echo(args, frame):
            with lock:
                held.append(frame)
                if len(held) % 10 == 0 or len(held) == total:
                    batch = held[:]
                    del held[:]
                else:
                    batch = []
            for f in reversed(batch):
                self.server.send({"id": f["id"], "ok": f["args"]["v"]})
            return NO_REPLY

        # Answer whatever is still held after a quiet moment (when fewer than 10 are in flight).
        def flusher():
            while not self.server.closed:
                time.sleep(0.05)
                with lock:
                    batch = held[:]
                    del held[:]
                for f in reversed(batch):
                    self.server.send({"id": f["id"], "ok": f["args"]["v"]})

        threading.Thread(target=flusher, daemon=True).start()
        self.server.on("test.echo", echo)
        nc = self.connect()
        results, failures = {}, []

        def worker(t):
            for i in range(calls_n):
                v = f"{t}-{i}"
                try:
                    got = nc.call("test.echo", {"v": v}, timeout=SHORT)
                except Exception as e:  # collected and reported by the assertion below
                    failures.append(repr(e))
                    return
                results[v] = got

        ts = [threading.Thread(target=worker, args=(t,)) for t in range(threads_n)]
        for t in ts:
            t.start()
        for t in ts:
            t.join(SHORT * calls_n)
        self.assertEqual(failures, [])
        self.assertEqual(len(results), total)
        for k, v in results.items():
            self.assertEqual(k, v)
        ids = [f["id"] for f in self.server.frames("test.echo")]
        self.assertEqual(len(set(ids)), total, "every request needs its own id")


class ArgumentsTest(ServerTest):
    def test_none_arguments_are_left_out(self):
        self.server.on("test.args", lambda a, f: a)
        nc = self.connect()
        got = nc.call("test.args", {"a": 1, "b": None, "c": [None], "d": {"x": None}, "e": False, "f": 0})
        self.assertEqual(got, {"a": 1, "c": [None], "d": {"x": None}, "e": False, "f": 0})

    def test_generated_wrappers_drop_unset_optionals(self):
        self.server.on("ui.tree", lambda a, f: {"wdg": 1, "class": "x", "children": []})
        nc = self.connect()
        nc.ui.tree()
        nc.ui.tree(depth=1)
        frames = self.server.frames("ui.tree")
        self.assertEqual(frames[0]["args"], {})
        self.assertEqual(frames[1]["args"], {"depth": 1})

    def test_no_args_sends_empty_object(self):
        nc = self.connect()
        nc.core.ping()
        self.assertEqual(self.server.frames("core.ping")[0]["args"], {})

    def test_tuples_and_sets_serialize_as_lists(self):
        self.server.on("test.args", lambda a, f: a)
        nc = self.connect()
        got = nc.call("test.args", {"pos": (1.5, -2.0), "ids": {7}})
        self.assertEqual(got, {"pos": [1.5, -2.0], "ids": [7]})

    def test_unserializable_argument(self):
        nc = self.connect()
        with self.assertRaises(TypeError):
            nc.call("test.args", {"x": object()})
        # The connection survives a refused request.
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)


class ErrorMappingTest(ServerTest):
    def test_each_wire_type_raises_its_class(self):
        nc = self.connect()
        for type_, cls in E._BY_TYPE.items():
            def fail(a, f, t=type_):
                raise Err(t, "boom " + t)
            self.server.on("test.fail", fail)
            with self.subTest(type=type_):
                with self.assertRaises(cls) as cm:
                    nc.call("test.fail", timeout=SHORT)
                self.assertIs(type(cm.exception), cls)
                self.assertEqual(cm.exception.type, type_)
                self.assertEqual(cm.exception.msg, "boom " + type_)
                self.assertEqual(cm.exception.op, "test.fail")
                self.assertEqual(str(cm.exception), "test.fail: boom " + type_)
                self.assertIsInstance(cm.exception, nurgling.NurglingError)

    def test_unknown_type_becomes_internal(self):
        def fail(a, f):
            raise Err("BrandNewError", "from a newer client")
        self.server.on("test.fail", fail)
        nc = self.connect()
        with self.assertRaises(nurgling.Internal):
            nc.call("test.fail", timeout=SHORT)

    def test_unknown_op(self):
        nc = self.connect()
        with self.assertRaises(nurgling.UnknownOp):
            nc.call("gobs.teleport", timeout=SHORT)

    def test_error_keeps_connection_usable(self):
        nc = self.connect()
        with self.assertRaises(nurgling.UnknownOp):
            nc.call("nope", timeout=SHORT)
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)

    def test_connection_lost_is_a_stop(self):
        self.assertTrue(issubclass(nurgling.ConnectionLost, nurgling.Stopped))


class TimeoutTest(ServerTest):
    def test_local_timeout(self):
        self.server.on("test.silent", lambda a, f: NO_REPLY)
        nc = self.connect()
        t0 = time.monotonic()
        with self.assertRaises(nurgling.Timeout) as cm:
            nc.call("test.silent", timeout=0.3)
        self.assertLess(time.monotonic() - t0, SHORT)
        self.assertEqual(cm.exception.op, "test.silent")
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)

    def test_late_response_after_timeout_is_ignored(self):
        frames = []
        self.server.on("test.slow", lambda a, f: frames.append(f) or NO_REPLY)
        nc = self.connect()
        with self.assertRaises(nurgling.Timeout):
            nc.call("test.slow", timeout=0.2)
        self.server.send({"id": frames[0]["id"], "ok": "late"})
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)


if __name__ == "__main__":
    unittest.main()
