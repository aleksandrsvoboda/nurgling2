"""hello: authentication, version check, what the SDK sends, and connection discovery."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from base import SHORT, ServerTest
from fakeserver import RUN, TOKEN

import nurgling
from nurgling import AuthFailed, NotFound, NotSupported


class HandshakeTest(ServerTest):
    def test_hello_fields_and_result(self):
        nc = self.connect(name="unit.py")
        hello = self.server.frames("hello")[0]
        self.assertEqual(hello["id"], 1)
        self.assertNotIn("run", hello, "hello is not addressed to a run")
        self.assertEqual(hello["args"], {"token": TOKEN, "api": nurgling.API_VERSION, "client": "unit.py", "java": False})
        self.assertEqual(nc.hello["run"], RUN)
        self.assertEqual(nc.character, "Tester")
        self.assertEqual(nc.session_info["id"], "s1")
        self.assertFalse(nc.stopped)

    def test_attach_to_named_session(self):
        nc = self.connect(session="Karasique")
        self.assertEqual(self.server.frames("hello")[0]["args"]["session"], "Karasique")
        self.assertEqual(nc.character, "Karasique")

    def test_java_flag(self):
        nc = self.connect(java=True)
        self.assertIs(self.server.frames("hello")[0]["args"]["java"], True)
        self.assertTrue(nc.hello["java"])

    def test_bad_token(self):
        with self.assertRaises(AuthFailed) as cm:
            nurgling.connect(port=self.server.port, token="wrong")
        self.assertEqual(cm.exception.op, "hello")
        self.assertIn("bad token", str(cm.exception))

    def test_major_version_mismatch(self):
        self.server.api = "2.0"
        with self.assertRaises(NotSupported) as cm:
            nurgling.connect(port=self.server.port, token=TOKEN)
        self.assertIn("2.0", cm.exception.msg)

    def test_minor_version_difference_is_accepted(self):
        self.server.api = "1.7"
        nc = self.connect()
        self.assertEqual(nc.hello["api"], "1.7")

    def test_ping_after_hello(self):
        nc = self.connect()
        self.assertEqual(nc.core.ping(_timeout=SHORT), 1234)
        self.assertEqual(self.server.frames("core.ping")[0]["id"], 2)


class ManagedLaunchTest(ServerTest):
    """Scripts started by the client get everything from the environment."""

    def test_environment_claims_the_managed_run(self):
        os.environ.update({"NURGLING_PORT": str(self.server.port), "NURGLING_TOKEN": TOKEN,
                           "NURGLING_RUN": "abc123", "NURGLING_SCRIPT": "chop.py", "NURGLING_JAVA": "1"})
        nc = nurgling.connect(session="ignored")
        self.addCleanup(nc.close)
        args = self.server.frames("hello")[0]["args"]
        self.assertEqual(args["run"], "abc123")
        self.assertNotIn("session", args, "a managed run is already bound to its session")
        self.assertEqual(args["client"], "chop.py")
        self.assertIs(args["java"], True)
        self.assertEqual(nc.hello["run"], "abc123")


class InfoFileTest(ServerTest):
    def write_info(self, path, port, token=TOKEN):
        path.write_text(json.dumps({"port": port, "token": token, "api": "1.0", "pid": 42}), encoding="utf-8")

    def test_nurgling_info_overrides_location(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "custom.json"
            os.environ["NURGLING_INFO"] = str(f)
            self.assertEqual(nurgling.info_file(), f)

    def test_connect_reads_port_and_token_from_info_file(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "scripting.json"
            self.write_info(f, self.server.port)
            os.environ["NURGLING_INFO"] = str(f)
            nc = nurgling.connect()
            self.addCleanup(nc.close)
            self.assertEqual(self.server.frames("hello")[0]["args"]["token"], TOKEN)
            self.assertEqual(nc.hello["run"], RUN)

    def test_info_file_ignores_stale_run_id(self):
        # NURGLING_RUN without NURGLING_PORT is a leftover; attaching must not claim it.
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "scripting.json"
            self.write_info(f, self.server.port)
            os.environ.update({"NURGLING_INFO": str(f), "NURGLING_RUN": "stale"})
            nc = nurgling.connect()
            self.addCleanup(nc.close)
            self.assertNotIn("run", self.server.frames("hello")[0]["args"])

    def test_missing_info_file(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["NURGLING_INFO"] = str(Path(d) / "nope.json")
            with self.assertRaises(NotFound) as cm:
                nurgling.connect()
            self.assertIn("Options > Scripting", str(cm.exception))

    def test_appdata_location(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "Haven and Hearth").mkdir()
            os.environ["APPDATA"] = d
            self.assertEqual(nurgling.info_file(), Path(d) / "Haven and Hearth" / "scripting.json")

    def test_home_fallback(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["APPDATA"] = d  # exists, but has no "Haven and Hearth" folder
            with mock.patch.object(Path, "home", return_value=Path(d) / "home"):
                self.assertEqual(nurgling.info_file(), Path(d) / "home" / ".haven" / "scripting.json")


if __name__ == "__main__":
    unittest.main()
