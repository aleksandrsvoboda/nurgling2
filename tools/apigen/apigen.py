#!/usr/bin/env python3
"""Generates the Python SDK bindings and the API reference from the exported schema.

    ant apigen          (exports the schema from the Java annotations, then runs this)
    python tools/apigen/apigen.py [--check]

Inputs:  docs/scripting/schema/api-schema.json, docs/scripting/schema/bots.json
Outputs: sdk/python/nurgling/_generated.py
         docs/scripting/docs/reference/*.md

--check exits 1 if any output would change (used by CI to catch a stale commit).
"""
from __future__ import annotations

import json
import keyword
import sys
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "docs/scripting/schema/api-schema.json"
BOTS = ROOT / "docs/scripting/schema/bots.json"
SDK_OUT = ROOT / "sdk/python/nurgling/_generated.py"
REF_DIR = ROOT / "docs/scripting/docs/reference"

# Namespaces the SDK wraps by hand (attribute names the Client already uses).
HIDDEN_NAMESPACES = {"events"}

NAMESPACES = OrderedDict([
    ("core", ("Core", "Connection checks and the API's own description.")),
    ("sessions", ("Sessions", "Logged-in characters.")),
    ("runs", ("Runs", "Bot threads of this script; see multi-session scripts.")),
    ("run", ("This run", "Outcome and start parameters of the current run.")),
    ("events", ("Events", "Event subscriptions. Use `nc.events(...)` and `nc.on(...)` instead of these ops.")),
    ("bots", ("Bots", "Layer 1: run the client's own bots.")),
    ("scenarios", ("Scenarios", "Layer 1: run saved scenarios.")),
    ("player", ("Player", "The player character: position, stats, meters, buffs, speed.")),
    ("gobs", ("Game objects", "Find and inspect objects in the world.")),
    ("inv", ("Inventories", "Items in the player's and containers' inventories, the hand, stockpiles.")),
    ("equip", ("Equipment", "Equipped items and the belt.")),
    ("windows", ("Windows", "Open windows: find, read, close, press buttons.")),
    ("areas", ("Areas", "Saved areas (zones) and their specialisations.")),
    ("map", ("Map", "Tiles, reachability and coordinates.")),
    ("chat", ("Chat & messages", "Chat channels and the game's message area.")),
    ("actions", ("Actions", "High-level actions built on the client's own Java actions: walking, containers, items, building.")),
    ("craft", ("Crafting", "Recipes, the craft window, and menu actions.")),
    ("wait", ("Waits", "Block until something happens, with a timeout.")),
    ("combat", ("Combat", "Fight state, attacking and combat moves.")),
    ("form", ("Forms & pickers", "Script UI: settings forms, area and object pickers.")),
    ("overlay", ("Overlays", "Script UI: highlights, shapes, labels and markers drawn in the world. Removed when the run ends.")),
    ("ui", ("Widgets", "Layer 3: the widget tree and raw widget messages.")),
    ("raw", ("Raw input", "Layer 3: low-level clicks and item messages, exactly as the mouse would send them.")),
    ("java", ("Java (unstable)", "Layer 4: reflective access to the client's Java code. No stability guarantees; enable with `java=True`.")),
])

TYPE_PARAM = {
    "int": "int", "float": "float", "bool": "bool", "str": "str", "any": "Any", "dict": "Dict[str, Any]",
    "null": "None", "Gob": "GobRef", "Item": "ItemRef", "Widget": "WidgetRef", "Window": "WidgetRef",
    "Inventory": "InventoryRef", "Area": "AreaRef", "Coord": "CoordLike", "Tile": "Tile", "Rect": "Rect", "Alias": "Alias",
}
TYPE_RETURN = dict(TYPE_PARAM, Gob="Gob", Item="Item", Widget="Widget", Window="Widget", Inventory="Inventory",
                   Area="Area", Coord="Coord")


