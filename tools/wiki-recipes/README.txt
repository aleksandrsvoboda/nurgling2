Offline Ring of Brodgar recipe database
======================================

Runtime file: bin/recipes.bin (shipped beside hafen.jar).
Source asset: src/nurgling/craft/recipes.bin (also embedded in the JAR).
The client never downloads wiki pages. It checks the local binary every five
seconds in a background worker and loads a replacement only after validation.
Observations from the game server and favorites remain in character journals.

Get changes (run from the repository root):

  python tools/wiki-recipes/update_recipes.py --check
  python tools/wiki-recipes/update_recipes.py

--check creates a full before/after report without changing the installed files
or accepted snapshot. The second command obtains and applies the latest changes.
It updates both the source asset and bin/recipes.bin, so the next build includes
the same database. Override --snapshot, --output and --runtime when needed.
Without an existing snapshot it downloads a complete initial snapshot.

Each check uses a fresh Objects index and revision/category metadata. Only new
or changed page content is downloaded, by exact revision ID; unchanged content
is reused. Page IDs preserve identity across renames. Category changes are
recompiled too. Removed recipes disappear from the wiki base. Recipes previously
observed in game remain in the personal journal even if removed from the wiki;
server observations retain priority over wiki ingredient amounts. Favorites are
preserved by ID, including for removed pages that later return.

Reports: build/wiki-recipes/updates/latest-changes.json and per-run directories.
They include added/removed records, old/new values and changed fields. No-change
checks leave binary bytes untouched. Download/parse failures occur before any
installation. Each file is replaced atomically with a .bak copy; a locked file
leaves that file unchanged. Source/runtime replacements are separate atomic
operations; if the second fails, rerunning safely finishes synchronization.
The accepted snapshot advances only after installation. An interrupted update
may leave pages.update.lock; remove it only after the updater process has exited.
No automatic schedule or network polling is installed.

Shared resource icons and formula rendering
------------------------------------------
ItemIcons is the shared name/descriptor lookup and asynchronous texture cache
used by the craft atlas, area catalog and cookbook. Static resources and ordered
composite layers use the same cache keys. Wiki names are resolved through the
area catalog plus a generated name/alias index. Seeds, bulk materials and generic
families resolve even when the wiki omits its category flag. Roast/spitroast/smoked
meat combines the preparation and animal layers through the same Layered renderer
as IngredientContainer; observations can register additional descriptors. Menu
icons never overwrite known composites. Buildings use verified native menu
resources when available, otherwise their infobox image from the wiki. Unknown
names retain a neutral placeholder; the packer reports them explicitly.

src/nurgling/resources/item-icons.bin is a ZIP binary archive embedded in the
client JAR. It contains image/tooltip layers from verified game resources (no
executable resource code), bounded PNG/JPEG fallback images, index.json (version 1)
and sources.json with wiki file URLs, hashes and revision dates. Game artwork
belongs to Seatribe; wiki sources are credited to Ring of Brodgar contributors.
It is shared across the UIs, not duplicated inside every recipe. IngredientContainer
uses the same archive loader. To regenerate after compiling a recipe snapshot:

  python tools/wiki-recipes/pack_item_resources.py
  python tools/wiki-recipes/pack_item_resources.py --refresh

The first command reuses downloaded resources; --refresh retrieves current
versions from the official resource server, validating TLS with etc/ressrv.crt,
and refreshes wiki image metadata. Wiki image bytes are reused when their source
hash and thumbnail URL are unchanged. --snapshot and --recipes select the accepted
page snapshot and compiler inspection JSON. icon-overrides.json holds verified
native building descriptors; the packer fails before installation if any native
resource fails validation. It honors explicit infobox images and file redirects,
never takes unrelated gallery images or Legacy artwork.
Rebuild the client after changing this embedded archive. Report and download
cache: build/item-icon-resources/. The archive is deterministic and installation
retains a backup. Recipe refresh remains independent of this artwork archive.

JLaTeXMath 1.0.7 renders formulas entirely offline, bundled with the client and
checksum-pinned in build.xml. Only mathematical commands are accepted; file
access and macro definitions are not accepted from wiki expressions. Textures
are released with the detail widgets; image cache size is bounded. Library:
https://github.com/opencollab/jlatexmath

Rebuild with Python 3.9+ (standard library only), from the repository root:

  python tools/wiki-recipes/fetch_wiki.py --cache build/wiki-recipes/snapshot
  python tools/wiki-recipes/compile_recipes.py
  python -m unittest discover -s tools/wiki-recipes -p test_compiler.py
  ant test-wiki-recipes
  ant test-compass-atlas

