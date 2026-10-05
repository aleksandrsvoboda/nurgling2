#!/usr/bin/env python3
"""Compile a cached Ring of Brodgar snapshot into the versioned NRCPDB01 format."""
import argparse
import collections
import hashlib
import html
import io
import json
import pathlib
import re
import struct
import urllib.parse
import zlib

STATIONS = {
    "fire", "fireplace", "grid iron", "roasting spit", "drying frame", "cheese tray", "cheese rack",
    "herbalist table", "crucible", "cauldron", "ore smelter", "pickling jar", "pickling crock", "demijohn",
    "extraction press", "oven", "kiln", "gemcutter's wheel", "curding tub", "stack furnace", "quern",
    "still", "tar kiln", "tanning tub", "finery forge", "steel crucible", "anvil", "frying pan", "smoke shed",
    "glass pane frame", "potter's wheel", "loom", "spinning wheel", "churn", "compost bin",
}
ATTRIBUTES = {s.casefold(): s for s in ["Strength", "Agility", "Intelligence", "Constitution", "Perception", "Charisma",
    "Dexterity", "Psyche", "Will", "Survival", "Sewing", "Cooking", "Carpentry", "Masonry", "Farming",
    "Smithing", "Lore", "Exploration", "Stealth"]}
MAGIC = b"NRCPDB01"


def split_top(value, separator="|"):
    """Split only outside nested templates and links (no MediaWiki execution)."""
    result, start, depth, links, i = [], 0, 0, 0, 0
    while i < len(value):
        pair = value[i:i + 2]
        if pair == "{{": depth += 1; i += 2; continue
        if pair == "}}": depth -= 1; i += 2; continue
        if pair == "[[": links += 1; i += 2; continue
        if pair == "]]": links -= 1; i += 2; continue
        if value[i] == separator and depth == 0 and links == 0:
            result.append(value[start:i]); start = i + 1
        i += 1
    result.append(value[start:])
    return result


def templates(text):
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    stack = []
    for match in re.finditer(r"\{\{|\}\}", text):
        if match.group() == "{{": stack.append(match.end())
        elif stack:
            start = stack.pop()
            fields = split_top(text[start:match.start()])
            yield fields[0].strip().replace("_", " ").lower(), fields[1:]


def properties(fields):
    result = {}
    for field in fields:
        pair = split_top(field, "=")
        if len(pair) >= 2:
            result[pair[0].strip().lower()] = "=".join(pair[1:]).strip()
    return result


def link_parts(content):
    parts = content.split("|")
    target = parts[0].split("::")[-1].strip().lstrip(":").split("#")[0]
    label = parts[-1] if len(parts) > 1 else target
    return target.replace("_", " "), label.strip()


