#!/usr/bin/env python3
"""Fetch the wiki's generic ingredient types ("Any Flour", "Edible Mushroom", ...) and their members.

python tools/wiki-recipes/fetch_generics.py

Writes src/nurgling/cookbook/generic-types.json, which the cookbook uses to list the ingredients a
recipe's "Any ..." input could take. Every page in Category:GenericTypePage is a type; its members
are the pages of the category of the same name. Subcategories are kept as nested type names
("Fruit or Berry" -> "Fruit", "Berry"); the client flattens them. Read-only, throttled requests.
"""
import argparse
import datetime
import json
import pathlib
import time
import urllib.parse
import urllib.request

API = "https://ringofbrodgar.com/api.php"


def query(**params):
    params = dict(action="query", format="json", formatversion=2, **params)
    request = urllib.request.Request(API + "?" + urllib.parse.urlencode(params),
                                     headers={"User-Agent": "nurgling2-generic-types/1"})
    time.sleep(0.3)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def members(category, namespace):
    result, continuation = [], {}
    while True:
        page = query(list="categorymembers", cmtitle="Category:" + category, cmnamespace=namespace,
                     cmlimit=500, **continuation)
        result.extend(m["title"].split(":", 1)[-1] if namespace == 14 else m["title"]
                      for m in page["query"]["categorymembers"])
        if "continue" not in page:
            return result
        continuation = page["continue"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="src/nurgling/cookbook/generic-types.json")
    args = parser.parse_args()
    types = sorted(t for t in members("GenericTypePage", 0) if not t.startswith("Legacy:"))
    result = {}
    for name in types:
        pages = members(name, 0) + members(name, 14)
        result[name] = sorted(set(p for p in pages if not p.startswith("Legacy:")))
        print(f"{name}: {len(result[name])}", flush=True)
    snapshot = dict(source=API, fetchedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
                    attribution="Ring of Brodgar contributors", types=result)
    target = pathlib.Path(args.output)
    target.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"Saved {len(result)} generic types: {target}")


if __name__ == "__main__":
    main()