For a manual full snapshot, use a new cache directory and pass its pages.json via
--snapshot to the compiler. Requests are read-only, throttled, resumable, and
use the public MediaWiki API. No credentials are needed. Normal client builds
do not access the wiki or regenerate the database.

The fetcher enumerates Category:Objects, follows every API continuation and
fetches content plus revision IDs, timestamps and categories. The compiler
includes explicit Craft/Build menu entries and workstation processing recipes,
including baked/fired stages linked with GM copy=. It excludes ordinary
forageables, fishing, carcass extraction and generic index pages without a
craft action. This is a snapshot of wiki knowledge, not a claim of server
completeness. It stores factual fields, source formulas and per-page revision
links, without article prose or images. Attribution: Ring of Brodgar contributors.

Amounts use the wiki's omitted-quantity convention of one; output yield is
unknown when the infobox does not state it. Liter and kilogram quantities are
preserved as decimals. Alternatives, staged construction, dynamic queries and
ambiguous processing inputs are kept as source expressions, not added together.
Their item references still support ingredient search and reverse links.
Server observations supersede wiki amounts, tools and quality modifiers.
Only a unique match to a currently available crafting action enables Open.
Baked/fired outputs are not mapped onto the preceding raw crafting action.

Compiler reports (not needed by the client): build/wiki-recipes/manifest.json,
recipes-inspect.json, review.json, excluded.json. Inspect review.json after an
update. The manifest contains source revisions and SHA-256 of the binary.

NRCPDB01 format (all integers big-endian, all strings UTF-8)
---------------------------------------------------------

Flat ingredient alternatives are stored as one material with category=true and
resource=wiki-choice:<JSON array of [wiki-item resource, label, category flag]>.
Its quantity applies once to the whole group. The client exposes the individual
options for icons and reverse recipe links; it never adds them together. All
options must share a quantity, including the wiki's common trailing suffix.
Unequal quantities, compound alternatives and construction stages remain source
expressions. The existing binary/journal layout stays compatible.

Header: ASCII NRCPDB01 (8 bytes), u32 raw size, u32 packed size, SHA-256(raw)
(32 bytes), then a zlib-compressed payload of exactly packed size bytes.

Payload: u32 string count; for each string, u32 byte length then bytes.
All subsequent strings are u32 indices into this table.
Metadata: source URL, UTC fetch timestamp, attribution; then u32 recipe count.
Each recipe:
  eight string indices: ID, name, group, source URL, revision timestamp, kind,
                        crafting action name, original ingredient expression;
  u32 revision ID; u8 arithmetic-known flag;
  input materials; output materials;
  four string lists: quality modifiers, workstations, requirements, formulas.
Material list: u32 count; each material is ID string index, name string index,
              IEEE-754 f64 amount (-1 means unknown), unit string index,
              u8 flags (bit 0 optional, bit 1 generic/category).
String list: u32 count, followed by that many u32 string indices.

The reader checks size/count bounds, compression, checksum, indices, flags,
amounts, duplicate IDs and trailing bytes. It does not deserialize Java objects.
Future incompatible changes require a new magic/version.

Quality workflow calculator
---------------------------
Open Quality calculator from the atlas. Drag recipes from its list onto the
canvas (or use Add recipe). Click a node header to expand/collapse, drag headers
to move, drag blank canvas to pan, and use the wheel to zoom around the pointer.
Orange = attributes, purple = skills, blue = tools, dark = ingredient quality.
Empty stat fields use the current character's modified CAttr.comp values. Empty
tool fields use the highest matching quality among loaded inventory/equipment
items, including loaded contents. World workstations without inventory data stay
unknown. Material fields require an explicit quality or an incoming connection.
A manual number/expression is green; an invalid override is red and produces an
unknown result instead of silently using a live value. Clear it to return to auto.

Drag a green recipe output to a material/tool input or an Output node. Right-click
an input port to disconnect. Output adds a green sink and connects the selected
recipe. A quality can feed multiple inputs. Self-links/cycles are rejected before
mutation; missing/invalid upstream values propagate as unknown. Values keep full
precision through the DAG and are displayed to two decimal places. Quantities are
not multiplied into quality. Undo/redo covers edits, nodes, connections and moves.

