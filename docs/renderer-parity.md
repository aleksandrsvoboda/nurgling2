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
without per-frame allocations. At 10 Hz the visible panel requests a background
raster and RGBA conversion, then uploads finished pixels into one persistent,
exact-size streaming texture. There is at most one pending raster per panel;
the UI never waits for it. Hidden panels stop requesting work. This avoids doing
Java2D conversion into a new power-of-two texture on every graph refresh, which
can itself create the frame spikes being measured. `fps-panel` diagnostic scopes
measure remaining UI-side publication/upload work.

`ant test-frame-history` checks the statistics and writes English/Russian
previews at 1x/2x scale to `build/fps-preview/`.
`ant test-fps-graph-texture` additionally blocks the raster worker to verify that
the UI stays nonblocking, and checks GPU pixels, native texture reuse, resize and
disposal with both Vulkan and OpenGL.

The collapsed **Debug** section offers a local time-of-day slider and independent
rain/snow previews, using the normal weather particles on both renderers. Moving
the slider applies an illustrative daily lighting cycle; it does not change the
server clock, season, astronomy or gameplay. Vulkan's optional time-of-day grading
and weather enhancements follow the preview as well. **Restore server time and
weather** clears all overrides. Unchecked weather previews leave server weather
intact. Collapsing or hiding the panel keeps previews active; leaving the session
discards them. `ant test-scene-debug` checks defaults, the daily light cycle,
weather replacement, reset and effect disposal.

## Movement hiccup capture (OpenGL and Vulkan)

Press **Ctrl+F9** immediately after a movement hitch. The client keeps a bounded
in-memory trace and saves ten seconds before the keypress plus two seconds after
it to `diagnostics/movement-*.zip` relative to the client's working directory
(`bin/diagnostics` for the development launcher). A game message confirms the
absolute path or reports a write error. Repeated presses during capture are ignored.
The capture itself does not change movement or continuously write to disk.

The archive contains `trace.csv` and a schema/context `README.txt`: applied server
movement deltas, predicted and scene-placed coordinates, rider/mount IDs, camera
matrix, frame cadence and CPU stage timings, collector notifications, and UI-thread
stack samples during stages exceeding 20 ms. GC event duration is collector-specific
and is not always a stop-the-world pause. Frame cadence is not GPU presentation time;
stack sampling is not a complete CPU profile. The trace records only the foreground
session's player/mount; login starts a fresh buffer. Insufficient history, unavailable
positions and capacity truncation are documented in the archive.
Schema v2 also records socket-read completion (before decrypt/parse), object dispatch,
queue entry, delta attempt start, gob-monitor wait and handler duration. Packet ID,
server frame, attribute type and actor ID correlate loader retries with receipt.
Socket-read completion does not measure kernel arrival or server send time: a burst
already present at that boundary can still be receiver scheduling, OS buffering or
network/server timing. Rejected/stale object deltas have a receive event without an
apply event. Missing timestamps remain blank (e.g. replay transports).
Nested `stage` events retain UI/graphics/hover, core tasks, scene placement, camera,
terrain, lighting/weather and overlay scopes lasting at least 1 ms. They are inclusive;
do not add a parent to its children. The watchdog reports the innermost active scope.
Terrain timings separate mesh updates, decoration, overlays, gridlines and picking;
time spent waiting for the map monitor remains in the enclosing scope.
`weather-state` isolates applying the global weather state inside `lighting-weather`.
MapView reserves the Wet and CloudShadow slots before attaching the scene. Weather
resource operations remain live, but disabling/removing them restores the incoming
value (normally null) without dropping a slot definition. This uses RenderTree's
group/mask updates instead of `updtotal` across every descendant; parent dependencies
and explicit null overrides remain supported. `test-weather-state` reproduces the
old 10,000-node rebuild, asserts zero unrelated descendant reevaluations after the
fix, and verifies add/change/remove uniform updates by Vulkan readback. New shader
variants may still need asynchronous preparation; this change targets CPU tree work.
Vulkan tracks shader changes in stable weather groups and retains valid uniforms
for the old program until its replacement is ready. This prevents Wet.param from
reading a removed Wet state. The GPU weather test exercises real Wet.param
transitions with synchronous and cold asynchronous shader variants.
Each archive also contains `frame-summary.txt` (average FPS, 1%/0.1% lows,
nearest-rank frame-time percentiles, worst duration and counts above 20/33.3/50/100 ms)
and `slow-frames.csv` (up to 32 worst frames, same schema, longest first).
Lows are 1000 divided by the mean duration of the slowest ceil(N * fraction) frames;
sample counts are included because 0.1% of a short capture may be just one frame.
These describe CPU submission/pacing cadence, not measured GPU presentation. All
statistics and sorting happen on export, with no extra per-frame history or disk IO.
During a Vulkan stall, stacks also include that environment's render and callback
threads (and monitor owners), to distinguish UI fence waits from renderer work.
`map-cut` events record each cut attachment/replacement/removal, its coordinates,
grid class and CPU duration, including operations shorter than 1 ms. They let a
boundary crossing be correlated with work on the following frames.
`render-stage` events additionally time Vulkan ring allocation/reset, new buffer
and texture preparation, draw-list preparation, queued uploads, GPU-fence waits,
queue-present and present-wait calls taking at least 2 ms. They use `stage_start_s`/`stage_ms`,
include return codes or buffer sizes, and describe CPU call time rather than GPU
execution time. This separates a long native wait from a chance stack sample.
`MovementTraceTest` checks retention, wraparound, actor/session isolation, the hotkey,
pre/post capture, repeat handling, archive encoding, network timestamp cloning and export,
queue/lock timings, nested stage restoration and disposal without a game server.