def py_type(t: str, table: dict) -> str:
    t = t.replace(" ", "")
    if "|" in t:
        parts = t.split("|")
        nonnull = [p for p in parts if p != "null"]
        inner = [py_type(p, table) for p in nonnull]
        inner = list(OrderedDict.fromkeys(inner))
        s = inner[0] if len(inner) == 1 else f"Union[{', '.join(inner)}]"
        return f"Optional[{s}]" if "null" in parts else s
    if t.startswith("list[") and t.endswith("]"):
        return f"List[{py_type(t[5:-1], table)}]"
    return table.get(t, "Any")


def ident(name: str) -> str:
    return name + "_" if keyword.iskeyword(name) else name


def indent(text: str, n: int) -> str:
    pad = " " * n
    return "\n".join(pad + line if line.strip() else "" for line in text.splitlines())


def docstring(op: dict) -> str:
    lines = [op["doc"]]
    if op["params"]:
        lines.append("")
        lines.append("Args:")
        for p in op["params"]:
            extra = f" Default: {p['default']}." if "default" in p else ""
            vals = f" One of: {', '.join(p['values'])}." if p.get("values") else ""
            lines.append(f"    {ident(p['name'])}: {p['doc']}{extra}{vals}")
    if op["returns"] != "null":
        lines.append("")
        lines.append(f"Returns: {op['returns']}" + (f" - {op['returnsDoc']}" if op.get("returnsDoc") else ""))
    if op["errors"]:
        lines.append("")
        lines.append("Raises: " + ", ".join(op["errors"]))
    if op.get("example"):
        lines.append("")
        lines.append("Example::")
        lines.append("")
        lines.append(indent(op["example"], 4))
    if op.get("unstable"):
        lines.append("")
        lines.append("Unstable (layer 4): may change or disappear in any client update.")
    if op.get("deprecated"):
        lines.append("")
        lines.append(f"Deprecated: {op['deprecated']}")
    text = "\n".join(lines).replace('"""', "'''").replace("\\", "\\\\")
    return f'"""{text}\n"""'


def gen_sdk(schema: dict) -> str:
    by_ns: "OrderedDict[str, list]" = OrderedDict()
    for op in schema["ops"]:
        ns, verb = op["name"].split(".", 1)
        by_ns.setdefault(ns, []).append((verb, op))
    out = [
        "# Generated by tools/apigen/apigen.py from docs/scripting/schema/api-schema.json. Do not edit.",
        f"# API {schema['api']}",
        "from __future__ import annotations",
        "",
        "from typing import TYPE_CHECKING, Any, Dict, List, Optional, Tuple, Union",
        "",
        "if TYPE_CHECKING:  # pragma: no cover",
        "    from .types import Area, Gob, Inventory, Item, Widget",
        "",
        "Coord = Tuple[float, float]",
        "Tile = Tuple[int, int]",
        "Rect = Tuple[Coord, Coord]",
        "Alias = Union[str, List[str], Dict[str, List[str]]]",
        'GobRef = Union["Gob", int]',
        'ItemRef = Union["Item", int]',
        'WidgetRef = Union["Widget", int]',
        'InventoryRef = Union["Inventory", int, str]',
        'AreaRef = Union["Area", int, str]',
        'CoordLike = Union[Coord, List[float], "Gob"]',
        "",
        "",
        "class _NS:",
        '    __slots__ = ("_c",)',
        "",
        "    def __init__(self, client: Any):",
        "        self._c = client",
        "",
    ]
    names = []
    for ns, ops in by_ns.items():
        title, blurb = NAMESPACES.get(ns, (ns, ""))
        cls = "_" + "".join(w.capitalize() for w in ns.split("_")) + "NS"
        names.append((ns, cls))
        out.append("")
        out.append(f"class {cls}(_NS):")
        out.append(f'    """{title}: {blurb}"""')
        for verb, op in ops:
            params = op["params"]
            req = [p for p in params if not p["optional"]]
            opt = [p for p in params if p["optional"]]
            sig = ["self"]
            for p in req:
                sig.append(f"{ident(p['name'])}: {py_type(p['type'], TYPE_PARAM)}")
            for p in opt:
                t = py_type(p["type"], TYPE_PARAM)
                t = t if t.startswith("Optional[") or t in ("Any", "None") else f"Optional[{t}]"
                sig.append(f"{ident(p['name'])}: {t} = None")
            sig.append("_timeout: Optional[float] = None")
            ret = py_type(op["returns"], TYPE_RETURN)
            out.append("")
            out.append(f"    def {ident(verb)}({', '.join(sig)}) -> {ret}:")
            out.append(indent(docstring(op), 8))
            args = ", ".join(f'"{p["name"]}": {ident(p["name"])}' for p in params)
            out.append(f'        return self._c.call("{op["name"]}", {{{args}}}, "{op["returns"]}", _timeout)')
    out.append("")
    out.append("")
    out.append("class Namespaces:")
    out.append('    """Mixin giving the Client one attribute per API namespace."""')
    out.append("")
    for ns, cls in names:
        if ns in HIDDEN_NAMESPACES:
            continue
        out.append(f"    {ident(ns)}: {cls}")
    out.append("")
    out.append("    def __init__(self) -> None:")
    for ns, cls in names:
        if ns in HIDDEN_NAMESPACES:
            continue
        out.append(f"        self.{ident(ns)} = {cls}(self)")
    out.append("")
    return "\n".join(out)


