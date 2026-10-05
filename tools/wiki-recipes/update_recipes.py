#!/usr/bin/env python3
"""Get wiki changes, compile and atomically install the offline recipe database."""
import argparse
import copy
import datetime
import hashlib
import json
import os
import pathlib
import uuid
from contextlib import contextmanager

from fetch_wiki import Wiki
from compile_recipes import extract, encode


def revision(page):
    return page['revisions'][0]['revid']


def refresh(wiki, previous):
    """Fresh index + metadata, with content requests pinned to changed revision IDs."""
    members = wiki.members('Objects')
    ids = sorted({p['pageid'] for p in members if not p['title'].startswith('Legacy:')})
    old = {p['pageid']: p for p in previous.get('pages', [])}
    if not ids or (old and len(ids) < len(old) * .8):
        raise ValueError('Objects index is empty or unexpectedly small; keeping the installed database')
    current = {}
    for offset in range(0, len(ids), 50):
        continuation = {}
        batch = {}
        while True:
            response = wiki.query(prop='revisions|categories', pageids='|'.join(map(str, ids[offset:offset + 50])),
                                  rvprop='ids|timestamp', cllimit=500, **continuation)
            for page in response['query']['pages']:
                if 'missing' in page or 'invalid' in page:
                    raise ValueError('Wiki index changed during update; retry to get a complete snapshot')
                key = page['pageid']
                if key in batch:
                    batch[key].setdefault('categories', []).extend(page.get('categories', []))
                    if page.get('revisions'):
                        batch[key]['revisions'] = page['revisions']
                else:
                    batch[key] = copy.deepcopy(page)
            continuation = response.get('continue')
            if not continuation:
                break
        if set(batch) != set(ids[offset:offset + 50]):
            raise ValueError('Incomplete wiki metadata response')
        current.update(batch)
    changed = [key for key, page in current.items() if key not in old or revision(page) != revision(old[key])]
    content = {}
    for offset in range(0, len(changed), 50):
        keys = changed[offset:offset + 50]
        response = wiki.query(prop='revisions', revids='|'.join(str(revision(current[k])) for k in keys),
                              rvprop='ids|timestamp|content', rvslots='main')
        if response.get('continue') or response.get('query', {}).get('badrevids'):
            raise ValueError('Incomplete wiki revision response')
        for page in response['query']['pages']:
            for rev in page.get('revisions', []):
                if not isinstance(rev.get('slots', {}).get('main', {}).get('content'), str):
                    raise ValueError('Wiki revision content is missing or hidden')
                content[rev['revid']] = rev
    for key, page in current.items():
        rev = revision(page)
        if key not in old or rev != revision(old[key]):
            if rev not in content:
                raise ValueError('Missing requested wiki revision')
            page['revisions'] = [content[rev]]
        else:
            page['revisions'] = copy.deepcopy(old[key]['revisions'])
    counts = dict(added=len(set(current) - set(old)), removed=len(set(old) - set(current)),
                  contentDownloaded=len(changed), reused=len(current) - len(changed))
    snapshot = dict(source='https://ringofbrodgar.com/',
                    fetchedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    siteinfo=previous.get('siteinfo', {}), index=members,
                    pages=[current[key] for key in sorted(current)])
    return snapshot, counts


def differences(before, after):
    old = {r['resource']: r for r in before}
    new = {r['resource']: r for r in after}
    added, removed, changed = [], [], []
    for key in sorted(new.keys() - old.keys()):
        added.append(new[key])
    for key in sorted(old.keys() - new.keys()):
        removed.append(old[key])
    for key in sorted(old.keys() & new.keys()):
        fields = [field for field in new[key] if old[key].get(field) != new[key][field]]
        if fields:
            changed.append(dict(id=key, name=new[key]['name'], fields=fields, before=old[key], after=new[key]))
    return dict(added=added, removed=removed, changed=changed)


def atomic_write(path, data):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('xb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def install(path, binary):
    path = pathlib.Path(path)
    previous = path.read_bytes() if path.exists() else None
    if previous == binary:
        return False
    if previous is not None:
        atomic_write(path.with_name(path.name + '.bak'), previous)
    atomic_write(path, binary)
    return True


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8')


@contextmanager
def update_lock(path):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise RuntimeError(f'Another update is active ({path}). After an interrupted process, remove this lock before retrying.')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(str(os.getpid()))
        yield
    finally:
        path.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', default='build/wiki-recipes/snapshot/pages.json')
    parser.add_argument('--output', default='src/nurgling/craft/recipes.bin')
    parser.add_argument('--runtime', default='bin/recipes.bin', help='Runtime copy; empty string disables it')
    parser.add_argument('--work-dir', default='build/wiki-recipes/updates')
    parser.add_argument('--check', action='store_true', help='Write a change report without replacing the database or snapshot')
    args = parser.parse_args()
    work = pathlib.Path(args.work_dir)
    work.mkdir(parents=True, exist_ok=True)
    with update_lock(pathlib.Path(args.snapshot).with_suffix('.update.lock')):
        path = pathlib.Path(args.snapshot)
        previous = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
        # A fresh request cache per check is essential: metadata must never come from an earlier check.
        run = work / ('check-' + uuid.uuid4().hex)
        run.mkdir()
        wiki = Wiki(run / 'requests')
        snapshot, pages = refresh(wiki, previous)
        records, excluded, review = extract(snapshot)
        old_records = extract(previous)[0] if previous else []
        if len(records) < 900 or (old_records and len(records) < .8 * len(old_records)):
            raise ValueError('Unexpected recipe count; the installed database has not been changed')
        changes = differences(old_records, records)
        if not any(changes.values()) and previous:
            # An unchanged check must not change binary bytes or trigger client reloads.
            snapshot['fetchedAt'] = previous['fetchedAt']
        binary = encode(records, snapshot)
        report = dict(checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      mode='check' if args.check else 'apply', pages=pages, recipeCount=len(records),
                      counts={k: len(v) for k, v in changes.items()}, changes=changes,
                      sha256=hashlib.sha256(binary).hexdigest(), review=review, excluded=excluded)
        atomic_write(run / 'changes.json', json_bytes(report))
        atomic_write(run / 'pages.json', json_bytes(snapshot))
        atomic_write(run / 'recipes.bin', binary)
        if not args.check:
            install(args.output, binary)
            if args.runtime and pathlib.Path(args.runtime).resolve() != pathlib.Path(args.output).resolve():
                install(args.runtime, binary)
            atomic_write(path, json_bytes(snapshot))
        atomic_write(work / 'latest-changes.json', json_bytes(report))
        print(json.dumps(dict(mode=report['mode'], pages=pages, recipes=report['counts'],
                              total=len(records), report=str(run / 'changes.json'), requests=wiki.requests), indent=2))


if __name__ == '__main__':
    main()