def plain(text, title=""):
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"\{\{#ask:\s*\[\[(?:Specific::|Category:)([^\]|]+)(?:\|[^\]]*)?\]\]\s*\}\}", r"any type of \1", text, flags=re.I)
    text = re.sub(r"\[\[([^\[\]]+)\]\]", lambda m: link_parts(m[1])[1], text)
    text = re.sub(r"\{\{PAGENAME\}\}", lambda m: title, text, flags=re.I)
    text = re.sub(r"\{\{(?:none|unknown)\}\}", "", text, flags=re.I)
    text = re.sub(r"<br\s*/?>", "; ", text, flags=re.I)
    text = re.sub(r"<ref\b[^>]*>.*?</ref>|<ref\b[^>]*/>", "", text, flags=re.S | re.I)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("'''", "").replace("''", "").replace("$", "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def normalize(value):
    return re.sub(r"\s+", " ", re.sub("[\u200b-\u200f\ufeff]", "", value).replace("_", " ")).strip().casefold()


def item_key(title):
    return "wiki-item:" + normalize(title.removeprefix("Category:"))


def resolve_magic(value, title):
    value = re.sub(r"\{\{PAGENAMEE?\}\}", lambda m: title, value, flags=re.I)
    value = re.sub(r"\{\{SD[ _]PAGENAME\|(\d+)\}\}", lambda m: title[int(m[1]):], value, flags=re.I)
    # Remove display:none semantic bookkeeping, which is not a visible ingredient.
    value = re.sub(r'<span\b[^>]*style=["\'][^"\']*display\s*:\s*none[^"\']*["\'][^>]*>.*?</span>', '', value, flags=re.I | re.S)
    return re.sub(r"<!--.*?-->", "", value, flags=re.S)


def parse_inputs(value, title, generic):
    """Only emit arithmetic for a complete, unambiguous flat list; preserve other expressions verbatim."""
    value = resolve_magic(value, title)
    readable = plain(value, title)
    if not readable or normalize(readable) in {"none", "unknown", "n/a", "nothing", "-"}:
        return [], readable, "no ingredients specified"
    if "{{" in value:
        return [], readable, "dynamic wiki expression"
    # Alternative recipes, ranges and construction stages must never become additive shopping lists.
    operators = re.sub(r"\[\[[^\[\]]+\]\]", "ITEM", value)
    if re.search(r"\b(?:level|cornerpost|sections?|gate)\s*:|\d+\s*[-–]\s*\d+", operators, re.I):
        return [], readable, "alternatives or stages"
    value = re.sub(r"<br\s*/?>|\n\s*\*", ",", value, flags=re.I)
    value = re.sub(r"<[^>]*>", "", value).strip()
    value = re.sub(r"\]\]\s*(x\s*\d+(?:\.\d+)?)\s*(?=\[\[)", r"]] \1, ", value)
    parts = split_top(value, ",")
    parts = [p for chunk in parts for p in split_top(chunk, ";") if p.strip()]
    result = []
    for part in parts:
        optional = bool(re.search(r"\boptional\b", part, re.I))
        part = re.sub(r"optional\s*:|\(optional\)", "", part, flags=re.I).strip()
        part = re.sub(r"^and\s+", "", part, flags=re.I)
        alternatives = re.split(r"\s+or\s+(?=\[\[)", part, flags=re.I)
        if len(alternatives) > 1:
            # A trailing quantity scopes this entire flat choice: A or B or C (0.25 kg).
            # Never flatten it into three additive materials, nor guess unequal branch quantities.
            if len(alternatives) > 16:
                return [], readable, "too many alternatives"
            options = []
            for alternative in alternatives:
                parsed, _, reason = parse_inputs(alternative, title, generic)
                if reason or len(parsed) != 1 or parsed[0]['resource'].startswith('wiki-choice:'):
                    return [], readable, "complex alternative expression"
                options.append(parsed[0])
            bare = [bool(re.fullmatch(r"\s*\[\[[^\[\]]+\]\]\s*", a)) for a in alternatives]
            amount, unit = options[-1]['amount'], options[-1]['unit']
            if not all(bare[:-1]) and any((o['amount'], o['unit']) != (amount, unit) for o in options):
                return [], readable, "unequal alternative quantities"
            identity = [[o['resource'], o['name'], o['category']] for o in options]
            result.append(dict(resource='wiki-choice:' + json.dumps(identity, ensure_ascii=False, separators=(',', ':')),
                               name=' or '.join(o['name'] for o in options), amount=amount, unit=unit,
                               optional=optional, category=True))
            continue
        part = re.sub(r"^(\d+)\s*(\[\[.*?\]\])$", r"\2 x\1", part)
        match = re.fullmatch(r"\s*\[\[([^\[\]]+)\]\]\s*(.*?)\s*", part, re.S)
        if not match:
            return [], readable, "non-flat ingredient expression"
        target, name = link_parts(match[1])
        rest = match[2].strip().strip("' ")
        # The wiki convention is one when the ingredient has no quantity suffix.
        amount, unit = 1.0, ""
        if rest:
            qty = re.fullmatch(r"\(?\s*(?:[x×]\s*)?(\d+(?:\.\d+)?)\s*(kg|g|[lL]|liters?|litres?)?\s*\)?", rest)
            if not qty:
                return [], readable, "quantity qualifier"
            amount = float(qty[1]); unit = (qty[2] or "").lower()
            if unit.startswith("lit"): unit = "l"
        if amount <= 0 or amount > 1_000_000:
            raise ValueError(f"Invalid amount in {title}: {part}")
        result.append(dict(resource=item_key(target), name=name, amount=amount, unit=unit, optional=optional,
                           category=target.startswith("Category:") or normalize(target) in generic))
    return result, readable, ""


def quality_modifiers(text):
    # "will" in ordinary English prose is not the character attribute Will.
    linked = [link_parts(m[1])[0] for m in re.finditer(r"\[\[([^\[\]]+)\]\]", text)]
    names = {ATTRIBUTES[s.casefold()] for s in linked if s.casefold() in ATTRIBUTES}
    names.update(word for word in re.findall(r"\b[A-Za-z]+\b", plain(text))
                 if word != "Will" and word in ATTRIBUTES.values())
    for formula in re.findall(r"<math>(.*?)</math>", text, re.S):
        names.update(ATTRIBUTES[word.casefold()] for word in re.findall(r"[A-Za-z]+", formula)
                     if word.casefold() in ATTRIBUTES)
    return sorted(names)


def extract(snapshot):
    parsed = {normalize(p["title"]): (p, page_parts(p)) for p in snapshot["pages"]}
    generic = {key for key, (_, (_, _, _, cats)) in parsed.items() if "GenericTypePage" in cats}
    records, skipped, review = [], [], []
    for key, (page, (text, boxes, menus, categories)) in parsed.items():
        title = page["title"]
        if len(boxes) != 1:
            skipped.append((title, "no unique item infobox")); continue
        box = boxes[0]
        explicit = [[p for p in menu if "=" not in p] for menu in menus]
        direct = next((m for m in explicit if m and m[0] in ("Craft", "Build")), [])
        copies = [p.split("=", 1)[1].strip() for menu in menus for p in menu if p.lower().startswith("copy=")]
        menu = direct
        kind = direct[0].lower() if direct else "process"
        if not menu and copies:
            parent = parsed.get(normalize(copies[0]))
            if parent:
                menu = next((m for m in parent[1][2] if m and m[0] in ("Craft", "Build")), [])
        station_names = {normalize(link_parts(m[1])[0]) for m in re.finditer(r"\[\[([^\[\]]+)\]\]", box.get("producedby", ""))}
        if not direct and ("GenericTypePage" in categories or not (menu or station_names & STATIONS)):
            skipped.append((title, "not a crafting/building/processing recipe")); continue
        # Never claim forageables or random curiosities found in a workstation as deterministic recipes.
        if not direct and ("Foraged" in categories or "Curiosity miscellaneous" in categories):
            skipped.append((title, "foraged or incidental output")); continue
        inputs, expression, reason = parse_inputs(box.get("objectsreq", ""), title, generic)
        if kind == "process" and len(inputs) > 1 and not reason:
            # Smelter/pickling/etc. infoboxes often list possible feedstocks separated by commas.
            # Without an explicit craft menu this is not evidence that they are all consumed together.
            inputs, reason = [], "processing alternatives not specified"
        if not inputs and not expression:
            skipped.append((title, "missing ingredients")); continue
        if reason: review.append(dict(title=title, reason=reason, expression=expression))
        if reason:
            # Retain ingredient references for search/graph navigation even when arithmetic is ambiguous.
            for match in re.finditer(r"\[\[([^\[\]{}]+)\]\]", resolve_magic(box.get("objectsreq", ""), title)):
                target, name = link_parts(match[1])
                inputs.append(dict(resource=item_key(target), name=name, amount=-1, unit="", optional=False,
                                   category=target.startswith("Category:") or normalize(target) in generic))
        quality = re.search(r"^==+\s*Quality\s*==+\s*(.*?)(?=^==|\Z)", text, re.I | re.M | re.S)
        quality_text = quality[1] if quality else ""
        modifiers = quality_modifiers(quality_text)
        # Mathematical formulas are kept as source formulas, not executed or guessed.
        formulas = [html.unescape(x.strip()) for x in re.findall(r"<math>(.*?)</math>", quality_text, re.S)]
        # Normalize the explicit uncapped single-material construction rule (e.g. Kiln).
        # The separate formula for firing products must not determine construction quality.
        if len(inputs) == 1 and not reason:
            rule = r"\bquality of (?:the )?" + re.escape(title) + r" is the average quality of (?:the )?" + re.escape(inputs[0]['name']) + r"\b[^.]*\bnot softcapped\b"
            if re.search(rule, plain(quality_text), re.I):
                formulas.insert(0, '_q' + title + ' = _q' + inputs[0]['name'])
                modifiers = []
        reqs = [plain(box[k], title) for k in ("skillreq", "discoveryreq") if plain(box.get(k, ""), title)]
        tool = plain(box.get("producedby", ""), title)
        revision = page["revisions"][0]
        group = " / ".join(menu[1:-1]) if len(menu) > 2 else "Processing"
        # Wiki infoboxes generally omit yield. An unknown yield stays unknown.
        records.append(dict(resource="wiki:" + str(page["pageid"]), name=title, group=group, inputs=inputs,
            outputs=[dict(resource=item_key(title), name=title, amount=-1, unit="", optional=False, category=key in generic)],
            quality=modifiers, tools=[tool] if tool and normalize(tool) not in {"nothing", "none", "n/a"} else [],
            requirements=reqs, formulas=formulas, source="https://ringofbrodgar.com/index.php?title=" + urllib.parse.quote(title.replace(" ", "_"), safe="") + "&oldid=" + str(revision["revid"]),
            revision=revision["revid"], date=revision["timestamp"], kind=kind,
            actionName=direct[-1] if direct and kind == "craft" else "", inputText=expression,
            quantitiesKnown=not bool(reason)))
    records.sort(key=lambda r: (normalize(r["name"]), r["resource"]))
    return records, skipped, review


class Writer:
    def __init__(self):
        self.buffer = io.BytesIO(); self.strings = {}; self.table = []
    def u32(self, n): self.buffer.write(struct.pack(">I", n))
    def string(self, value):
        if value not in self.strings:
            self.strings[value] = len(self.table); self.table.append(value)
        self.u32(self.strings[value])
    def strings_list(self, values):
        self.u32(len(values))
        for value in values: self.string(value)
    def materials(self, values):
        self.u32(len(values))
        for value in values:
            self.string(value["resource"]); self.string(value["name"])
            self.buffer.write(struct.pack(">d", value["amount"]))
            self.string(value["unit"])
            self.buffer.write(bytes([int(value["optional"]) | (int(value["category"]) << 1)]))


def encode(records, snapshot):
    writer = Writer()
    writer.string(snapshot["source"]); writer.string(snapshot["fetchedAt"])
    writer.string("Ring of Brodgar contributors; factual recipe data extracted from cited page revisions. No article prose or images included.")
    writer.u32(len(records))
    for recipe in records:
        for field in ("resource", "name", "group", "source", "date", "kind", "actionName", "inputText"):
            writer.string(recipe[field])
        writer.u32(recipe["revision"])
        writer.buffer.write(bytes([recipe["quantitiesKnown"]]))
        writer.materials(recipe["inputs"]); writer.materials(recipe["outputs"])
        for field in ("quality", "tools", "requirements", "formulas"): writer.strings_list(recipe[field])
    table = io.BytesIO(); table.write(struct.pack(">I", len(writer.table)))
    for value in writer.table:
        data = value.encode("utf-8"); table.write(struct.pack(">I", len(data))); table.write(data)
    raw = table.getvalue() + writer.buffer.getvalue()
    compressed = zlib.compress(raw, 9)
    return MAGIC + struct.pack(">II", len(raw), len(compressed)) + hashlib.sha256(raw).digest() + compressed


def page_parts(page):
    revision = page.get("revisions", [{}])[0]
    text = revision.get("slots", {}).get("main", {}).get("content", "")
    boxes, menus = [], []
    for name, fields in templates(text):
        if name == "infobox metaobj": boxes.append(properties(fields))
        if name == "gm": menus.append([plain(p, page["title"]) for p in fields])
    categories = {c["title"].removeprefix("Category:") for c in page.get("categories", [])}
    return text, boxes, menus, categories


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", default="build/wiki-recipes/snapshot/pages.json")
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument("--output", default="src/nurgling/craft/recipes.bin")
    parser.add_argument("--report-dir", default="build/wiki-recipes")
    args = parser.parse_args()
    snapshot = json.loads(pathlib.Path(args.snapshot).read_text(encoding="utf-8"))
    if not args.inspect:
        records, skipped, review = extract(snapshot)
        if len(records) < 900: raise ValueError(f"Unexpectedly small recipe catalogue: {len(records)}")
        binary = encode(records, snapshot)
        target = pathlib.Path(args.output); target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(binary)
        report = pathlib.Path(args.report_dir); report.mkdir(parents=True, exist_ok=True)
        manifest = dict(format="NRCPDB01", source=snapshot["source"], fetchedAt=snapshot["fetchedAt"],
            scannedPages=len(snapshot["pages"]), recipeCount=len(records), kinds=dict(collections.Counter(r["kind"] for r in records)),
            arithmeticRecipes=sum(r["quantitiesKnown"] for r in records), sourceExpressionRecipes=len(review),
            sha256=hashlib.sha256(binary).hexdigest(), bytes=len(binary), recipes=[{k:r[k] for k in ("resource", "name", "kind", "source", "revision")} for r in records])
        (report / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        (report / "recipes-inspect.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
        (report / "review.json").write_text(json.dumps(review, ensure_ascii=False, indent=2), encoding="utf-8")
        (report / "excluded.json").write_text(json.dumps(skipped, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({k:v for k,v in manifest.items() if k != "recipes"}, ensure_ascii=False, indent=2))
        return
    counts, forms, roots, fields = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    examples = []
    for page in snapshot["pages"]:
        text, boxes, menus, cats = page_parts(page)
        counts["infoboxes=" + str(len(boxes))] += 1
        for menu in menus: roots[" > ".join(menu[:2])] += 1
        for box in boxes:
            fields.update(box.keys())
            if box.get("objectsreq"):
                counts["with_inputs"] += 1
                if not menus and "GenericTypePage" not in cats: examples.append((page["title"], box.get("objectsreq"), box.get("producedby")))
                if any(char in box["objectsreq"] for char in ["{", "/", ";", "("]) or " or " in box["objectsreq"]:
                    forms[box["objectsreq"]] += 1
    print("COUNTS", counts)
    print("MENUS", roots)
    print("FIELDS", fields.most_common(100))
    print("NO MENU", json.dumps(examples[:160], ensure_ascii=False))
    print("COMPLEX", json.dumps(forms.most_common(120), ensure_ascii=False))


if __name__ == "__main__":
    main()