# ---------------------------------------------------------------------- docs

def md_escape(s: str) -> str:
    return s.replace("|", "\\|").replace("\n", " ")


def signature(op: dict) -> str:
    ns, verb = op["name"].split(".", 1)
    parts = []
    for p in [p for p in op["params"] if not p["optional"]] + [p for p in op["params"] if p["optional"]]:
        parts.append(ident(p["name"]) + ("=None" if p["optional"] else ""))
    return f"nc.{ns}.{ident(verb)}({', '.join(parts)}) -> {op['returns']}"


def gen_ref(schema: dict, bots: list) -> dict:
    files = {}
    by_ns: "OrderedDict[str, list]" = OrderedDict()
    for op in schema["ops"]:
        by_ns.setdefault(op["name"].split(".", 1)[0], []).append(op)
    order = [ns for ns in NAMESPACES if ns in by_ns] + [ns for ns in by_ns if ns not in NAMESPACES]

    idx = ["# API reference", "",
           f"Generated from the client's annotations (API {schema['api']}). Every op is listed with its wire name, "
           "which is also the SDK call: `gobs.find` is `nc.gobs.find(...)`.", "",
           "| Namespace | What it covers | Ops |", "|---|---|---|"]
    for ns in order:
        title, blurb = NAMESPACES.get(ns, (ns, ""))
        idx.append(f"| [{title}]({ns}.md) | {blurb} | {len(by_ns[ns])} |")
    idx += ["", "See also: [snapshot types](types.md), [errors](errors.md), [bot catalogue](bot-catalogue.md), "
            "[wire protocol](../protocol.md).", ""]
    files["index.md"] = "\n".join(idx)

    for ns in order:
        title, blurb = NAMESPACES.get(ns, (ns, ""))
        lines = [f"# {title}", "", blurb, ""]
        if ns in HIDDEN_NAMESPACES:
            lines += ["!!! note", f"    The SDK exposes these through `nc.events(...)` and `nc.on(...)`; the raw ops are "
                      f"reachable with `nc.call(\"{ns}.<verb>\", {{...}})`.", ""]
        for op in by_ns[ns]:
            lines.append(f"## `{op['name']}`")
            lines.append("")
            badges = [f"since {op['since']}", op["kind"]]
            if op["exec"] == "connection":
                badges.append("runs off the bot thread")
            if op.get("unstable"):
                badges.append("**unstable**")
            if op.get("deprecated"):
                badges.append(f"**deprecated**: {op['deprecated']}")
            lines.append("<small>" + " · ".join(badges) + "</small>")
            lines.append("")
            lines.append("```python")
            lines.append(signature(op))
            lines.append("```")
            lines.append("")
            lines.append(op["doc"])
            lines.append("")
            if op["params"]:
                lines += ["| Parameter | Type | Description |", "|---|---|---|"]
                for p in op["params"]:
                    desc = md_escape(p["doc"])
                    if p["optional"]:
                        desc += f" *Optional{', default ' + md_escape(p['default']) if 'default' in p else ''}.*"
                    if p.get("values"):
                        desc += " One of: " + ", ".join(f"`{v}`" for v in p["values"]) + "."
                    lines.append(f"| `{p['name']}` | `{md_escape(p['type'])}` | {desc} |")
                lines.append("")
            ret = f"**Returns** `{op['returns']}`"
            if op.get("returnsDoc"):
                ret += f": {op['returnsDoc']}"
            lines.append(ret)
            lines.append("")
            if op["errors"]:
                lines.append("**Raises** " + ", ".join(f"[`{e}`](errors.md#{e.lower()})" for e in op["errors"]))
                lines.append("")
            if op.get("example"):
                lines += ["```python", op["example"], "```", ""]
        files[f"{ns}.md"] = "\n".join(lines)

    t = ["# Snapshot types", "",
         "Ops return plain data. In Python every snapshot is a `dict` with attribute access "
         "(`gob.name == gob[\"name\"]`). Pass a snapshot, or just its id, back to any op that takes that type.", ""]
    for ty in schema["types"]:
        t += [f"## {ty['name']}", "", ty["doc"], ""]
        if ty["fields"]:
            t += ["| Field | Type | Description |", "|---|---|---|"]
            for f in ty["fields"]:
                t.append(f"| `{f['name']}` | `{md_escape(f['type'])}` | {md_escape(f['doc'])} |")
            t.append("")
    files["types.md"] = "\n".join(t)

    e = ["# Errors", "",
         "Every error the client reports has a type. The SDK raises the exception class of the same name "
         "(`from nurgling import Timeout`); all derive from `NurglingError`.", ""]
    for k, v in schema["errors"].items():
        e += [f"## {k}", "", v, ""]
    files["errors.md"] = "\n".join(e)

    b = ["# Bot catalogue", "",
         "Every bot built into the client, runnable from a script with `nc.bots.run(id, params={...})`. "
         "Bots marked *interactive* stop to ask the player for input and cannot run on background sessions. "
         "Most bots take no parameters: they read your areas and specialisations, just like when started from the menu.", ""]
    types = OrderedDict()
    for bot in bots:
        types.setdefault(bot["type"], []).append(bot)
    for ty, lst in types.items():
        b += [f"## {ty.replace('_', ' ').capitalize()}", "", "| Id | Name | Description | Parameters |", "|---|---|---|---|"]
        for bot in sorted(lst, key=lambda x: x["id"]):
            ps = "<br>".join(
                f"`{p['name']}` ({p['type']}{', required' if p['required'] else ', default ' + json.dumps(p.get('default'))}): {md_escape(p['doc'])}"
                for p in bot["params"]) or "—"
            flags = " *(interactive)*" if bot["interactive"] else ""
            b.append(f"| `{bot['id']}` | {md_escape(bot['title'] or '')}{flags} | {md_escape(bot['description'] or '')} | {ps} |")
        b.append("")
    files["bot-catalogue.md"] = "\n".join(b)
    return files


def main() -> int:
    check = "--check" in sys.argv
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    bots = json.loads(BOTS.read_text(encoding="utf-8"))
    outputs = {SDK_OUT: gen_sdk(schema)}
    for name, text in gen_ref(schema, bots).items():
        outputs[REF_DIR / name] = text
    stale = []
    for path, text in outputs.items():
        text = text.rstrip("\n") + "\n"
        old = path.read_text(encoding="utf-8") if path.exists() else None
        if old != text:
            stale.append(path)
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8", newline="\n")
    if check and stale:
        print("Generated files are out of date; run `ant apigen` and commit:")
        for p in stale:
            print("  " + str(p.relative_to(ROOT)))
        return 1
    print(f"[apigen] {len(schema['ops'])} ops; {'checked' if check else 'wrote'} {len(outputs)} files ({len(stale)} changed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
