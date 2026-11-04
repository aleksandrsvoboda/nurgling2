"""Python SDK for the Nurgling2 Haven & Hearth client's scripting API.

    from nurgling import script

    @script
    def main(nc):
        for tree in nc.gobs.find(name="trees/", radius=200):
            print(tree.name, tree.dist)

Docs: https://aleksandrsvoboda.github.io/nurgling2/latest/
"""
from .client import API_VERSION, Client, Subscription, connect, info_file, script
from .errors import *  # noqa: F401,F403 - the exception classes are part of the public API
from .types import Area, Event, Gob, Inventory, Item, Snapshot, Widget

__version__ = "1.0.0"

__all__ = [
    "API_VERSION", "Client", "Subscription", "connect", "info_file", "script",
    "Area", "Event", "Gob", "Inventory", "Item", "Snapshot", "Widget",
]
