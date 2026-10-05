import json
import pathlib
import unittest
import hashlib
import struct
import zlib
from compile_recipes import parse_inputs, split_top, properties, templates, extract, encode, MAGIC, quality_modifiers


class ParserTests(unittest.TestCase):
    def test_will_prose_is_not_an_attribute(self):
        self.assertEqual(quality_modifiers('The end product will increase. Will this change? [[Masonry]] applies.'), ['Masonry'])
        self.assertEqual(quality_modifiers('Softcapped by [[Will]] and [[Masonry]].'), ['Masonry', 'Will'])
        self.assertEqual(quality_modifiers('<math>\\sqrt{Will*Masonry}</math>'), ['Masonry', 'Will'])
    def test_nested_template_fields(self):
        template = '{{infobox metaobj|objectsreq={{#ask:[[Specific::Milk]]|format=list}}|skillreq=[[requires::Sewing|Sewing]]}}'
        box = next(properties(fields) for name, fields in templates(template) if name == 'infobox metaobj')
        self.assertEqual(box['objectsreq'], '{{#ask:[[Specific::Milk]]|format=list}}')
        self.assertEqual(len(box), 2)

    def test_fractional_units_and_optional(self):
        items, _, reason = parse_inputs('[[requires::Water]] (0.5 liters), [[Any Flour]] x0.55 kg, optional:[[Spices]]', 'Pie', {'spices'})
        self.assertFalse(reason)
        self.assertEqual([(i['amount'], i['unit']) for i in items], [(0.5, 'l'), (0.55, 'kg'), (1, '')])
        self.assertTrue(items[2]['optional'])
        self.assertTrue(items[2]['category'])

    def test_quantity_prefix_and_unknown_qualifier(self):
        items, _, reason = parse_inputs('2 [[Prepared Animal Hide]], 1 [[String]]', 'Waterskin', set())
        self.assertFalse(reason)
        self.assertEqual([i['amount'] for i in items], [2, 1])
        items, text, reason = parse_inputs('[[Stone]] x100 (avg)', 'Well', set())
        self.assertEqual(items, [])
        self.assertIn('(avg)', text)
        self.assertTrue(reason)

    def test_alternatives_not_added_together(self):
        items, text, reason = parse_inputs('[[Raw Pork]] or [[Raw Wild Pork]] x2, [[Intestines]]', 'Sausage', set())
        self.assertFalse(reason)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]['amount'], 2)
        self.assertTrue(items[0]['resource'].startswith('wiki-choice:'))
        self.assertEqual(len(json.loads(items[0]['resource'].removeprefix('wiki-choice:'))), 2)
        self.assertIn(' or ', text)

    def test_ambiguous_alternatives_still_require_review(self):
        for value in ['[[A]] x1 or [[B]] x2', '[[A]] and [[B]] or [[C]]', '[[A]] or [[B]] to pull', '( [[A]] or [[B]] ) x3']:
            items, _, reason = parse_inputs(value, 'Complex', set())
            self.assertEqual(items, [])
            self.assertTrue(reason)

    def test_caviar_choice_and_optional(self):
        value = '[[Seedcrisp Flatbread]], [[Milk]] (0.25 liters), [[Roe]] or [[Fineroe]] or [[Caviar]] (0.25 kg), optional:[[Spices]]'
        items, _, reason = parse_inputs(value, 'Canape', {'spices'})
        self.assertFalse(reason)
        self.assertEqual([(i['amount'], i['unit']) for i in items], [(1, ''), (.25, 'l'), (.25, 'kg'), (1, '')])
        self.assertEqual([o[1] for o in json.loads(items[2]['resource'].removeprefix('wiki-choice:'))], ['Roe','Fineroe','Caviar'])
        self.assertTrue(items[3]['optional'])

    def test_or_in_item_name_is_not_an_operator(self):
        items, _, reason = parse_inputs('[[Seed of Tree or Bush]] x2, [[Entrails]]', 'Offering', {'seed of tree or bush'})
        self.assertFalse(reason)
        self.assertEqual(items[0]['amount'], 2)

    def test_invisible_semantic_links_not_materials(self):
        items, _, reason = parse_inputs('[[Board]] x5 <span style="display:none">[[Great Hall]][[Pickaxe]]</span>', 'Furniture', set())
        self.assertFalse(reason)
        self.assertEqual(len(items), 1)


class SnapshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshot = json.loads(pathlib.Path('build/wiki-recipes/snapshot/pages.json').read_text(encoding='utf-8'))
        cls.records, cls.skipped, cls.review = extract(cls.snapshot)
        cls.by_name = {r['name']:r for r in cls.records}

    def test_completeness_and_no_foraged_items(self):
        self.assertGreaterEqual(len(self.records), 1000)
        self.assertEqual(len({r['resource'] for r in self.records}), len(self.records))
        self.assertNotIn('Dandelion', self.by_name)
        self.assertNotIn('Eel', self.by_name)
        for name in ['Stone Axe', 'Bucket', 'Linen Cloth', 'Unbaked Apple Pie', 'Apple Pie', 'Palisade', 'Bar of Gold']:
            self.assertIn(name, self.by_name)

    def test_wiki_stage_and_yield_not_fabricated(self):
        self.assertEqual(self.by_name['Apple Pie']['inputs'][0]['name'], 'Unbaked Apple Pie')
        self.assertEqual(self.by_name['Apple Pie']['kind'], 'process')
        self.assertEqual(self.by_name['Unbaked Apple Pie']['actionName'], 'Apple Pie')
        self.assertEqual(self.by_name['Bucket']['inputs'][0]['amount'], 2)
        self.assertEqual(self.by_name['Stone Axe']['inputs'][0]['amount'], 1)
        self.assertFalse(self.by_name['Bar of Cast Iron']['quantitiesKnown'])
        self.assertEqual(self.by_name['Bucket']['outputs'][0]['amount'], -1)
        canape = self.by_name['Caviar Canapé']
        self.assertTrue(canape['quantitiesKnown'])
        self.assertEqual(len(canape['inputs']), 4)
        self.assertEqual(self.by_name['Kiln']['quality'], [])
        self.assertEqual(self.by_name['Kiln']['formulas'][0], '_qKiln = _qClay')

    def test_binary_is_deterministic_and_checksums_payload(self):
        binary = encode(self.records, self.snapshot)
        self.assertEqual(binary, encode(self.records, self.snapshot))
        self.assertEqual(binary[:8], MAGIC)
        rawlen, packedlen = struct.unpack('>II', binary[8:16])
        self.assertEqual(packedlen, len(binary) - 48)
        raw = zlib.decompress(binary[48:])
        self.assertEqual(rawlen, len(raw))
        self.assertEqual(binary[16:48], hashlib.sha256(raw).digest())

    def test_provenance_and_no_template_leaks(self):
        for recipe in self.records:
            self.assertIn('&oldid=' + str(recipe['revision']), recipe['source'])
            self.assertNotIn('{{', recipe['inputText'])


if __name__ == '__main__':
    unittest.main()
