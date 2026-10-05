#!/usr/bin/env python3
"""Build the shared offline icon archive from verified resource paths in the area catalog.

Retains only image and tooltip layers, never resource code. Cached resources can
be replaced by running with --refresh. TLS uses the same server CA as the client.
"""
import argparse
import concurrent.futures
import io
import json
import pathlib
import re
import ssl
import struct
import time
import urllib.request
import zipfile


def images_only(data):
    header_size = len(b'Haven Resource 1') + 2
    if not data.startswith(b'Haven Resource 1') or len(data) < header_size:
        raise ValueError('Invalid game resource')
    result = bytearray(data[:header_size])
    offset, images = header_size, 0
    while offset < len(data):
        end = data.index(b'\0', offset)
        name = data[offset:end]
        length = struct.unpack_from('<I', data, end + 1)[0]
        stop = end + 5 + length
        if stop > len(data):
            raise ValueError('Truncated resource layer')
        if name in (b'image', b'tooltip'):
            result.extend(data[offset:stop])
            images += name == b'image'
        offset = stop
    if not images:
        raise ValueError('Resource has no image layer')
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh', action='store_true')
    parser.add_argument('--output', default='src/nurgling/resources/item-icons.bin')
    parser.add_argument('--snapshot', default='build/wiki-recipes/snapshot/pages.json')
    parser.add_argument('--recipes', default='build/wiki-recipes/recipes-inspect.json')
    args = parser.parse_args()
    text = pathlib.Path('src/nurgling/tools/VSpec.java').read_text(encoding='utf-8')
    text = re.sub(r'^\s*//.*$', '', text, flags=re.M)
    from icon_catalog import native_catalog, wiki_images
    names = native_catalog(text)
    resources = set()
    for item in names.values():
        if 'static' in item:
            resources.add(item['static'])
        resources.update(item.get('layer', []))
    # Keep every area-catalog variant, including descriptors with a duplicate display name.
    for literal in re.findall(r'new JSONObject\("((?:\\.|[^"\\])*)"\)', text):
        item = json.loads(re.sub(r',\s*([}\]])', r'\1', json.loads('"' + literal + '"')))
        if 'static' in item: resources.add(item['static'])
        resources.update(item.get('layer', []))
    cache = pathlib.Path('build/item-icon-resources')
    cache.mkdir(parents=True, exist_ok=True)
    context = ssl.create_default_context(cafile='etc/ressrv.crt')
    failures, packed = [], {}
    def fetch(name):
        path = cache / (name + '.res')
        if path.exists() and not args.refresh:
            return name, images_only(path.read_bytes())
        for attempt in range(3):
            try:
                with urllib.request.urlopen('https://game.havenandhearth.com/res/' + name + '.res', context=context, timeout=20) as response:
                    raw = response.read(2 * 1024 * 1024)
                image = images_only(raw)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                return name, image
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(.3 * (attempt + 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        jobs = {executor.submit(fetch, name): name for name in sorted(resources)}
        for index, job in enumerate(concurrent.futures.as_completed(jobs), 1):
            try:
                name, data = job.result()
                packed[name] = data
            except Exception as error:
                failures.append(dict(resource=jobs[job], error=str(error)))
            if index % 100 == 0 or index == len(jobs):
                print(f'{index}/{len(jobs)} resources, {len(failures)} unavailable', flush=True)
    if failures:
        raise ValueError(f'{len(failures)} native icons unavailable; leaving archive unchanged: {failures}')
    snapshot = json.loads(pathlib.Path(args.snapshot).read_text(encoding='utf-8'))
    recipes = json.loads(pathlib.Path(args.recipes).read_text(encoding='utf-8'))
    images, provenance, missing = wiki_images(names, snapshot, recipes, cache, args.refresh)
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        entries = {name + '.res': data for name, data in packed.items()}
        entries.update(images)
        entries['index.json'] = json.dumps(dict(version=1, names=names), ensure_ascii=False, sort_keys=True).encode('utf-8')
        entries['sources.json'] = json.dumps(dict(source=snapshot['source'], images=provenance), sort_keys=True).encode('utf-8')
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    from update_recipes import install, atomic_write, json_bytes
    install(target, output.getvalue())
    atomic_write(cache / 'report.json', json_bytes(dict(resources=len(packed), wikiImages=len(images), names=len(names),
        bytes=target.stat().st_size, failures=failures, missing=missing)))
    print(f'Saved {len(packed)} native resources, {len(images)} wiki images, {len(names)} names; {len(missing)} unresolved names')


if __name__ == '__main__':
    main()
