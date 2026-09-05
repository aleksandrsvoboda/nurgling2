# Forager distance-limit visualization — implementation plan

Status: planning only, nothing implemented. Written 2026-09-05 against branch `forager-settings-refactor`.

## 1. What exists today

Two per-route numeric limits, set in Forager Settings > Routes and stored on `ForagerPath`
(`src/nurgling/routes/ForagerPath.java:40-48`):

- `maxDistance` (tiles, -1 = unlimited) — the leash. Enforced by
  `ForagerRouteConstraints.withinLeash(anchor, candidate)`
  (`src/nurgling/actions/bots/forager/ForagerRouteConstraints.java:70-73`): straight-line distance
  from a fixed **anchor** point, not from the route itself. The anchor is set once per detour
  episode to wherever the player was when the episode started
  (`Forager.java:500` `leashAnchor = player.rc`, and again at `Forager.java:561` for the
  non-episode `walkInHops` path) and held fixed for that whole episode.
- `maxBranches` / `maxBranchDistance` (count / tiles, -1 = unlimited each) — a shrinking
  per-episode budget tracked by `DetourBranchBudget`
  (`src/nurgling/actions/bots/forager/DetourBranchBudget.java`). Every hop taken while chasing a
  gob off-route calls `budget.spend(hopDistance)` (`Forager.java:544,577,592`); `canBranch()`
  gates whether another hop is allowed. The object is created fresh per episode
  (`Forager.java:501-502`) and is plain, unsynchronized state private to the bot thread today —
  there is currently no getter for the remaining amount, only the boolean `canBranch()`.

Neither limit is rendered anywhere. `ForagerRouteMap.java` (the Routes settings-panel map) draws
waypoints, legs, and exclusion-brush tiles, but never `maxDistance`/`maxBranchDistance`
(confirmed: no match for those field names in that file).

### The existing live-overlay pattern (this is what all three pieces below must reuse)

`NGameUI` already exposes bot-thread state to the render thread as plain, unsynchronized public
fields, written by the bot thread and read by the UI/render thread (`NGameUI.java:65-75`):

```java
public nurgling.routes.ForagerPath activeBotPath = null;
public int activeBotWaypointIndex = -1;
public java.util.Set<Integer> activeBotFailedWaypoints = null;
public java.util.List<haven.Coord2d> activeBotDetourTrail = null;   // "mutated live by the bot thread"
public haven.Coord2d activeBotDetourTarget = null;
```

No `volatile`, no locking. The comment on `activeBotDetourTrail` says as much: it is mutated live
by the bot thread and read as-is. This is tolerated because it's visualization-only — a
render-thread read of a half-updated field costs at most one stale frame, never a correctness bug,
since the bot thread never reads these fields back. Readers that iterate a live-mutated collection
take a defensive copy first, e.g. `NMiniMap.drawBotDetourTrail`
(`NMiniMap.java:692`: `new ArrayList<>(gui.activeBotDetourTrail)`) — copy-before-iterate, not
synchronization, is the actual safety mechanism.

Two independent consumers read these fields today, and both are relevant since the task wants
both a minimap and a 3D-world rendering:

- **3D world**: `NWaypointOverlay` (`src/nurgling/overlays/NWaypointOverlay.java`), a
  `RenderTree.Node` subclass of `NGroundPathOverlay`. `resolve()` (line 196) branches on
  `gui.activeRouteEditor` → `gui.activeBotPath` → recording path → click-queue, in that priority
  order, and appends `resolveDetourNodes(gui)` (line 174) for the live detour trail/target when a
  bot is running. `NGroundPathOverlay` supplies the reusable ground-hugging primitives:
  `ribbon()` (terrain-following strip between two points), `ring()` (world-space disc with edge +
  fill, `NGroundPathOverlay.java:257-279`), and in the 2D pass `circle()` (a world-space circle
  drawn as a projected polyline, `NGroundPathOverlay.java:296-307`) plus `plate()` (numbered label
  plate, `NWaypointOverlay.java:492-502`).
- **Minimap**: `NMiniMap.java` does its own independent flat-2D draw, not sharing geometry with
  `NWaypointOverlay` — `drawBotDetourTrail` (line 687) draws the live trail as dots+lines in
  yellow; `drawForagerRecordingPath` (line 725) draws the recorded route with dashed crawling legs
  and numbered pulsing nodes via `getWaypointLabel(num)` (same numbered-label texture cache
  `NWaypointOverlay` uses for plates, `NMiniMap.java:75`).

