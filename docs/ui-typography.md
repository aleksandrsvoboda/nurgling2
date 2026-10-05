# UI typography

The approved fonts are those already used by the login screen, character sheet
and FPS graph: bundled Open Sans regular / Semibold, and Java SansSerif.
`nurgling.styles.UIFont` owns the family list and compatibility mappings.

- Buttons and headings use Open Sans Semibold; entry fields use Open Sans.
- Existing SansSerif labels and FPS diagnostics retain their family.
- Legacy Serif, Arial, Inter, Roboto and monospace requests use Open Sans;
  Fraktur uses Semibold. `Text.mono` remains a SansSerif compatibility alias.
- Saved font settings migrate families while preserving sizes and colours.
  Both font selectors expose the same approved list.
- `Text.Foundry` and `RichText` normalize font requests from resource widgets and
  server markup. Rich text resolves the bundled font object directly, preserving
  weight, italics, size, links and colours. Rich text antialiasing defaults to on.

`ant test-ui-typography` exercises legacy families, saved settings, glyph coverage,
text measurements and rich text overrides, and exports a raster sample to
`build/ui-fonts-preview/typography.png`. This verifies generated text, not every
window layout in a live game session. Decorative lettering baked into images is
outside the font rendering paths.
