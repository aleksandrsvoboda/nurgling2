"""The @script entry point: outcome reporting and exit codes."""
import contextlib
import io
import os
import unittest

from base import ServerTest
from fakeserver import TOKEN

import nurgling
from nurgling import client as client_mod


class ScriptDecoratorTest(ServerTest):
    def setUp(self):
        super().setUp()
        self.server.on("run.finish", lambda a, f: None)
        os.environ.update({"NURGLING_PORT": str(self.server.port), "NURGLING_TOKEN": TOKEN, "NURGLING_RUN": "m1"})

    def run_main(self, fn):
        with self.assertRaises(SystemExit) as cm, contextlib.redirect_stderr(io.StringIO()):
            client_mod._run_main(fn, None)
        return cm.exception.code

    def test_imported_module_is_not_run(self):
        def main(nc):
            raise AssertionError("must not run on import")
        main.__module__ = "some_module"
        self.assertIs(nurgling.script(main), main)
        self.assertEqual(self.server.frames("hello"), [])

    def test_success(self):
        seen = []
        self.assertEqual(self.run_main(lambda nc: seen.append(nc.hello["run"])), 0)
        self.assertEqual(seen, ["m1"])
        self.assertEqual(self.server.frames("run.finish"), [])

    def test_returning_false_reports_failure(self):
        self.assertEqual(self.run_main(lambda nc: False), 0)
        self.assertEqual(self.server.wait_frame("run.finish")[0]["args"], {"success": False})

    def test_exception_reports_failure_and_exit_code_1(self):
        def main(nc):
            raise RuntimeError("expected in this test")
        self.assertEqual(self.run_main(main), 1)
        self.assertEqual(self.server.wait_frame("run.finish")[0]["args"], {"success": False})

    def test_stopped_exits_quietly(self):
        def main(nc):
            raise nurgling.Stopped("stop button")
        self.assertEqual(self.run_main(main), 0)
        self.assertEqual(self.server.frames("run.finish"), [])


if __name__ == "__main__":
    unittest.main()
