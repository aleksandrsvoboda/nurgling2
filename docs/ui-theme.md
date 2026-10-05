# Shared interface theme

The FPS window, saved-account list and character sheet are the visual references:
flat grey-green panels, light sans-serif text, muted outlines, orange focus/selection
accents and alternating rows. Original item, character and category artwork is retained.
Button plates, +/- controls and toolbar symbols are raster artwork generated with
the built-in ImageGen tool. The frames are simple flat grey/orange outlines, without
rust, metallic bevels or selection underlines.

`nurgling.styles.UITheme` owns the shared palette and primitives. `NStyle` uses the
same palette, so the existing flat window decoration and custom Nurgling panels
continue to match it.

## Coverage

- Text buttons: normal, hovered, pressed, selected and disabled; existing dimensions
  and activation callbacks retained. Disabled buttons also ignore keyboard activation.
- Checkboxes, drop-downs, list selection, scrollbars, sliders and tab strips.
- Single-line/password and multiline text fields, selections and focus/caret colours.
- Inventory cells, stockpile panels, generic frames and progress/vertical meters.
- Context menus, chat trim, equipment background, buff/combat frames and window sizing handle.
- Character/kin tab bezels: original image content is drawn unchanged inside a clipped
  border area; only the outer frame is replaced. Character tabs show their active state.
- Main HUD toggle button bezels use the same treatment, retaining the original icons.

The obsolete selector for four ornamental text-button skins is removed. Its saved
preference and loader API remain compatible with old configs/resource code.

`UIResources` supplies procedural replacements for a small explicit list of background
and frame resources. It preserves dimensions, layer IDs and offsets. It must not load
other Haven resources during `Resource.Image` construction, or match whole icon/world
resource namespaces. Component RGBA images are required for `PUtils.uiscale` compatibility.

## Validation

`tools/render-parity/UiThemeTest.java` draws real widgets through the renderer, exercises
button/check/slider input and tabs, checks that icon and world resource paths are not
overridden, and saves GPU readbacks. The test finishes window opening animations and
waits for asynchronous Vulkan pipelines; an empty screenshot fails validation.

Run from the project root after building `build/hafen.jar` and installing runtime jars
in `bin` (PowerShell, Java 17 on PATH):

```powershell
New-Item -ItemType Directory -Force build/ui-theme-test | Out-Null
javac -encoding UTF-8 -cp 'build/hafen.jar;bin/*' -d build/ui-theme-test tools/render-parity/UiThemeTest.java
$themeCp = (@('build/ui-theme-test', 'build/hafen.jar') + @(Get-ChildItem bin -Filter *.jar | Where-Object Name -ne 'hafen.jar' | ForEach-Object FullName)) -join ';'
java '-Dhaven.uiscale=1.0' -cp $themeCp haven.UiThemeTest vulkan
java '-Dhaven.uiscale=1.5' -cp $themeCp haven.UiThemeTest vulkan
java '-Dhaven.uiscale=1.0' -cp $themeCp haven.UiThemeTest jogl
```

Verified: complete Ant jar build, Vulkan at 100% and 150%, typography regression
(Cyrillic, Latin, rich text, measurements and scaling), and `git diff --check`.
Previews: `build/ui-theme-preview/vulkan-100.png` and `vulkan-150.png`.
JOGL validation on this host could not create a Windows graphics configuration;
it failed before drawing widgets, including when run in a separate process.

The full build also exposed an existing `EnvMap` reference to the old water-sky sampler
type. It now uses `WaterTile.waterSky()` with a frame dependency, like the water pass,
so asynchronously prepared sky data can replace the fallback instead of being cached forever.

The quest tracker uses the character-sheet Open Sans regular/semibold pair at the
configured quest text size, preserving its quest/status colours. Grouping, search
and settings use inventory-style orange outline controls; category filters show a
dialogue bubble (NPC), working tools (credo specializations), and globe (world).
Search and inventory share the generated magnifier. Orange toolbar symbols are
borderless with subtle hover/active highlights; +/- buttons keep square frames.
Its background fills the frame,
and the resize grip and hit area share the tracker's bottom-right corner.
`QuestTrackerThemeTest.java`, compiled alongside `UiThemeTest.java`, captures real
quest rows, filter/search states and the resize corner at 100% and 150% using
`haven.QuestTrackerThemeTest`. Fixtures use no account or server data.

## ImageGen button artwork

Masters and exact generation/edit prompts are in `art/ui/imagegen/`. The original
textured plate proposal was superseded by the flat-frame edit in `prompts.json`.
`tools/render-parity/PackGeneratedButtons.java` crops the masters into runtime PNGs
in `src/nurgling/styles/assets/buttons/`; it does not redraw the artwork. The plate
crops exclude exterior glow from the master. `GeneratedButtons` uses nine-slice
scaling and caches resulting textures; the generated glyphs retain their aspect ratio.

The shared renderer covers text/image button bezels, character tabs, combat save
and category controls, steppers, checkboxes, drop-downs, quest toolbar/action buttons,
inventory header controls and flower-menu choices. Explicit resource overrides
provide generated close, settings, inventory and ability symbols while preserving
resource dimensions and offsets. These overrides do not match game-icon namespaces.
