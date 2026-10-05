"""Build a shared name -> sprite descriptor index, including wiki-only objects.

Native layered descriptors use exactly the same format as ItemTex/IngredientContainer.
Wiki images are a fallback for objects without a native inventory sprite (buildings,
for example). Nothing is downloaded by the running game client.
"""
import concurrent.futures
import hashlib
import json
import pathlib
import re
import struct
import urllib.parse
import urllib.request
import uuid

from compile_recipes import normalize, templates, properties, resolve_magic
from fetch_wiki import Wiki

# Wiki redirects/ingredient labels, plus representative images for generic families.
WIKI_ALIASES = {'Cow': 'Cattle', 'Jar': 'Clay Jar', 'Bloodstern': 'Blood Stern',
                'Goat corpse': 'Goat', 'Cave Angler Corpse': 'Cave Angler',
                'Pitbaked Goods': 'Fishwrap', 'Tame Reindeer': 'Reindeer'}


def native_catalog(text):
    text = re.sub(r'^\s*//.*$', '', text, flags=re.M)
    lists, names, categories = {}, {}, {}
    statements = re.compile(r'(\w+)\.add\(new JSONObject\("((?:\\.|[^"\\])*)"\)\)|'
                            r'(\w+)\.addAll\((\w+)\)|categories\.put\("([^"]+)", (\w+)\)')
    for match in statements.finditer(text):
        owner, literal, dest, source, category, variable = match.groups()
        if literal is not None:
            decoded = json.loads('"' + literal + '"')
            item = json.loads(re.sub(r',\s*([}\]])', r'\1', decoded))
            lists.setdefault(owner, []).append(item)
            names.setdefault(normalize(item['name']), {k: v for k, v in item.items() if k != 'name'})
        elif dest:
            lists.setdefault(dest, []).extend(lists.get(source, []))
        elif lists.get(variable):
            categories[normalize(category)] = {k: v for k, v in lists[variable][0].items() if k != 'name'}
    for name, descriptor in categories.items():
        names.setdefault(name, descriptor)
        names.setdefault('any ' + name, descriptor)
    # New animal variants absent from the older area selector; resource paths verified
    # against the official server and validated again during archive assembly.
    for label, resource in {'Bullfinch': 'bullfinch', 'Crane': 'crane', 'Garefowl': 'garefowl',
                            'Goshawk': 'goshawk', 'Jotun Clam': 'jotunclam'}.items():
        names[normalize(label + ' Meat')] = {'layer': ['gfx/invobjs/meat-poultry' if resource != 'jotunclam' else 'gfx/invobjs/meat-weird',
                                                     'gfx/invobjs/meat-' + resource]}
    for name, descriptor in sorted(list(names.items()), key=lambda pair: not pair[0].startswith(('raw ', 'filet of '))):
        if name.endswith(' seed'):
            names.setdefault('seeds of ' + name[:-5], descriptor)
        if name.endswith(' seeds'):
            names.setdefault('seeds of ' + name[:-6], descriptor)
        if name.startswith('sprouted '):
            names.setdefault('seeds of ' + name, descriptor)
        if name.startswith('raw ') or name.startswith('filet of ') or name.endswith(' meat'):
            layers = descriptor.get('layer', [])
            bases = {'meat-raw', 'meat-filet', 'meat-poultry', 'meat-weird'}
            animal = [p for p in layers if p.rsplit('/', 1)[-1] not in bases]
            if len(animal) == 1 and any(p.rsplit('/', 1)[-1] in bases for p in layers):
                label = re.sub(r'^(raw |filet of )| meat$', '', name)
                names.setdefault('spitroast ' + label, {'layer': ['gfx/invobjs/meat-spitroast', animal[0]]})
                names.setdefault('roast ' + label, {'layer': ['gfx/invobjs/meat-roast', animal[0]]})
                names.setdefault('smoked ' + label, {'layer': ['gfx/invobjs/meat-smoked', animal[0]]})
    for alias, target in {
        'Bar of Hard Metal': 'Bar of Bronze, Iron or Steel',
        'Nugget of Hard Metal': 'Nugget of Bronze, Iron or Steel',
        'Crop Seeds': 'Seeds',
        'Block': 'Block of Wood', 'Oak Block': 'Block of Oak',
        'Bone': 'Bone Material', 'Tree Bark': 'Bark', 'Birchbark': 'Birch Bark',
        'Taproot': 'Spindly Taproot', 'Crab Shell': 'Crabshell', 'Bough': 'Tree Bough',
        'Dried Pepper Drupes': 'Black Pepper',
        'Dream': 'A Beautiful Dream', 'Apple': 'Red Apple', 'Hazel Nut': 'Hazelnut',
        'Fish Hook': 'Hooks', 'Fishing Lure': 'Lures', 'Bush Seed': 'Seed of Tree or Bush',
        'Raw Boar': 'Raw Wild Pork', 'Raw Chicken': 'Chicken Meat',
        'Roasted Chicken Meat': 'Roast Chicken', 'Wrought Iron': 'Bar of Wrought Iron',
        'Smoked Boar': 'Smoked Wild Pork', 'Bark Boat': 'Barkboat', 'Troll Hide Patch': 'Troll Hide',
        'Baked Goods': 'Bread', 'Fuel': 'Block of Wood', 'Pigments': 'Pigment',
    }.items():
        if normalize(target) in names:
            names[normalize(alias)] = names[normalize(target)]
    names['silk filament'] = {'static': 'gfx/invobjs/silkfilament'}
    overrides = pathlib.Path(__file__).with_name('icon-overrides.json')
    if overrides.exists():
        names.update({normalize(k): v for k, v in json.loads(overrides.read_text(encoding='utf-8')).items()})
    return names