A third pattern worth naming: **radius overlays**. `NAreaRad`
(`src/nurgling/overlays/NAreaRad.java`) is a `Sprite`-based world circle (filled disc + outline)
attached to a `Gob` owner, used for things like `NBeehiveRadius`. It's gob-relative and
tick-driven, which fits "a shape centered on the player" reasonably well, but it's a *circle*, and
it's a `Sprite`/`Owner` attached to a specific `Gob` — the player's own `Gob` is a valid owner, so
this is usable, but see the design discussion in §3 for why a hand-drawn square via the
`NGroundPathOverlay` primitives is probably simpler than adapting `NAreaRad` for a square.

## 2. Piece 1 — max-distance boundary around the route

### What it should represent, and an important honesty caveat

`maxDistance` is not actually a distance-from-route constraint — it's a distance-from-anchor
constraint, and the anchor is a single point re-chosen per detour episode (§1). A geometrically
exact live constraint boundary would be a circle around *that episode's anchor*, which moves
around and mostly doesn't exist between episodes.

The user's request is explicitly for a boundary around the *recorded route* — i.e. "everywhere an
anchor could ever land, buffered by maxDistance" — which is a reasonable and useful
*approximation/upper-bound* visualization (anchors are always taken from points at or near the
route, since the bot is normally on-route when a detour episode starts), but it is not the same
shape as "where the bot could get flagged right now." **The plan should render it as exactly that:
an upper-bound reference boundary, not a live constraint line**, and this distinction is worth a
one-line clarifying label/tooltip in the UI ("approximate — actual limit is measured from where
each detour started, not from the nearest route point") so the user doesn't mistake it for an
exact boundary while debugging.

### Geometry: buffering a polyline by a fixed radius

The route is a polyline (ordered `ForagerWaypoint`s, filtered to the current segment/`sessloc`,
same filtering `resolveForagerPath` already does at `NWaypointOverlay.java:150-162`). "Every point
within `maxDistance` tiles of the route" is the Minkowski sum of the polyline with a disc of that
radius — a stadium/capsule shape per segment, unioned. Rigorous polygon union (true offset
polygon with mitered/rounded joins, self-intersection removal for tight zig-zag routes) is a real
computational-geometry undertaking and is overkill for a HUD reference line.

Recommended approach — **capsule-per-segment, drawn independently, no boolean union**:

For each route segment `(a, b)`, draw two things at radius `r = maxDistance * tilesz`:
1. Two parallel offset lines, one on each side of the segment, at perpendicular distance `r`
   (same perpendicular-vector technique `NGroundPathOverlay.ribbon()` already uses:
   `dir = (b-a).norm(); perp = (-dir.y, dir.x)`, `NGroundPathOverlay.java:229-230`).
2. A semicircle arc cap at each waypoint, reusing `NGroundPathOverlay.circle()`'s
   angle-stepped-polyline approach (`NGroundPathOverlay.java:296-307`) restricted to the half-plane
   facing away from the segment (or simplest: just draw the *full* circle at every waypoint,
   accepting some redundant line segments at concave joins — see below).

This does not attempt to remove the overlapping seams where consecutive segments' offset lines
cross each other on the inside of a turn — visually this reads fine for a boundary line (the
overdraw is invisible; a boundary line drawn twice looks like a boundary line), it just isn't a
clean simple polygon. This is the "good enough for a game HUD, not a CS undertaking" call the
task asked to make explicitly: **draw full-circle rings at every waypoint plus straight offset
lines per segment, all at radius `r`, with no polygon boolean algebra**. On a route with waypoints
spaced further apart than `2r`, this looks like a clean stadium outline; on a tightly-zigzagging
route with waypoint spacing much smaller than `r`, it degrades to something closer to a fat blob
outline (still a correct upper bound, just visually rounder than a tight offset polygon would be)
— call this out as a known visual limitation, not a bug, if it comes up during manual testing.

### Where it renders

- **3D world**: new overlay class, e.g. `src/nurgling/overlays/NRouteLeashOverlay.java`, modeled
  directly on `NWaypointOverlay`/`NGroundPathOverlay`: a `RenderTree.Node` that rebuilds its `Buf`
  only when the route or `maxDistance` changes (signature-based rebuild, same as
  `NWaypointOverlay.signature()`/`update()`, `NWaypointOverlay.java:264-285`), not every frame.
  Render as unfilled outline (edge color, alpha fill near-zero or omitted) rather than the filled
  disc `ring()` gives waypoint markers, so add a `ring()` overload or a new `ringOutline()` helper
  in `NGroundPathOverlay` that skips the fill triangles — this is the one **new primitive** needed
  in the shared base class (extend, don't modify `ring()` itself).
- **Minimap**: a new `NMiniMap` method e.g. `drawRouteLeashBoundary(GOut g)`, following
  `drawForagerRecordingPath`'s existing per-waypoint iteration and coordinate conversion
  (`NMiniMap.java:761-781`), drawing the same full-circle-per-waypoint + offset-line construction
  in flat screen space (trivial there — screen-space circles need no terrain sampling at all,
  unlike the 3D version).
- Both consumers read `maxDistance` from the same source `NWaypointOverlay.resolve()` already
  uses for the route itself: `gui.activeRouteEditor.getRoute()` (editing) or `gui.activeBotPath`
  (bot running). No new state-sharing needed for this piece — the route object already carries
  `maxDistance` as a field.

### Visibility / toggle

Follow the existing `NConfig.Key.showBotPathOnGround` / `showBotPathOnMinimap` /
`showWaypointsInWorld` pattern (referenced at `NWaypointOverlay.java:226,240`,
`NMiniMap.java:743`) — add a new `NConfig.Key` (e.g. `showRouteLeashBoundary`) rather than always
rendering it, since it's a lot of extra line geometry on a long route and the user may only want
it while actively debugging the leash. Default off or on is a UX call, not an architectural one.

### Performance callout

A route can be hundreds of waypoints (`NWaypointOverlay`'s edge-arrow code already has to cap
itself at `MAX_EDGE_ARROWS = 3` for exactly this reason, `NWaypointOverlay.java:32-34`). A full
circle-ring per waypoint at `RING_SEG`-style resolution (24 segments,
`NGroundPathOverlay.RING_SEG = 24`) is `waypoints * 24` line segments just for the caps, plus two
offset lines per segment sampled at `SAMPLE = 11.0` world-unit spacing like `ribbon()` does
(`NGroundPathOverlay.java:30`) for terrain-following — on a 300-waypoint route with long
segments, that's real geometry. Mitigate the same way the rest of the file already does:
rebuild-on-signature-change (not per frame — the route rarely changes while a bot runs), and
consider a lower segment count for the boundary rings than the 24 used for waypoint markers, since
this is a large ambient shape, not a precise clickable target.

## 3. Piece 2 — shrinking square around the player for remaining branch budget

### Data plumbing (the thread-safety question)

`DetourBranchBudget` currently only exposes `canBranch()` (boolean) — no getter for the actual
remaining count/distance. Needs two new accessor methods (pure additions, no existing method
touched):

```java
public double remainingDistanceWorldUnits(); // returns Double.POSITIVE_INFINITY if !distanceCapped
public int remainingBranches();               // returns Integer.MAX_VALUE if !branchesCapped
```

Then follow the exact existing pattern from §1: add a plain (non-`volatile`) field to `NGameUI`,
next to `activeBotDetourTrail`/`activeBotDetourTarget`, e.g.:

```java
// Remaining branch-distance budget for the in-progress detour episode, world units;
// Double.POSITIVE_INFINITY = uncapped, null-episode state signaled by activeBotDetourTrail == null.
public volatile Double activeBotBranchBudgetRemaining = null;
```

One deliberate deviation from the exact existing pattern: mark this one `volatile`. The existing
fields (`activeBotDetourTrail` et al.) get away without `volatile` because they're written once
per episode-phase-transition and read defensively (copy-before-iterate); this field would instead
be written on **every single spend() call**, i.e. potentially every hop, and read every render
frame purely as a scalar for a shrinking-size animation. A stale read here just means the square's
size lags a hop behind — cosmetically harmless — but `volatile` on a single `Double` reference is
free (no lock, no copy) and removes any doubt about torn/reordered reads for a plain field checked
every frame, so there's no reason not to take the small guarantee. This is worth flagging in the
PR description as an intentional one-field divergence, not silently copying old field style. If the
existing collaborator coding-standards note about render-thread field access comes up in review,
point at this reasoning.

Write it in `Forager.java` at every point that currently calls `budget.spend(...)` or creates a
fresh `budget` (`Forager.java:502` on creation, `544`, `577`, `592` after each spend), and reset it
to `null` in the same `finally` block that already clears `activeBotDetourTrail`/`activeBotDetourTarget`
(`Forager.java:511-512,515-516`). This is 5 call sites, all in code already being touched by
detour logic — no new control flow.

### Rendering: a literal square, centered on the player

This does not need `NAreaRad` (circle, `Sprite`/`Gob`-owner machinery) — a square outline
centered on the current player position is simpler to build directly with
`NGroundPathOverlay`'s primitives than to adapt a `Sprite`. Concretely: a new small overlay (could
live in the same new `NRouteLeashOverlay` file as piece 1, or its own
`NBranchBudgetOverlay.java` — one file makes sense if both pieces share update-throttling
boilerplate, but they have unrelated lifecycles: piece 1 only changes when the route changes,
piece 2 changes every hop while an episode is active — so **separate classes** is the cleaner
split) that:

- Every frame (or on a short throttle, e.g. matching `NWaypointOverlay`'s
  `now - lastBuild > 0.2` throttle, `NWaypointOverlay.java:312`), reads
  `gui.activeBotBranchBudgetRemaining`; if `null`, draws nothing (no active episode).
  If non-null and finite, computes a half-width `s = remaining / 2` (world units, since
  `remainingDistanceWorldUnits()` already returns world units, matching `tilesz` scale) and draws
  four line segments forming a square centered on `mv.player().rc`, ground-sampled the same way
  `ribbon()` samples a line (reuse that helper directly — a square is 4 calls to `ribbon()`-style
  edge drawing, or literally 4 calls into a small helper that does what `ribbon` does per edge).
  If `remaining` is infinite (uncapped route), draw nothing — there's no meaningful budget to
  visualize.
- On the minimap: same square, computed in flat screen space directly around the player's
  minimap dot — trivial, no terrain sampling needed there.

### Design question: is a square the right shape for a *distance* budget?

Worth flagging back to the user before or during implementation, not deciding unilaterally: a
literal square (`side = remaining`) reads naturally as "you can go this far in *any single
straight-line hop*, in any direction" if drawn as a square *centered* on the player with a
half-width, but the actual budget (`distanceRemainingWorldUnits`) is a **cumulative path-length**
budget across potentially many hops, not a single-hop straight-line radius — a player could spend
the whole budget in one long hop, or in ten short zig-zag hops covering far less net displacement.
A shrinking circle (radius = remaining ÷ 2, or radius = remaining if read as "how far you could
still get in one more straight hop right now") might communicate that more honestly than a square,
which visually implies "safe in all 4 diagonal-ish directions equally" in a way a distance budget
isn't. The task explicitly asked for a square, so this plan builds a square, but flag this
ambiguity rather than silently picking an interpretation — confirm with the user which reading
(remaining ÷ 2 half-width vs. remaining full half-width vs. a circle instead) matches their mental
model before finalizing the exact size formula.

## 4. Piece 3 — sequence numbers on branch/detour waypoints

This is the smallest piece and has almost all its infrastructure in place already.

`Forager.java`'s `breadcrumbs` (an `ArrayList<Coord2d>`, e.g. `Forager.java:497`) *is* the ordered
list of hops taken in the current detour episode, and is the exact same object referenced by
`gui.activeBotDetourTrail` (`Forager.java:498`: `gui.activeBotDetourTrail = breadcrumbs;`) — so the
index of a breadcrumb in that list is already, implicitly, "which hop number this was." No new
bot-side state is needed; this is purely a rendering change in the two existing consumers.

### 3D world (`NWaypointOverlay`)

`resolveDetourNodes()` (`NWaypointOverlay.java:174-193`) currently builds every trail `WNode` with
`num = 0` (`new WNode(id--, 0, trail.get(i), Kind.DETOUR)`, line 188). Change to
`new WNode(id--, i + 1, trail.get(i), Kind.DETOUR)` so the node carries its real sequence number.

Then in `draw2d()` (`NWaypointOverlay.java:439-442`), `Kind.DETOUR` nodes currently skip the
numbered plate entirely:

```java
if(n.kind == Kind.DETOUR) {
    // The 3D ring (already drawn in update()) is enough for an ephemeral breadcrumb.
    continue;
}
```

This comment's premise (a ring alone is enough for an ephemeral breadcrumb) is exactly what the
task is asking to revisit — add the stem + `plate(g, top, n.num, col)` call here too, mirroring
what `ROUTE` nodes already do a few lines below (lines 456-465), using `detourColor()` instead of
`nodeColor()` for the plate's border/text color so it stays visually distinct from route numbers.
`Kind.DETOUR_TARGET` should probably stay unnumbered (it's explicitly "the thing being chased
right now," not a completed hop — the comment at line 445 already says as much), so only extend
the `DETOUR` branch, not `DETOUR_TARGET`.

### Minimap (`NMiniMap.drawBotDetourTrail`)

Currently draws each trail point as a plain filled dot (`NMiniMap.java:713`:
`g.fellipse(c, new Coord(UI.scale(4), UI.scale(4)))`), no label. Add a numbered label the same
way `drawForagerRecordingPath` already does for route nodes (`NMiniMap.java:816`:
`g.aimage(getWaypointLabel(num).tex(), c, 0.5, 0.5)`) — reuse the same `getWaypointLabel(int)`
texture cache (`NMiniMap.java:75`), fed with `i + 1` for each point in the (already
defensively-copied) trail snapshot.

### Visual-clutter callout

A long detour episode (`maxBranches` unlimited or large) could rack up dozens of breadcrumb
points, each now carrying a numbered plate — on a dense detour (bot zig-zagging through a thick
patch of gobs) this could get visually noisy in a way the route's own edge-arrow cap
(`MAX_EDGE_ARROWS = 3`) was specifically built to avoid for the main route. Recommend capping
numbered labels to, say, the most recent N breadcrumbs (draw all the dots/rings as today, but only
label the last N with a number) if this proves cluttered in practice — flag this as a
"watch for it during manual testing, add a cap if needed" item rather than pre-building a cap
whose right value can't be judged without seeing it on screen.

## 5. Staged implementation order

Staged for incremental, independently-buildable, bisectable commits, similar in spirit to the
existing detour-trail work (breadcrumbs → budget → gate logic landed as separable, individually
testable steps per the repo's commit history for `DetourBranchBudget`/`ForagerRouteConstraints`):

1. **Piece 3 first (sequence numbers)** — smallest, no new state, no new files, touches only two
   existing methods (`NWaypointOverlay.resolveDetourNodes`/`draw2d`,
   `NMiniMap.drawBotDetourTrail`). Ships independent value immediately and is the easiest to
   verify by eye once in-game. Do this first to get a quick win and to sanity-check the
   `getWaypointLabel`/`plate()` reuse path before the harder pieces.
2. **Piece 2's data plumbing** (`DetourBranchBudget` getters, `NGameUI` field, `Forager.java`
   write sites) as its own commit, *before* any rendering code — this is pure bot-thread-side
   state exposure with no visual effect yet, so it can be verified by compilation plus a temporary
   chat-message printout (e.g. temporarily log `activeBotBranchBudgetRemaining` on spend, the way
   `Forager.java:487-488` already does for Maintain stock) rather than needing the renderer built
   first. Remove the temporary logging once piece 2's renderer (next step) is confirmed working.
3. **Piece 2's renderer** (the shrinking square, world + minimap) — depends on step 2's field
   existing. Build world first, minimap second (they're independent draws; doing world first
   means checking the harder terrain-sampled version before the trivial flat one).
4. **Piece 1** (leash boundary) last — it's the most geometrically involved and touches a new
   shared primitive (`ringOutline()`/no-fill `ring()` variant in `NGroundPathOverlay`) that piece 2
   doesn't need, so there's no reason to block piece 2 on it. Build the world overlay first
   (reuses more existing helpers directly), minimap second.

Within piece 1 itself, a further sub-stage is worth calling out: build and verify the *straight
offset lines* per segment before adding the *per-waypoint circle caps* — the straight-line part is
a direct, low-risk reuse of `ribbon()`'s perpendicular-offset math, whereas the circular caps are
where the "does this look like a clean boundary or a blob" visual judgment call actually lives, and
that judgment can only be made by looking at it in-game (see below).

## 6. What requires manual in-game verification (not confirmable by compilation alone)

Everything in this plan compiles-and-links independently of whether it looks right, so explicitly
flagging what needs eyes-in-game before considering each piece actually done:

- **Piece 1**: whether the capsule/circle-per-waypoint construction actually reads as "a
  boundary" rather than "a blob" on a real recorded route with realistic waypoint spacing —
  this is a purely visual judgment this plan cannot make from code alone. Also whether the
  chosen ring segment count keeps frame cost acceptable on the longest real route the user has
  recorded (no route-length numbers were available while planning; check against whatever the
  user's longest actual route is).
- **Piece 2**: (a) whether `volatile Double` reads on the render thread actually track the bot
  thread's spends smoothly (no visible stutter/lag) during a real multi-hop detour episode; (b)
  the square-vs-circle / half-width-vs-full-width sizing question raised in §3 needs the user's
  eyes on an actual shrinking shape before the formula is finalized — this plan deliberately does
  not pick one alone.
- **Piece 3**: whether numbered plates on every breadcrumb clutter the view during a real dense
  detour episode, and whether a cap on labeled points (vs. all points) is actually needed — cannot
  be judged from the code, only from watching a bot run through a gob-dense area.
- **All three**: minimap vs. 3D-world visual consistency (same colors/line styles reading as "the
  same feature" in both views) is a subjective check that only looking at both side by side can
  confirm.
