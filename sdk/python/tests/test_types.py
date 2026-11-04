"""Snapshot wrapping (types.wrap) and passing snapshots back to the client as handles."""
import json
import unittest

from base import ServerTest

from nurgling import Area, Gob, Inventory, Item, Widget
from nurgling.types import wrap

GOB = {"gob": 42, "name": "gfx/terobjs/trees/oak", "hash": "h", "rc": [10.5, -3.0], "a": 0.0,
       "pose": None, "attr": -1, "overlays": ["gfx/fx/fire"], "dist": 12.0}
ITEM = {"item": 9, "name": "Branch", "res": "gfx/invobjs/branch", "q": 10.0, "num": None,
        "contents": [], "stack": None, "slot": [0, 1], "size": [1, 1], "parent": 5}


class WrapTest(unittest.TestCase):
    def test_none_and_untyped_pass_through(self):
        self.assertIsNone(wrap(None, "Gob", None))
        self.assertEqual(wrap({"gob": 1}, "", None), {"gob": 1})
        self.assertNotIsInstance(wrap({"gob": 1}, "dict", None), Gob)

    def test_each_type(self):
        for rtype, cls, data in [("Gob", Gob, GOB), ("Item", Item, ITEM), ("Inventory", Inventory, {"wdg": 5}),
                                 ("Widget", Widget, {"wdg": 6}), ("Window", Widget, {"wdg": 7}),
                                 ("Area", Area, {"area": 3, "name": "Logs"})]:
            with self.subTest(rtype=rtype):
                v = wrap(dict(data), rtype, None)
                self.assertIsInstance(v, cls)
                self.assertIsInstance(v, dict)

    def test_attribute_access(self):
        g = wrap(dict(GOB), "Gob", None)
        self.assertEqual(g.name, "gfx/terobjs/trees/oak")
        self.assertEqual(g["name"], g.name)
        self.assertEqual(g.id, 42)
        self.assertIsNone(g.pose)
        self.assertTrue(g.has_overlay("fire"))
        self.assertFalse(g.has_overlay("smoke"))
        with self.assertRaises(AttributeError):
            g.nonexistent
        g.note = "mine"
        self.assertEqual(g["note"], "mine")

    def test_optional_and_lists(self):
        self.assertIsInstance(wrap(dict(GOB), "Gob|null", None), Gob)
        self.assertIsInstance(wrap(dict(GOB), "Gob | null", None), Gob)
        items = wrap([dict(ITEM), dict(ITEM, item=10)], "list[Item]", None)
        self.assertEqual([type(i) for i in items], [Item, Item])
        self.assertEqual([i.id for i in items], [9, 10])
        self.assertEqual(wrap([], "list[Gob]", None), [])

    def test_union_is_resolved_by_handle_field(self):
        self.assertIsInstance(wrap(dict(GOB), "Gob|Item", None), Gob)
        self.assertIsInstance(wrap(dict(ITEM), "Gob|Item", None), Item)
        self.assertIsInstance(wrap({"wdg": 5, "window": 4, "size": [4, 4]}, "Inventory|Window", None), Inventory)
        self.assertIsInstance(wrap({"wdg": 4, "class": "haven.Window", "caption": "Chest"}, "Inventory|Window", None), Widget)
        self.assertIs(type(wrap({"x": 1}, "Gob|Item", None)), dict, "no handle field: left as a plain dict")
        self.assertEqual(wrap([1, 2], "Coord|null", None), (1, 2))

    def test_optional_list(self):
        v = wrap([dict(GOB)], "list[Gob]|null", None)
        self.assertIsInstance(v[0], Gob)

    def test_coords_become_tuples(self):
        self.assertEqual(wrap([1.5, 2.0], "Coord", None), (1.5, 2.0))
        self.assertEqual(wrap([3, 4], "Tile", None), (3, 4))
        self.assertEqual(wrap([[1, 2], [3, 4]], "list[Coord]", None), [(1, 2), (3, 4)])

    def test_identity_is_the_handle(self):
        a = wrap(dict(GOB), "Gob", None)
        b = wrap(dict(GOB, dist=99.0, pose="chop"), "Gob", None)
        self.assertEqual(a, b, "two snapshots of the same object compare equal")
        self.assertEqual(len({a, b}), 1)
        self.assertNotEqual(a, wrap(dict(GOB, gob=43), "Gob", None))
        self.assertNotEqual(a, wrap({"item": 42}, "Item", None), "same id, different kind")

    def test_repr(self):
        self.assertIn("oak", repr(wrap(dict(GOB), "Gob", None)))

    def test_snapshot_serializes_as_plain_json(self):
        g = wrap(dict(GOB), "Gob", None)
        self.assertEqual(json.loads(json.dumps(g)), GOB)


class HandlesOnTheWireTest(ServerTest):
    def test_results_are_wrapped_with_the_client(self):
        self.server.on("ui.find", lambda a, f: [{"wdg": 11, "class": "haven.Button"}])
        nc = self.connect()
        found = nc.ui.find(cls="Button")
        self.assertIsInstance(found[0], Widget)
        self.assertIs(found[0]._client, nc)

    def test_snapshot_goes_back_with_its_handle(self):
        self.server.on("raw.click_gob", lambda a, f: None)
        nc = self.connect()
        gob = wrap(dict(GOB), "Gob", nc)
        nc.raw.click_gob(gob, button=1)
        args = self.server.frames("raw.click_gob")[0]["args"]
        self.assertEqual(args["gob"]["gob"], 42, "the client finds the live object by this field")
        self.assertEqual(args["button"], 1)
        self.assertNotIn("mods", args)

    def test_widget_close_sends_wdgmsg(self):
        self.server.on("ui.wdgmsg", lambda a, f: None)
        nc = self.connect()
        w = wrap({"wdg": 77, "class": "haven.Window", "caption": "Cupboard"}, "Window", nc)
        w.close()
        args = self.server.frames("ui.wdgmsg")[0]["args"]
        self.assertEqual(args["wdg"]["wdg"], 77)
        self.assertEqual(args["msg"], "close")


if __name__ == "__main__":
    unittest.main()