def image_title(page):
    title = page['title']
    content = page.get('revisions', [{}])[0].get('slots', {}).get('main', {}).get('content', '')
    for template, fields in templates(content):
        if template == 'infobox metaobj':
            image = resolve_magic(properties(fields).get('image', ''), title)
            if re.match(r'(?i)^(file|image):[^{}\[\]|]+$', image):
                return 'File:' + image.split(':', 1)[1].strip()
    return 'File:' + title + '.png'


def png_size(data):
    if len(data) < 24 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        raise ValueError('Expected PNG image')
    width, height = struct.unpack_from('>II', data, 16)
    if not (0 < width <= 1024 and 0 < height <= 1024):
        raise ValueError('Image dimensions exceed limits')
    return width, height


def image_size(data):
    if data.startswith(b'\x89PNG'):
        return png_size(data)
    if data[:2] == b'\xff\xd8':
        offset = 2
        while offset + 4 <= len(data) and data[offset] == 0xff:
            marker = data[offset + 1]
            length = struct.unpack_from('>H', data, offset + 2)[0]
            if length < 2 or offset + 2 + length > len(data): break
            if marker in (0xc0, 0xc1, 0xc2) and length >= 7:
                height, width = struct.unpack_from('>HH', data, offset + 5)
                if 0 < width <= 1024 and 0 < height <= 1024: return width, height
                break
            offset += 2 + length
    raise ValueError('Invalid or oversized PNG/JPEG icon')


def wanted_names(recipes):
    wanted = {normalize(r['name']): r['name'] for r in recipes}
    for recipe in recipes:
        for item in recipe['inputs'] + recipe['outputs']:
            if item.get('resource', '').startswith('wiki-choice:'):
                for resource, name, category in json.loads(item['resource'].removeprefix('wiki-choice:')):
                    wanted.setdefault(normalize(name), name)
            else:
                wanted.setdefault(normalize(item['name']), item['name'])
    return wanted


def wiki_images(names, snapshot, recipes, cache, refresh=False):
    """Return packed images, provenance and explicit gaps; reuse downloads by revision hash."""
    pages = {normalize(p['title']): p for p in snapshot['pages']}
    wanted = wanted_names(recipes)
    for alias, target in WIKI_ALIASES.items():
        if normalize(alias) in wanted: wanted.setdefault(normalize(target), target)
    files = {}
    for key, title in wanted.items():
        if key not in names:
            if title in WIKI_ALIASES: continue
            image = image_title(pages.get(key, {'title': title}))
            files.setdefault(image, []).append(key)
    # A refresh reads current image metadata but keeps content-addressed image downloads.
    api_cache = cache / ('wiki-api-' + uuid.uuid4().hex if refresh else 'wiki-api')
    wiki = Wiki(api_cache)
    metadata, missing = [], []
    titles = sorted(files)
    for offset in range(0, len(titles), 50):
        response = wiki.query(prop='imageinfo', titles='|'.join(titles[offset:offset + 50]),
                              iiprop='url|sha1|timestamp|mime', iiurlwidth=96, redirects=1)
        query = response['query']
        aliases = {p['from']: p['to'] for p in query.get('normalized', []) + query.get('redirects', [])}
        by_title = {p['title']: p for p in query['pages']}
        for title in titles[offset:offset + 50]:
            resolved, seen = title, set()
            while resolved in aliases and resolved not in seen:
                seen.add(resolved); resolved = aliases[resolved]
            infos = by_title.get(resolved, {}).get('imageinfo', [])
            if not infos or infos[0].get('mime') not in ('image/png', 'image/jpeg'):
                missing.extend(files[title]); continue
            metadata.append((title, infos[0]))
        print(f'Wiki icon metadata: {min(offset + 50, len(titles))}/{len(titles)}', flush=True)
    packed, provenance = {}, {}
    def fetch(entry):
        title, info = entry
        url = info.get('thumburl', info['url'])
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or parsed.hostname != 'ringofbrodgar.com':
            raise ValueError('Unexpected wiki image host')
        digest = hashlib.sha256((info['sha1'] + ':96:' + url).encode()).hexdigest()
        extension = '.png' if info['mime'] == 'image/png' else '.jpg'
        path = cache / 'wiki-png' / (digest + extension)
        if path.exists():
            data = path.read_bytes()
        else:
            request = urllib.request.Request(url, headers={'User-Agent': 'NurglingRecipeAtlas/1.0 (offline icons)'})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('Image too large')
            image_size(data)
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        image_size(data)
        return title, info, digest + extension, data
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        for index, (title, info, digest, data) in enumerate(executor.map(fetch, metadata), 1):
            path = 'wiki/' + digest
            packed[path] = data
            for key in files[title]:
                names[key] = {'image': path}
            provenance[path] = dict(title=title, url=info['url'], sha1=info['sha1'], timestamp=info['timestamp'])
            if index % 100 == 0:
                print(f'Wiki icon downloads: {index}/{len(metadata)}', flush=True)
    for alias, target in WIKI_ALIASES.items():
        if normalize(target) in names: names.setdefault(normalize(alias), names[normalize(target)])
    missing = [key for key in wanted if key not in names]
    return packed, provenance, sorted(missing)
