#!/usr/bin/env python3
"""Read-only, resumable MediaWiki snapshot for the offline recipe compiler.

python tools/wiki-recipes/fetch_wiki.py --cache build/wiki-recipes/snapshot
Use a new cache directory for a new snapshot. Existing successful requests are reused.
"""
import argparse
import datetime
import gzip
import hashlib
import json
import pathlib
import time
import urllib.parse
import urllib.request

API = "https://ringofbrodgar.com/api.php"


class Wiki:
    def __init__(self, cache):
        self.cache = pathlib.Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.requests = 0

    def query(self, **params):
        params = dict(action="query", format="json", formatversion=2, maxlag=5, **params)
        query = urllib.parse.urlencode(sorted(params.items()))
        path = self.cache / (hashlib.sha256(query.encode()).hexdigest() + ".json")
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
        for attempt in range(4):
            try:
                time.sleep(.3)
                request = urllib.request.Request(API + "?" + query, headers={
                    "User-Agent": "NurglingRecipeAtlas/1.0 (offline crafting reference; read-only MediaWiki API)",
                    "Accept-Encoding": "gzip",
                })
                with urllib.request.urlopen(request, timeout=45) as response:
                    data = response.read()
                    if response.headers.get("Content-Encoding") == "gzip":
                        data = gzip.decompress(data)
                result = json.loads(data)
                if "error" in result:
                    raise RuntimeError(result["error"])
                if "warnings" in result:
                    print("API warning:", result["warnings"], flush=True)
                path.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                self.requests += 1
                return result
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(2 ** (attempt + 1))

    def members(self, category):
        members, continuation = [], {}
        while True:
            result = self.query(list="categorymembers", cmtitle="Category:" + category,
                                cmnamespace=0, cmlimit=500, **continuation)
            members.extend(result["query"]["categorymembers"])
            if "continue" not in result:
                break
            continuation = result["continue"]
        return members


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default="build/wiki-recipes/snapshot")
    args = parser.parse_args()
    wiki = Wiki(args.cache)
    info = wiki.query(meta="siteinfo", siprop="general|rightsinfo")
    members = wiki.members("Objects")
    ids = sorted({p["pageid"] for p in members if not p["title"].startswith("Legacy:")})
    print(f"Objects index: {len(ids)} pages", flush=True)
    pages = []
    for offset in range(0, len(ids), 50):
        result = wiki.query(prop="revisions|categories", pageids="|".join(map(str, ids[offset:offset + 50])),
                            rvprop="ids|timestamp|content", rvslots="main", cllimit=500)
        batch = {p["pageid"]: p for p in result["query"]["pages"]}
        while "continue" in result:
            result = wiki.query(prop="revisions|categories", pageids="|".join(map(str, ids[offset:offset + 50])),
                                rvprop="ids|timestamp|content", rvslots="main", cllimit=500, **result["continue"])
            for page in result["query"]["pages"]:
                batch[page["pageid"]].setdefault("categories", []).extend(page.get("categories", []))
        pages.extend(batch.values())
        print(f"Fetched {len(pages)}/{len(ids)} pages ({wiki.requests} API requests this run)", flush=True)
    snapshot = dict(source="https://ringofbrodgar.com/", fetchedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    siteinfo=info["query"], index=members, pages=pages)
    target = pathlib.Path(args.cache) / "pages.json"
    target.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {len(pages)} pages: {target} ({target.stat().st_size} bytes)", flush=True)


if __name__ == "__main__":
    main()
