import copy
import json
import pathlib
import uuid
import unittest
from contextlib import contextmanager
from unittest.mock import patch

from update_recipes import refresh, differences, install, update_lock


@contextmanager
def test_directory():
    root = pathlib.Path('build/wiki-recipes/tests').resolve()
    directory = root / uuid.uuid4().hex
    directory.mkdir(parents=True)
    try:
        yield directory
    finally:
        assert directory.resolve().parent == root
        for file in directory.iterdir():
            file.unlink()
        directory.rmdir()


def page(key, title, rev, content='recipe'):
    return dict(pageid=key, title=title, ns=0, categories=[{'title': 'Category:Objects'}],
                revisions=[dict(revid=rev, timestamp='2026-10-05T00:00:00Z',
                                slots={'main': {'content': content}})])


class FakeWiki:
    def __init__(self, pages):
        self.pages = copy.deepcopy(pages)
        self.downloaded = []

    def members(self, _):
        return [{k: p[k] for k in ('pageid', 'title', 'ns')} for p in self.pages]

    def query(self, **params):
        if 'revids' in params:
            ids = {int(s) for s in params['revids'].split('|')}
            self.downloaded.extend(ids)
            return {'query': {'pages': [copy.deepcopy(p) for p in self.pages if p['revisions'][0]['revid'] in ids]}}
        ids = {int(s) for s in params['pageids'].split('|')}
        pages = [copy.deepcopy(p) for p in self.pages if p['pageid'] in ids]
        for p in pages:
            p['revisions'][0].pop('slots')
        return {'query': {'pages': pages}}


class UpdateTests(unittest.TestCase):
    def test_incremental_add_change_remove_and_rename(self):
        old = [page(1, 'Old name', 1), page(2, 'Changed', 2), page(3, 'Deleted', 3)]
        wiki = FakeWiki([page(1, 'New name', 1), page(2, 'Changed', 22, 'new content'), page(4, 'Added', 4)])
        snapshot, counts = refresh(wiki, {'pages': old})
        self.assertEqual(set(wiki.downloaded), {22, 4})
        self.assertEqual(counts, dict(added=1, removed=1, contentDownloaded=2, reused=1))
        by_id = {p['pageid']: p for p in snapshot['pages']}
        self.assertEqual(by_id[1]['title'], 'New name')
        self.assertEqual(by_id[1]['revisions'][0]['slots']['main']['content'], 'recipe')
        self.assertEqual(by_id[2]['revisions'][0]['slots']['main']['content'], 'new content')
        self.assertEqual(old[0]['title'], 'Old name')

    def test_no_change_downloads_no_content_but_refreshes_categories(self):
        old = page(1, 'Same', 1)
        updated = copy.deepcopy(old)
        updated['categories'].append({'title': 'Category:GenericTypePage'})
        wiki = FakeWiki([updated])
        snapshot, counts = refresh(wiki, {'pages': [old]})
        self.assertFalse(wiki.downloaded)
        self.assertEqual(snapshot['pages'][0]['categories'], updated['categories'])
        self.assertEqual(counts['reused'], 1)

    def test_category_continuation_is_merged(self):
        wiki = FakeWiki([page(1, 'Same', 1)])
        original = wiki.query
        def query(**params):
            result = original(**params)
            if 'clcontinue' not in params:
                result['continue'] = {'clcontinue': 'next', 'continue': '||'}
            else:
                result['query']['pages'][0]['categories'] = [{'title': 'Category:Second'}]
            return result
        wiki.query = query
        snapshot, _ = refresh(wiki, {'pages': [page(1, 'Same', 1)]})
        self.assertEqual(len(snapshot['pages'][0]['categories']), 2)

    def test_partial_metadata_or_hidden_content_aborts(self):
        wiki = FakeWiki([page(1, 'Changed', 2)])
        original = wiki.query
        def missing(**params):
            result = original(**params)
            if 'revids' in params:
                result['query']['pages'][0]['revisions'][0]['slots'] = {}
            return result
        wiki.query = missing
        with self.assertRaisesRegex(ValueError, 'missing or hidden'):
            refresh(wiki, {'pages': [page(1, 'Changed', 1)]})
        wiki.query = lambda **params: {'query': {'pages': []}}
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            refresh(wiki, {'pages': []})

    def test_change_report_retains_exact_before_after(self):
        old = [dict(resource='wiki:1', name='A', revision=1), dict(resource='wiki:2', name='B')]
        new = [dict(resource='wiki:1', name='Renamed', revision=2), dict(resource='wiki:3', name='C')]
        report = differences(old, new)
        self.assertEqual(report['changed'][0]['fields'], ['name', 'revision'])
        self.assertEqual(report['changed'][0]['before'], old[0])
        self.assertEqual(report['added'], [new[1]])
        self.assertEqual(report['removed'], [old[1]])
        self.assertFalse(any(differences(old, old).values()))

    def test_atomic_install_failure_noop_and_backup(self):
        with test_directory() as directory:
            target = pathlib.Path(directory) / 'recipes.bin'
            self.assertTrue(install(target, b'old'))
            self.assertFalse(install(target, b'old'))
            self.assertFalse(target.with_name('recipes.bin.bak').exists())
            import os
            replace = os.replace
            def fail(source, destination):
                if pathlib.Path(destination) == target:
                    raise OSError('simulated full disk / locked file')
                replace(source, destination)
            with patch('update_recipes.os.replace', side_effect=fail):
                with self.assertRaises(OSError):
                    install(target, b'new')
            self.assertEqual(target.read_bytes(), b'old')
            self.assertTrue(install(target, b'new'))
            self.assertEqual(target.with_name('recipes.bin.bak').read_bytes(), b'old')
            self.assertEqual(target.read_bytes(), b'new')
            self.assertEqual(len(list(pathlib.Path(directory).glob('*.tmp'))), 0)

    def test_concurrent_updater_is_rejected(self):
        with test_directory() as directory:
            lock = pathlib.Path(directory) / 'update.lock'
            with update_lock(lock):
                with self.assertRaises(RuntimeError):
                    with update_lock(lock):
                        self.fail('Concurrent updater entered')
            self.assertFalse(lock.exists())


if __name__ == '__main__':
    unittest.main()