Movement prediction integrates a per-trajectory monotonic clock. Both `ctick` and
server `sett` consume that clock before advancing/correcting `t`, so a frame stall
is not added again after a correction and new trajectories do not inherit an old
frame's duration. `test-movement-timing` replays the recorded GL/Vulkan stalls and
checks cadence, late updates, prediction limits and end times. Authoritative
trajectory replacement is still respected by the gameplay coordinates.

Rendering reconciles small `linbeg`/`linstep`/stop corrections separately: a visual
offset decays with a 120 ms time constant, without filtering ordinary movement.
The world tick publishes one XY position used by scene placement and the camera,
including the mount followed by a rider. Network changes between world tick and
rendering cannot replace that published position. `Gob.getc()` and authoritative
movement remain unchanged. Corrections over 55 world units snap (teleports), and
switching to other movement modes clears reconciliation. This path is shared by
OpenGL and Vulkan. `test-movement-smoothing` checks captured-size correction bursts,
frame stability, steady motion without lag, settling after stopping and teleports.
`predicted_*` remains unsmoothed in diagnostics; a temporary difference from
`placed_*` is now expected during reconciliation.

Water sky image decoding and all six RGBA face conversions run on a dedicated
daemon thread. A tiny fallback is used until ready; the sampler refreshes via
`FrameInfo` on both renderers. GPU upload remains on the renderer, using immutable
prepared bytes that also survive context recreation. `test-water-sky` checks the
nonblocking path and byte-for-byte parity with the former cubemap conversion.

## Baseline visuals

Selecting Vulkan changes the rendering backend, without opting into a new visual
style. `NGfx.Settings.enabled` defaults to false, including for configurations
saved before this switch existed. Existing effect choices remain stored, but
only take effect after explicitly enabling **Visual enhancements** or choosing
**Enhanced** / **Ultra** in Nurgling's Graphics settings.

Turning enhancements off preserves the individual choices. **Classic** instead
resets all enhancement settings, including upscaling. OpenGL
always uses the baseline settings. The ordinary video options (outlines,
shadows, render scale, etc.) are still independent and must match when comparing
backends.

## Automated checks

Clarity, Vignette, Tilt-shift and Ambient occlusion have been removed from the UI,
presets and render passes. Object relief (including metal sheen and relief cracks),
Bloom and the separate soft-shadow/local-light controls have also been removed.
Their old saved keys are ignored.

