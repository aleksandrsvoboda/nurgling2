# Vulkan baseline and optional enhancements

## Live FPS panel

Toggle **Show FPS graph** in Graphics settings or press **Ctrl+F10**. The panel
can be dragged by its title and closed without affecting the F11 database HUD.
Launch with `-Dhaven.fpsgraph=true` to show it immediately for diagnostics.
It works with either renderer and keeps a separate history for each session.

The upper graph shows individual client frame rates over ten seconds; the lower
graph shows frame duration, retaining spikes even when samples share a pixel.
The FPS headline averages the most recent second. The other statistics cover
the ten-second history: average/minimum FPS, 1% low (reciprocal of the mean
duration of the slowest 1% of frames), p99/max duration, and frames over 50 ms.
Times include simulation, render waiting and the FPS limiter; they are client
frame cadence, not GPU execution time or measured monitor presentation. A new
session or session switch resets the history. Recording uses a bounded ring
without per-frame allocations; the visible panel refreshes its texture at 10 Hz.

`ant test-frame-history` checks the statistics and writes English/Russian
previews at 1x/2x scale to `build/fps-preview/`.

The collapsed **Debug** section offers a local time-of-day slider and independent
rain/snow previews, using the normal weather particles on both renderers. Moving
the slider applies an illustrative daily lighting cycle; it does not change the
server clock, season, astronomy or gameplay. Vulkan's optional time-of-day grading
and weather enhancements follow the preview as well. **Restore server time and
weather** clears all overrides. Unchecked weather previews leave server weather
intact. Collapsing or hiding the panel keeps previews active; leaving the session
discards them. `ant test-scene-debug` checks defaults, the daily light cycle,
weather replacement, reset and effect disposal.

## Baseline visuals

Selecting Vulkan changes the rendering backend, without opting into a new visual
style. `NGfx.Settings.enabled` defaults to false, including for configurations
saved before this switch existed. Existing effect choices remain stored, but
only take effect after explicitly enabling **Visual enhancements** or choosing
**Enhanced** / **Ultra** in Nurgling's Graphics settings.

Turning enhancements off preserves the individual choices. **Classic** instead
resets all enhancement settings, including tilt-shift and upscaling. OpenGL
always uses the baseline settings. The ordinary video options (outlines,
shadows, render scale, etc.) are still independent and must match when comparing
backends.

## Automated checks

**Color > Better lighting** is an opt-in palette for directional and ambient
light: warm horizon sunlight, neutral daytime light, blue moonlight and cooler
night ambient light. Strength blends from the original light (0) to the full
palette (1). The server's shadow direction is retained. Missing or black outdoor
lights are left alone, including underground. Debug's time slider previews the
cycle. The old time-of-day tint checkbox and saved `tod` flag have been removed;
there is no hidden tint stacked over the lighting option. Auto-exposure activates
the color pass independently. Highlight compression applies only with tone mapping
or bloom. `ant test-color-lighting` checks this contract and GPU grading output.

**Lighting > Better shadows** replaces the directional shadow path with two
world-stable 2048-square maps: a detailed 440-unit-wide region around the player
and a 1500-unit-wide distant region. Texel-snapped cameras update each tick;
overlapping regions blend smoothly and the outer boundary fades. Both maps reuse
the engine's geometry caster list. Depth storage is 32 MiB plus local-light maps.
A blocker search estimates penumbra width; 16 stable bilinearly filtered comparison
taps keep contact edges tight and soften distant shadows. Receiver-plane correction
and a bounded bias prevent sloped surfaces from shadowing themselves. This is a
shadow-map approximation, not ray tracing or physically exact area-light shadows.

Directional visibility and cloud cover attenuate direct diffuse and specular light
together, leaving ambient light. In this mode (or with Better lighting), materials
use continuous lighting rather than the legacy cel thresholds: a .49 -> .51 light
transition no longer doubles a material's brightness. The daily palette preserves
matte lights and caps specular energy instead of inventing strong highlights.
Direct diffuse light rises smoothly to a 35% boost at noon, tapering with the fourth
power of daytime sun height to zero at dawn/dusk; ambient light and night brightness
are unchanged by this boost. The setting's strength blends this along with the palette.
Procedural clouds move more slowly and have a broader, weaker transition.