The formula panel lists the binding for each i/t/a/s variable and allows a custom
arithmetic expression. Supported functions: mean, geomean, sqrt, pow, min, max,
softcap(quality, cap), floor. No executable expression engine is used. Eligible
wiki TeX fractions/roots are parsed and bound to the recipe's own material/tool
names. Formulas for a tool's products are excluded by their left-hand side; only
an explicit skill-only formula supplies its softcap. Ambiguous or unsupported
formulas are not guessed. If a complete binding is unavailable, the node uses a
clearly marked approximate general model: equal material-type means, optional
3:1 material/tool weighting, and geometric skill softcap. A bound base without
a confirmed cap is also approximate when the wiki mentions quality skills.
Review/override these weights for recipes with special rules. Approximation flags
propagate to downstream nodes and the canvas output. Source/general rules:
https://ringofbrodgar.com/wiki/Quality

Each character saves craft-flow-<character-key>.json next to the atlas journal.
Version 1 stores stable node UUIDs, recipe IDs, positions, overrides, formulas,
edges and viewport. Save uses the existing atomic writer and .bak recovery;
bounded transactional loading rejects unsupported versions and cyclic files.
Each node stores its ordered input signature and a port-to-kind/name map. Reordered
inputs preserve manual values, edges and custom formula bindings by identity.
If an actively used input disappears or an old file lacks enough identity data,
calculation stops with an explanatory error; re-add that recipe to review inputs.
The exact legacy Kiln/Will bug is migrated automatically, retaining its clay input.
Formula-only updates use the new recipe formula; custom formulas remain explicit.
The compiler recognizes linked/formula stats and avoids treating English "will"
as the Will attribute. Explicit uncapped single-material construction prose is
normalized to a quality formula (Kiln inherits its clay quality).
Canvas drawing and nested input editors are GPU-scissored, including primitive
borders and circles. Connected inputs hide the manual source card; disconnecting
restores it with its earlier value. Toolbar icons reuse Area Settings artwork;
only undo/redo add new ImageGen sprites (art/ui/imagegen/flow-history.prompt.txt).
The DAG and resource summary recalculate automatically after edits and while live
character values change; there is no manual Calculate button. Legacy expanded
alternatives are migrated only after their exact old layout signature verifies.
A single populated alternative keeps its value/edge; conflicting populated
alternatives remain an explicit error rather than silently selecting one.
The calculator only reads game data and never sends crafting/inventory actions.

Flow cards show material quantities instead of internal port IDs. Drag a quality
card by its icon/header; click its value to edit it. Relative card positions are
saved with the recipe, support undo/redo, and migrate with input identities.
Rounded frames and directed Bezier edges use antialiased Java2D raster caching;
backward/stacked recipe edges use rounded corridors outside their endpoint cards.
The geometry follows the React Flow edge API concepts, implemented independently
in Java without a browser runtime: https://reactflow.dev/api-reference/utils/get-bezier-path
Connections can start at either handle. Hover Formula to see the active expression
rendered as LaTeX with ingredient/stat names; click it to edit. Background and
healthy recipe cards do not show a generic navigation tooltip.

Each Result node has an editable Crafts count (1..9999, default 1 in old saves).
It repeats the connected material chain: one operation of each upstream recipe
per repetition. Shared ancestors count once within one result, and counts from
multiple results add up. Recipes outside any result chain count once. Counts are
saved with the canvas and support undo/redo; they do not change quality values.
Editable canvas values share rounded input frames and centered text. Quality
cards reserve separate header and input rows; the focused editor keeps the same
font, dimensions and position, with matching caret/selection coordinates at zoom.
Canvas fonts scale proportionally down to the minimum zoom; label rows have fixed
world-space clipping so titles cannot spill into the input below them.
The fixed bottom table sums these external inputs. Quality-connected inputs are
excluded; tools, character stats and quality-only formula inputs are excluded.
This is a repeated-chain summary, not automatic batch planning: quality edges do
not carry output yields. Equal material identities are summed
with exact decimal arithmetic, keeping units and optional inputs separate.
Unknown quantities retain a ? alongside any known subtotal; non-additive wiki
compositions are listed as unresolved rather than silently counted. Table rows
use shared resource icons and scroll independently of the canvas.

Validation: ant test-craft-flow; UI scale 2: ant -Dcompass-atlas.scale=2 test-craft-flow.
The test covers wiki formula selection, live and manual values, clearing overrides,
missing tools, multi-hop propagation, cycles, JSON round-trips, corrupt-file
preservation, and native Vulkan rendering/mouse interactions.