Realistic fire replaces unlit scrolling flame surfaces with a baked 3D combustion
cache (64 x 64 x 96 voxels, 64 frames, shared by every source). The original mesh
bounds supply placement and scale, while scene depth clips ray integration at logs,
fixtures and other opaque objects. Two adjacent 3D frames are interpolated; emission
and extinction are integrated front to back with premultiplied blending before tone
mapping. Small/distant sources and crowded scenes use 32 rather than 64 ray steps.
Playback averages 24 interpolated frames per second with smooth aperiodic tempo
variation (always forward). Burner lobes and turbulence use seeded random motion,
not sinusoidal modulation. The loop crossfades two moving 16-frame sequences,
never towards a single frozen frame. The source texture and material tint preserve blue and green fire.
Only known combustion resources are replaced; unrelated scrolling effects remain
untouched. Coincident legacy flame surfaces render as one volume.
Registration uses resource identity before mesh-local state is installed, so newly
loaded fires cannot disappear between suppressing the old mesh and drawing the volume.
Orange fire shades from red thin edges through amber to pale hot cores using both
temperature and density, rather than coloring most of the volume uniformly yellow.
The baked burner layer is aligned with the original fuel base; compact flames are
aligned with the fixture top below them, fixing the candelabrum's floating flame.
The compressed resource is generated by `tools/fire-volume/bake.py` using advection,
pressure projection, buoyancy, vorticity confinement and a decaying reaction field.
This is a baked simulation, with no runtime fluid solver or obstacle interaction.
It needs only ordinary sampled 3D textures and fragment shaders, with no NVIDIA SDK,
CUDA or RTX requirement. GPU residency for the shared RG8 cache is 48 MiB.
See NVIDIA's [Flow overview](https://nvidia-omniverse.github.io/PhysX/flow/index.html)
and [volume rendering discussion](https://developer.nvidia.com/gpugems/gpugems3/part-v-physics-simulation/chapter-30-real-time-simulation-and-rendering-3d-fluids)
for the simulation/rendering approach; this implementation contains no NVIDIA code
or assets. The source is original and the cache can be regenerated locally.

Sparks follow the upper flame volume (falling back to the light attachment before
the volume is available). Their size has a
4.5-pixel minimum for visibility, with separate torch/campfire emission profiles,
short cooling trajectories and weak wind drag. `ant test-fire-effects` checks
production volume shaders on Vulkan (rotation, animation, depth occlusion and the
lower-step approximation and colored fire), saves diagnostic images and checks particle trajectories.
GPU tests on the development machine do not constitute AMD/Intel driver testing.
Wet terrain uses explicit surface profiles: vegetation stays matte, soil darkens
with a restrained sheen, and paving has a broad, bounded highlight. The separate
**Relief only on bricks and paving** preference restricts both Ground relief and
its parallax to the paving material profile. GroundTile transitions and TerrainTile
base/variant materials retain their classification when settings change, so existing
terrain responds without reloading the map. Master relief and depth controls still
apply; disabling the restriction restores relief on natural terrain.
At full
wetness, vegetation darkens by 22%, soil by 32% and paving by 28% before adding
the sheen. Paving's reflected-light gain is 1.10 (twice the previous 0.55).
Paving highlights follow the existing stone relief rather than a
procedural patch mask. Screen-space normal variance fades unresolved detail back
to the geometric normal to suppress subpixel glitter, while resolved stone slopes
retain their highlights. Rain darkening uses the geometric normal; vegetation and
soil do not acquire relief glints. There is no procedural wetness noise. Unknown natural
terrain defaults to matte vegetation; dirt, plow, sand and rock resources use the
porous soil response. `ant test-wet-surface` checks the production shader on Vulkan
at seven viewing angles, including dry colors, alpha, minimum paving sheen,
response to resolved stone relief and rejection of pixel-scale alternating normals.
`WetSurfaceTest --cached-resources`
also verifies brick and stone paving base/variant profiles in actual cached resources.
Debug's Heavy rain option enables local rain at three times the ordinary preview
rate, updates the existing particle source in place, and restores ordinary rain
when unchecked. Disabling rain or restoring server weather also clears Heavy rain.
The former independent lightning screen-flash setting has been removed. World-space
bolts now include a short cool-white scene-lighting flash synchronized with the same
stroke envelope. It preserves scene alpha and visible detail, skips empty depth,
and runs after temporal accumulation; `LightningFlashTest` checks its GPU output.
The separate **Lightning bolts during heavy rain** option renders branching world-space
discharges, with an antialiased white core, plasma sheath, blue halo, a descending
leader and repeated strokes through the same channel. Each strike has 416–528
segments, tapered branches/sub-branches, randomized shape and stroke timing, plus a
small contact corona. It is off by default and independent of water effects.
It uses CPU fractal subdivision and camera-facing triangles following
[NVIDIA's Lightning SDK](https://developer.download.nvidia.com/SDK/10.5/direct3d/Source/Lightning/doc/lightning_doc.pdf),
with core Vulkan rendering and no CUDA, RTX or vendor-specific extensions.
Scene depth hides occluded channels; the pass runs after temporal accumulation and
tonemapping to avoid trails and exposure pumping. Shader/pipeline preparation uses
the asynchronous draw path and starts before the first strike. Rain intensity below
1.8 (ordinary preview = 1, heavy preview = 3) and black indoor lighting suppress
strikes. Impact candidates are uniform by area in an 18–40 tile annulus around the
current session's player, restricted to visible loaded terrain. `LightningPlacementTest`
checks radius boundaries and 30,000 samples. Debug Heavy rain previews them
every 2–3 s; server storms use randomized intervals of roughly 7–16 s.
The cloud endpoint is fitted beyond the upper screen edge using the current
camera/projection, instead of a fixed world height. Near top-down views use an
up-screen cloud offset while keeping the impact anchored in the world. Tests
cover both projection types, three zooms, four elevations and three azimuths,
plus a GPU check that the bright channel reaches the top edge.
`test-lightning` checks bounded geometry across 100 seeds and four camera angles,
settings isolation, rain gates, GPU depth occlusion, animation, expiry and scene
alpha. `LightningTest --preview` also exports frames for a short animation preview.
With Vulkan effects enabled, rain smoothly desaturates and dims the existing
outdoor ambient/direct light (including the optional daily palette). It preserves
time-of-day luminance, black indoor light, light direction and warm local lights;
it does not apply a gray fullscreen filter. Cloud cover follows current rain,
independently of ground wetness, with a slower clearing transition.
The separate **Rain ripples on water** option requires Transparent water and works
with Water reflections off. Small randomized impacts expand and fade over 1.6 s
in world space, sampling neighbouring cells to avoid cut circles or section seams.
Heavy rain increases their density. Normals distort refraction and highlights;
Sky-lit crests use a 0.42 gain (formerly 0.13) for clearer rings without changing
their radius, density, lifetime or normal displacement. Pixel-footprint
filtering removes unresolved circles at distance. Current rain has its own uniform,
so lingering wet ground cannot spawn drops, and toggling rings does not recompile
the water shader. `test-water-surface` includes day/night lighting checks and GPU
checks for dry/off isolation, rain density, motion, section seams and occlusion.
With Transparent water enabled, existing rain drop/splash sprites are suppressed
in the scene color used for refraction and replayed after the water resolve. They
test the real scene/water depth, preserve framebuffer alpha and use a pale
sky-scaled color with 1.65 times the original vertex alpha. Thus airborne drops
remain straight and readable above deep water, without shining through foreground
objects or from below the surface. Empty rain models do not force an extra water
resolve. Classic water keeps its original precipitation path. GPU checks cover
rain above/below water, foreground occlusion and scenes containing no water mesh.
Enclosed-map water disables ambient swell and wind ripples, while retaining object
wakes. Interior detection recognizes mine, cave, nil, deepcave and deeptangle tiles,
including loaded neighboring grids around paved areas. Interior lighting uses the
standard model independently of outdoor enhancements and Debug time. Both displaced
geometry and shading derivatives use zero sheltered strength, passed as uniforms
without changing shader variants. `WaterSurfaceTest` verifies no ambient underground
waves and the complete sheltered-water resolve on Vulkan; `InteriorLightingTest`
checks the lighting gate.
The optional gust-driven tree sway, ambient dust/fireflies/leaves, footsteps
and butterflies have also been removed, including their settings and presets.
The game's original vegetation animation, weather, smoke and fire embers remain.
The separate **Animated grass** option adds short segmented blades exclusively to
`gfx/tiles/grass`. It defaults off. The implementation follows Shrine's procedural
blade/root weighting, with deterministic elongated groups of varying size and
strength. World-space cells may contain zero or multiple group centers, placed
without an inset, so there is no one-group-per-cell lattice or empty tile-border grid. Heights range from 1.3 to 3.4 Haven units;
roots sample the actual terrain surface. Grass does not change movement or picking.
Grass covers the visible grassy terrain within the map's rendered cuts, without
a distance limit around the player. Quantity remains adjustable from 25% to 200%
(default 100%), persisted and applied when the slider is released.
One background worker selects patches against a snapshot of the camera frustum
using cached terrain height bounds, and builds geometry in 5x5-tile patches
aligned with the map cuts, without requesting neighboring cuts outside that area.
There is at most one pending visibility scan and four pending mesh builds. The
worker consumes these builds continuously, without a timer between patches. Visibility
is refreshed at 5 Hz, with a screen margin; draws are culled against the current
camera each frame. Cached meshes are retained outside the camera until they leave
the rendered map cuts, avoiding rebuilds when the camera turns back. Meshes are
invalidated when source map meshes change. Missing resources
retry without waiting on the frame thread. The Vulkan pass uses asynchronous
pipeline preparation, writes depth before water and temporal accumulation, and
uses no vendor-specific APIs or per-blade CPU animation. Quantity extends a deterministic
tuft sequence, preserving existing positions. Each tile has 8–64 tufts across
the quantity range; roots stay within their tile, without a bare border. Density changes keep
old meshes visible until replacements are ready; stale-density jobs are discarded.
Each patch is capped below 16-bit vertex indices; mesh bounds interpret those indices as unsigned. Wind and distance-sampled player contacts bend
only the upper blade; the strongest nearby contact wins, and the trail relaxes in
1.4 seconds. Teleports clear the trail. `test-grass` covers placement, slope/root
height, repeatability, bounded geometry, distance sampling, wind, contact, recovery
foreground occlusion, saved quantity values, clustered coverage without truncated
tiles, unsigned bounds through index 65535, camera culling
and visible terrain far from the player;
`GrassTest --preview` writes rendered animation frames.
Contact sampling carries residual path length across frames and interpolates birth
times, so equal trajectories at different frame rates produce the same contacts.
Completed background patches are checked against current map meshes before adoption;
only failed/missing-resource builds are delayed for retry, not valid terrain updates.
The former Water details (lake-bed caustics and rings around waders) implementation
and its setting have been removed, including the old source texture upload.

The transparent-water option replaces the additive sky/noise overlay with a
current-frame forward resolve (`WaterSurface`, order -210). The river bed and
submerged objects render without the legacy linear fog; Beer–Lambert transmission
and in-scattering are evaluated once, using the visible depth behind the surface.
Extinction includes sediment: river RGB coefficients are (0.17, 0.10, 0.075),
ocean (0.105, 0.060, 0.043) per world unit. Thin shoreline water remains clear,
while the actual depth-6 / depth-30 river beds become subdued / obscured.
Scattering uses muted blue-gray river water (0.050, 0.110, 0.190) and turquoise ocean water
(0.020, 0.220, 0.180), modulated by scene lighting without changing transmission.
The independent Water reflections checkbox skips sky/object reflections and sun
glints, keeping refraction, color, waves and foam. It applies on the next frame
and persists separately from the water switch. Older saved water configurations
retain their reflections; Classic disables both, while Enhanced/Ultra enable both.
Moving objects emit pairs of freely propagating wave packets every 1.5 travelled
world units, interpolating positions/timestamps and carrying leftover distance
across updates. The origin is the forward ray intersection with the object's
rotated bounding box, including asymmetric boxes and reverse movement. The two
fronts meet at emission and spread into a broad V. Gaussian ends
are constrained to their own side of the path and behind the moving apex,
preventing the two trailing crests from extending forward as an X. They
overlap smoothly through turns without joining offset polylines (which produced
folds and spikes). The packets continue moving after the source stops. Width
grows with age, while foam has a continuous base with shared world-space value
noise. The previous per-packet sine modulation punched repeating checker gaps
into the crest; all packets now sample one aperiodic pattern. Foam overlap is
normalized and smoothly compressed so brighter crests retain their variation.
`WaterWakes` combines analytical slope and foam disturbances into a half-resolution
floating-point field, following the local-disturbance approach in
[NVIDIA GPU Gems 2, section 18.2.5](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-18-using-vertex-texture-displacement).
This is a local visual wake, not a fluid solver or a vendor-specific extension.
Measured motion and object bounds control strength and size; packets expand and
fade over 4.8 seconds, retain their emission origins/directions through turns, and reject
teleports. Riders do not duplicate their carrier's wake. Up to 12 nearby moving
sources and 1536 packets share one draw. Only water fragments consume the field,
and opaque scene depth prevents wakes drawing over banks or objects. Foam remains
visible with reflections disabled. `test-water-surface` checks motion history,
budgets, render/simulation clock skew, wake visibility and foreground occlusion.
The GPU test also samples both accumulated crests for dark gaps and abrupt jumps.
Water samples immutable scene color and an R32F depth copy, then writes the real
scene depth for smoke, fire, heat and TAA. Refraction rejects foreground/silhouette
samples, uses the actual jittered camera and stays within five pixels. Bounded
20-step SSR uses current-frame above-water intersections and falls back to the sky
cube. Reflection uses water's dielectric F0 (~2%) and filtered GGX sun/moon glints.
Resolved small ripples now have stronger slopes and carry a tighter GGX lobe
(roughness 0.23 / 0.24), broadened by pixel normal variance. Direct glints retain
a hue-preserving soft shoulder below 0.338 (river) / 0.39 (ocean) per channel
before grading, including the 30% gain after compression. This permits bright
specular facets without clipping at HDR intensity 2. Environment/shore reflection weight also rolls off
smoothly toward a 0.16 / 0.25 material limit to preserve the water body's color.
Wave readability also has a bounded, view-independent scattering fill driven by
the existing world-space normals and light direction. This leaves the wave
geometry, spectrum and animation intact while avoiding flat blue water away
from the sun. Specular energy is concentrated on resolved tilted facets; a faint
broad sheen remains instead of saturating the entire patch near the mirror angle.
The former additive skylight sparkle on short-wave slopes has been removed: it
lit too many facets independently of the reflection direction, producing dense
worm-like streaks. Directional GGX is now supplemented by a bounded sky sheen
on the narrow positive tops of the existing travelling wave packets, including
only the two coarsest ripple bands. Their original phases and envelopes place
and animate the highlights; there is no independent noise/slope sparkle mask.
This artistic sky response stays visible around a full camera orbit, follows
skylight intensity, and fades with bed depth and sheltered-water strength.
Short-wave slopes smoothly settle over depth 0–12 world units (half strength at
the usual shallow river depth of 6), using interpolated bed depth shared across
map sections. The travelling geometry waves, open-water spectrum, rain rings and
wakes retain their existing behavior.
Normal variance fades unresolved sparkles. A 24-azimuth, three-elevation sweep
for river and ocean checks stable average brightness, readable relief at every
angle, bounded visible glint coverage, sparse bright highlights and retained local peaks. Shore tests check
quieter shallows, continuing ripple motion and unchanged geometry/open-water slopes. Bed transmission is
checked against a black-bed reference, separately from the added surface relief.
Facets fade across the light/view horizon. The GPU regression sweeps six elevations
for both river and ocean under a bright sun, checking highlight range and gradients.

Object foam using `gfx/fx/oarsplash` (including flintwash and flotsam) is deferred
until after the water resolve. Its transparent triangles never enter the opaque
color/depth snapshot, which previously left raw bed-colored holes in the water.
Foam blends without depth writes, tests the immutable opaque scene depth and
retains its original texture animation. Wave crests do not slice flat foam skirts.
Classic water retains the resource's original pass. GPU regression checks that
zero-alpha foam leaves water unchanged, visible foam blends, and solid foreground
objects still occlude it.

Land texture fringes over water use the original surface vertices, UVs and triangle
diagonal in both water modes. There is no second submerged bank mesh: projecting
the texture onto the bed caused stretched patches in shallow water. Only these
fringes carry `ShadowMap.maskshadow`, excluding them from directional and point-light
shadow casting while retaining lighting and received shadows. Ground overlays,
the actual terrain and picking retain their original geometry and shadow settings.
`WaterShoreTest` exercises straight/corner banks, checks original vertex identity,
the isolated shadow mask and a single mesh, and compares both modes to the original.
It is included in `test-water-surface`; previews are in `build/water-preview/shore-*`.

Four wave components displace a 4x4 subdivision of each tile vertically; their
shared section footprint stays fixed instead of drifting laterally. Five
log-spaced normal-wave bands (10.5 down to 2.25 world units) bridge large waves
and fine ripples, fading below pixel resolution. Independent smoothly varying
activity per band replaces the former thresholded calm/rough switch. Waves form
spatially varying wave packets instead of infinite straight crests: smooth envelopes
travel at group velocity, with varied packet sizes, two scales of phase bending and analytic derivatives
for both displacement and shading. River components cross at broader angles;
fine ripples use the same packet model rather than uniform sine stripes.
Leading river/ocean wavelengths are 21/44 units, with 0.16/0.55-unit amplitudes;
smaller components have independently varied lengths and directions. Broad,
slowly moving activity fields vary fine ripples above an 18% minimum, preserving
surface motion in quieter stretches without evenly covering the river in ripples.
The ocean keeps stronger swell and a nonzero fine-wave floor. Pattern preview
checks that both quiet and active areas remain present and broad-wave normals
remain visible; real river-depth captures bound bed contrast in every RGB channel.
Angle tests also check
the combined direct-sun and white-environment reflection contribution.
River currents use
the map slope/pressure field with two staggered seven-second advection phases;
oceans (`owater`, `odeep`, `odeeper`) use longer, larger waves and different
absorption/scattering. Shared vertex depth/profile values pin the shore and join
river/ocean tiles. The flow solver's halo covers both neighbour operations in each
iteration, preventing map-cut seams. This is a visual wave/current approximation,
not a server-side fluid/boat simulation. It uses core raster/texture operations,
with no CUDA, RTX, vendor extensions or external SDK dependency. Classic water is
retained when the option is disabled. Caustics and wader rings stay removed.

References: [GPU Gems water model](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models),
[refraction and masking](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-19-generic-refraction-simulation),
[GPUOpen reflection pipeline](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/stochastic-screen-space-reflections/).
The two-phase flow approach was also inspected in the local `newgame` project's
`WorldCleanWaterArtwork.hlsl`; its painted 2D water was not copied into the 3D scene.
`ant test-water-surface` renders the actual production geometry/pass on Vulkan,
checks depth-dependent bed contrast, foreground rejection, perspective/orthographic
jitter, animated flow/seam continuity and river/ocean separation, and builds adjacent
MapMesh chunks to compare boundary attributes. `WaterSurfaceTest --cached-resources`
also checks actual cached river tile factories. Preview images go to
`build/water-preview`. No AMD/iGPU hardware performance claim is implied by this test.

Heat shimmer uses the same flame geometry and fixture alignment, independently of
the realistic-fire toggle. It starts within the upper flame and fades shortly above
it, with width derived from the projected flame footprint in pixels. The old fixed
30-unit plume attached to warm point lights is removed. Current depth excludes
foreground objects. Refraction runs on the jittered raster grid before the final
TAA resolve; its strength scales with the footprint, capped below 2.5 pixels per
source, with subpixel motion for small candles.
`ant test-heat-shimmer` checks the actual GPU shader's footprint, distortion and
occlusion, plus candle/campfire scaling.
`python tools/fire-volume/check.py` checks motion through the baked loop seam.
The optional `FireEffectsTest --cached-resources` mode also verifies cold render-tree
registration, original orange/green/blue materials and candelabrum wick alignment
using the configured client resource cache.

TAA resolves after depth-dependent effects and sharpening, before resampling.
Color history and output use the stable display grid; current color and both depth
frames use their own jittered raster coordinates. Reprojection uses the actual
view/projection matrices and removes the preceding frame's jitter when sampling
color history. This avoids repeatedly shifting and filtering stationary detail.
History weight falls from 92% at rest to 20% during fast screen-space motion.
Neighborhood color bounds and a tighter moving neighborhood limit trails; static
subpixel coverage changes no longer trigger aggressive history rejection.
A single R32F depth-history texture rejects newly visible surfaces; history resets
after resizing or a frame gap over 250 ms. `ant test-temporal-aa` checks these shader
rules, depth copying, and stationary marker centroids over all eight Halton offsets
at native and doubled output resolution. A 96-frame fine-line scene also checks
temporal variation and preserved spatial contrast at both scales: the old pipeline
passed centroid checks while still producing 21.93/255 temporal RMS shimmer; the
stable history resolves that forced-jitter fixture at 3.84/255 at native resolution.
The live camera advances its sampling offset only when the unjittered camera
matrix changes, retaining a fixed offset at rest. This deliberately stops cycling
subpixel samples in a stationary scene. Camera and projection are snapshotted once
per view draw, after settings sync; UI ticks do not advance jitter. Disabling TAA
clears its correction immediately, and resizing starts at zero offset. The GPU
move/stop fixture now measures 0/255 temporal RMS at both output scales, with
spatial contrast retained. CPU checks cover immutable camera snapshots, two views,
toggle/reset behavior and resizing.
Reprojection still lacks per-object motion
vectors, so moving/animated objects use depth and color rejection heuristics.

`ant test-vulkan-picking` reproduces a dropped first click with cold asynchronous
selection shaders, then verifies first-pass ID readback, empty space, shader changes
and recreated picking lists. Picking uses `DrawList.async(false)`: shader compilation
must finish and pipeline creation must reach execution before readback. Regular world
draw lists retain asynchronous compilation. The first selection can wait for compilation
once; it no longer silently consumes clicks while warming up. Unloaded resources remain
subject to the engine's existing loading behavior.

**Color > Better lighting** is an opt-in palette for directional and ambient
light: warm horizon sunlight, neutral daytime light, blue moonlight and cooler
night ambient light. Strength blends from the original light (0) to the full
palette (1). The server's shadow direction is retained. Missing or black outdoor
lights are left alone, including underground. Debug's time slider previews the
cycle. The old time-of-day tint checkbox and saved `tod` flag have been removed;
there is no hidden tint stacked over the lighting option. Auto-exposure activates
the color pass independently, including its highlight shoulder even when color
grading is disabled. `ant test-color-lighting` checks the grading contract and GPU output.

Auto-exposure meters only rendered geometry using scene depth, so the black void
around caves and houses does not increase their brightness. Weighted luminance
and coverage survive every reduction level; dark geometry still contributes.
An RMS luminance limit protects lit surfaces when deep shadows dominate the
frame. Empty scenes use neutral exposure. `ant test-auto-exposure` checks actual
Vulkan readbacks with 50–94% void, lit interiors, night, shadows and missing depth.
Vulkan always uses the server's original ambient, diffuse and specular light,
including when auto-exposure or graphics enhancements are disabled. The saved
legacy Night vision boost applies only to OpenGL. Toggling Vulkan auto-exposure
therefore leaves the scene's base lighting unchanged. Indoors, exposure is bounded
to 1–2.2, even during adaptation after entering from outdoors. The applied gain
falls smoothly towards one for already bright pixels, using their peak RGB
channel so a saturated lamp is protected too. This lifts darkness while avoiding
full-scene amplification of small bright areas. A soft shoulder above 0.8 retains
highlight gradations; it can compress bright pixels but does not dim the shadows.
The GPU test checks a monotonic dark-to-HDR ramp, dark-region lift, preserved
highlight differences and the neutral disabled path. Outdoor exposure retains
its 0.75 lower bound.

Point-light shadow comparisons correct receiver depth at every PCF texel,
including the four bilinear taps. Previously a single center depth was compared
with all nearby samples; at grazing angles a basement floor shadowed itself in
bands and blocks. The correction uses the receiver-plane gradient within each
cube face, falling back to the existing bias across a face seam. The shadow GPU
test reproduces the old floor artifact and checks that the fixed flat floor stays
lit while a genuine blocker still casts a shadow.

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
Night uses a brighter blue ambient palette and a minimum directional-light peak of
0.60, fading out through dawn/dusk, so a faint server moon does not force the enhanced
scene back into darkness. Black outdoor lights are still left alone indoors/underground.
The optional fair-weather cloud shadows have been removed from settings, presets
and rendering. Old `clouds` keys are ignored; server weather remains unchanged.
Settled snow is temporarily gated off because its visual cover conflicts with game
mechanics. Its checkbox is disabled with an explanation; saved settings and presets
cannot enable it. The implementation remains behind `NGfx.SNOW_SETTLING_AVAILABLE`.

Up to two nearby local lights cast shadows automatically in Better shadows, using
six 512-square atlas faces, filtered comparisons and the actual light position. Atlas edges
remain face-clamped; seamless cube filtering and baked texture shading are outside
this implementation.

The option overrides legacy shadow resolution and can enable shadows even
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

Presentation pacing waits for N-1 after submitting N, leaving one frame of
headroom instead of draining the display queue after every submission. Present
IDs restart on swapchain recreation. The pacing test also injects isolated render
preparation spikes; its client timing does not establish physical scanout cadence.
The frame limiter rechecks focus and FPS settings at most every 10 ms while
sleeping, so returning from the 5 FPS background mode need not wait out 200 ms.

## Cut streaming and exploration work

Terrain meshes already build on Defer workers. Vulkan used to prepare all newly
attached draw slots synchronously, even when an entire row of cuts arrived at
once. Regular asynchronous draw lists now queue both additions and replacements.
Each draw drains at most 300 attempts and stops starting new work after 2 ms.
This is a soft budget per draw list: a single allocation/preparation can exceed
it, and a frame may contain multiple lists. New parts may appear over several
frames; existing drawing remains until its replacement is ready. Uniform-only
updates remain immediate. Synchronous picking keeps its complete one-shot path.
`ant test-vulkan-cut-streaming` exercises expensive warm preparation, blocked cold
shader workers, queued removal, latest state and synchronous lists. The test's
synthetic preparation delays are not an in-game FPS benchmark.

Exploration persistence (merge, RLE/JSON, locking and disk writes) uses a daemon
worker; profile paths are captured before dispatch. Mask arrays are immutable
per-grid snapshots, so visiting one grid no longer invalidates every visible
overlay. Overlay rasters use a bounded worker queue and a per-widget LRU cache;
each map publishes at most two finished textures per draw. Workers never access
render-tree/UI state. File-lock failures retain dirty state for retry instead of
overwriting without the merge. `ant test-exploration-performance` checks snapshot
identity, texture reuse, upload/cache bounds, asynchronous persistence and session
write/delete ordering. Diagnostics distinguish `minimap-map` and
`minimap-exploration`; metadata includes VSync and the background FPS limit.

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

The Vulkan upload ring retains a recently used working set up to 64 MiB per
submission slot (three slots, up to 192 MiB retained). Active submissions may
temporarily exceed that cache budget. Reuse and eviction happen only after that
slot's fence has retired. Empty blocks are searched by size before allocating;
new blocks are rounded to 4 MiB so small size changes do not repeatedly allocate.
Unused blocks expire after 120 slot resets, leaving at most a 16 MiB idle reserve
per slot. Ring teardown still frees everything. Presentation pacing is unchanged.
`ant test-vulkan-ring` verifies reuse under changing request order, alignment,
non-overlap, cache bounds and expiry, then uploads and reads back 18 pairs of
8/6 MiB textures across real GPU submissions with no ring allocations after warmup.

Water surface and object-foam resolve draws opt into `States.asynccompile`.
On Vulkan, the immediate draw path uses the same background program and pipeline
builders as draw lists. An unready draw records no command and is retried next
frame; uniforms and geometry are not committed to a partially prepared command.
The new effect can appear a few frames late on its first encounter, while the
rest of the frame continues. OpenGL and unmarked immediate draws are unchanged.
Pipeline build failures are reported instead of leaving a permanently missing
effect. `ant test-vulkan-async-draw` blocks the compiler workers to check both
preparation stages, state recovery, error propagation and immediate warm reuse.
Water/shore GPU image tests retry incomplete preparation before comparing pixels.

## Compatibility fixes covered

Better lighting without automatic exposure now lifts ambient and direct light in
dry weather. Rain retains the existing darker palette; the transition is smoothed,
and interior lighting and specular light are unchanged. `DryWeatherLightingTest`
checks the daily cycle, rain and exposure gates, and transition behavior.

Vulkan pipeline-cache extraction and disk writes run on a dedicated background
worker. The render loop only requests a save when the cache revision changes, with
at most one pending job. Snapshots use native buffers without a second large heap
copy, and atomic replacement preserves the previous file on write failure. Shutdown
waits for builders and the final save before destroying the native cache.
`PipelineCacheWriterTest` covers blocked saves, concurrent revisions and failures;
`VulkanPipelineCacheTest` verifies continued rendering during a real cache save.

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

The October 5 stationary-scene capture showed five GC pauses of 17–19 ms beside
its five frames over 20 ms. A live JFR allocation sample identified recurring
FPS HUD rasters and Vulkan draw recording among the allocation sources. The HUD
now reuses its worker-owned ARGB image (clearing alpha before every raster), while
keeping immutable RGBA upload snapshots. Vulkan records draw-list uniform blocks
with absolute buffer copies instead of two duplicate wrappers per draw, and
reuses its state-change index scratch array. GPU readback checks cover HUD refresh,
alpha/resize, uniform snapshot isolation, async retries, and GL/Vulkan parity.
These changes reduce allocation; they do not establish that all gameplay GC pauses
are eliminated.

The follow-up allocation pass also retains immutable light-grid topology while
projection, light positions/ranges/order/count and the per-cell limit are unchanged.
Color and directional-light direction updates still produce current light data;
they do not require another 64x64x64 CPU grid. A changed topology gets new arrays,
so delayed texture uploads cannot observe a later frame mutating their source.
`test-light-grid-cache` compares Vulkan texture readback against uncached compilation
for every invalidation case and checks allocation in a stationary scene (440 bytes
per compile in the fixture, without another 512 KiB grid). Vulkan draw lists also
retain equivalent pipeline keys across attachment swaps and immutable sampler
snapshots when their bindings are unchanged.

The 20:08 October 5 capture identified two further cold paths on the UI thread:
icon-archive inflation from craft-menu scanning (176 ms UI tick), and a Vulkan
water-wake shader-cache read from attachment clear (311 ms draw). Icon indexing
now publishes a completed background catalog, independently retaining native
registrations and lower-priority menu fallbacks. `test-item-icons-async` blocks
all loader workers to verify callers return placeholders and registrations survive.
Vulkan clears now resolve only the requested color/depth attachment and scissor,
without evaluating material shaders or changing cached draw state. Water wakes
request async draw preparation; until ready their field clears to neutral zero.
Compiler-gate, GL/Vulkan parity, full water GPU and atlas rendering tests cover
these changes. The later 20:09 capture has a separate 42.9 ms frame with a toolbar
render sample; that sample alone does not establish its precise cause.