AO remains an optional screen-space approximation: Better shadows reduces its radius
from 7 to 2 world units and bounds compositing by an estimated ambient share (10–35%)
to avoid a second broad dark silhouette. It is not an actual separate ambient buffer.
Up to two nearby local lights cast shadows automatically (a larger selected count
is respected), using six atlas faces, filtered comparisons and the actual light
position. The legacy mode can still lift ground-level light positions. Atlas edges
remain face-clamped; seamless cube filtering and baked texture shading are outside
this implementation.

The option overrides legacy shadow resolution/softness and can enable shadows even
when the video shadow toggle is off. Disabling it restores the legacy settings;
Classic never enables it. Two geometry passes and local-light maps can increase GPU
cost; scene performance still needs checking in game. `ant test-better-shadows`
checks texel stability, slopes, widening penumbra, near/far blending and upward point
shadows on GPU, plus actual caster passes with the production Phong shader in both
zones. `ant test-color-lighting` also reproduces and checks the cel brightness jump.

`ant test-vulkan-pacing` reproduces the UI's FRAME-mode overlap (tick, previous
CPU fence, draw and present), rather than waiting for each frame's final callback
before preparing another. It also checks VSync toggles, swapchain recreation on
resize and hidden-window recovery. Run on an otherwise idle desktop for meaningful
timings. On a 60 Hz RTX 5090 test, the previous path repeated roughly 33/3/13 ms
client intervals; presentation pacing reduced p95 from about 33.6 to 18.0 ms,
with the same 16.7 ms mean. This is a synthetic scheduling result, not a claim
that every game-scene stall has been removed.

With VSync enabled, supported devices now use `VK_KHR_present_id` and
[`vkWaitForPresentKHR`](https://docs.vulkan.org/refpages/latest/refpages/source/vkWaitForPresentKHR.html)
to pace the render queue by presentation completion. GPU submission fences alone
can be released in bursts and do not measure presentation. Both extensions and
feature bits are checked before enabling this path. Waits are bounded to 100 ms
for hidden/changing surfaces. Without support, or with VSync disabled, the prior
queue path remains available; `-Dhaven.vkpacing=false` provides an A/B diagnostic
override. The extension does not promise an exact physical scanout timestamp.

Run `ant test-graphics-baseline` for the configuration contract, or
`ant test-renderer-parity` for that check plus the GPU comparison. The latter
requires a desktop, working JOGL/OpenGL and Vulkan 1.3. It briefly opens test
windows, uses deterministic local geometry and textures, and does not connect
to a game server or account. Images are saved in `build/render-parity/`.

The GPU cases cover colors, alpha/additive blending, minification and
magnification, mipmaps, sRGB, RGB texture uploads, Phong/cel lighting, depth and
normal buffers (sampling and readback), face culling, and scissoring. A uniform
image is rejected, so two empty renders cannot pass. Each comparison reports
the maximum and mean channel difference, and the number of channels differing
by more than 2/255. More than 0.1% of channels over that threshold fails the run.

`ant test-vulkan-frames` checks frame scheduling in a temporary Vulkan window.
It renders nested geometry with CPU fences before drawing and after presentation,
matching the UI loop's callback placement. Each unprofiled frame must produce
one GPU submission; a render containing only callbacks, including nested ones,
must produce none. The previous executor submitted twice per frame and copied
the parent arena again after presentation, potentially waiting for a GPU slot
before delivering the UI callback. Frame-time statistics are diagnostic only;
this small scene does not measure in-game camera smoothness.

## Compatibility fixes covered

- Non-mipmapped textures use `maxLod = 0.25` with nearest mip selection. Zero
  incorrectly forced the magnification filter when minifying. This follows the
  [Vulkan sampler specification](https://docs.vulkan.org/spec/latest/chapters/samplers.html).
- RGB textures stored as RGBA sample/read back alpha as one, as OpenGL does.
  A vec3 fragment output does not define the padded alpha channel.
- Sampling views can remap channels; framebuffer attachment views use identity
  mappings, including depth attachments, as required by
  [VkRenderingInfo](https://docs.vulkan.org/refpages/latest/refpages/source/VkRenderingInfo.html).

These checks catch backend regressions; they do not establish pixel identity
for every game material or animated scene. For an in-game comparison, disable
enhancements, use the same video settings, camera, scale, place and lighting,
and account for animation and world-time changes between captures.
