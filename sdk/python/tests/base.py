"""Shared setup: a fresh fake server per test and a connected client with a clean environment."""
import os
import unittest
from unittest import mock

import fakeserver
from fakeserver import TOKEN, FakeServer

import nurgling

# Environment variables the SDK reads; tests start with none of them set.
SDK_ENV = ("NURGLING_PORT", "NURGLING_TOKEN", "NURGLING_RUN", "NURGLING_JAVA", "NURGLING_SCRIPT", "NURGLING_INFO")

# How long a test waits for something that should be instant before calling it a hang.
SHORT = 3.0


class ServerTest(unittest.TestCase):
    def setUp(self):
        env = {k: v for k, v in os.environ.items() if k not in SDK_ENV}
        patcher = mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.server = FakeServer()
        self.addCleanup(self.server.close)
        self.server.on("core.ping", lambda a, f: 1234)

    def connect(self, **kw):
        kw.setdefault("port", self.server.port)
        kw.setdefault("token", TOKEN)
        nc = nurgling.connect(**kw)
        self.addCleanup(nc.close)
        return nc


__all__ = ["ServerTest", "SHORT", "fakeserver"]
