import struct
import unittest
from pack_item_resources import images_only
from icon_catalog import native_catalog, image_title, png_size, image_size, wanted_names
from pathlib import Path


class IconPackTests(unittest.TestCase):
    def test_choice_members_have_independent_artwork(self):
        names = wanted_names([dict(name='Canape', outputs=[], inputs=[dict(name='Roe or Caviar',
            resource='wiki-choice:[["wiki-item:roe","Roe",false],["wiki-item:caviar","Caviar",false]]')])])
        self.assertIn('roe', names)
        self.assertIn('caviar', names)
        self.assertNotIn('roe or caviar', names)

    def test_native_names_and_composed_meat(self):
        names = native_catalog(Path('src/nurgling/tools/VSpec.java').read_text(encoding='utf-8'))
        self.assertEqual(names['seeds of barley'], {'static': 'gfx/invobjs/seed-barley'})
        self.assertEqual(names['seeds of sprouted barley'], {'static': 'gfx/invobjs/seed-barleygerm'})
        self.assertEqual(names['spitroast bear']['layer'], ['gfx/invobjs/meat-spitroast', 'gfx/invobjs/meat-bear'])
        self.assertEqual(names['spitroast abyss gazer']['layer'], ['gfx/invobjs/meat-spitroast', 'gfx/invobjs/meat-abyssgazer'])
        self.assertEqual(names['bar of hard metal'], names['bar of bronze, iron or steel'])
        for key in ['board', 'block of wood', 'crop seeds', 'any flour', 'silk filament']:
            self.assertIn(key, names)

    def test_infobox_image_is_not_an_arbitrary_article_image(self):
        self.assertEqual(image_title({'title': 'Sawmill'}), 'File:Sawmill.png')
        page = dict(title='Spitroast Bear', revisions=[dict(slots=dict(main=dict(content=
            '{{infobox metaobj|image=file:Spitroast Meat.png}} [[File:Unrelated.png]]')))])
        self.assertEqual(image_title(page), 'File:Spitroast Meat.png')

    def test_png_dimensions_are_bounded(self):
        header = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR'
        self.assertEqual(png_size(header + struct.pack('>II', 32, 64)), (32, 64))
        with self.assertRaises(ValueError): png_size(header + struct.pack('>II', 99999, 32))
        with self.assertRaises(ValueError): png_size(b'not a png')

    def test_jpeg_dimensions_are_bounded(self):
        header = b'\xff\xd8\xff\xc0' + struct.pack('>HBHH', 7, 8, 90, 96)
        self.assertEqual(image_size(header), (96, 90))
        with self.assertRaises(ValueError): image_size(header[:-1])
        with self.assertRaises(ValueError): image_size(b'\xff\xd8\xff\xc0' + struct.pack('>HBHH', 7, 8, 9999, 96))

    def test_archive_excludes_executable_resource_layers(self):
        header = b'Haven Resource 1' + struct.pack('<H', 7)
        def layer(name, value):
            return name + b'\0' + struct.pack('<I', len(value)) + value
        image = layer(b'image', b'pixels')
        tooltip = layer(b'tooltip', b'Item name\0')
        code = layer(b'code', b'executable resource code')
        self.assertEqual(images_only(header + code + image + tooltip), header + image + tooltip)

    def test_bad_or_imageless_resource_rejected(self):
        with self.assertRaises(ValueError):
            images_only(b'not a resource')
        with self.assertRaises(ValueError):
            images_only(b'Haven Resource 1\0\0')
        with self.assertRaises(ValueError):
            images_only(b'Haven Resource 1\0\0image\0' + struct.pack('<I', 100) + b'short')
