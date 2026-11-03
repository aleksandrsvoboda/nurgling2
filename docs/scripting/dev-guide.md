# Scripting API: developer guide (adding and changing ops)

This is for people working on the **client side** of the scripting API. Script authors want the
site under `docs/scripting/docs/` instead.

## Architecture in one paragraph

`nurgling.scripting.ScriptServer` listens on `127.0.0.1` (raw TCP, 4-byte length + UTF-8 JSON
frames). Each connection (`ScriptConnection`) owns one or more **runs** (`ScriptRun`). A run is a
real bot thread started through `BotExecutor`, bound to one session with `ThreadLocalUI`, with a
row in that session's Bot Status strip. Requests are queued to their run and executed **in order on
that bot thread**, so op handlers can call any existing Java action, `NUtils`, `Finder`, `NContext`
exactly like a Java bot does. The stop button interrupts the thread; the interrupt ends the run.

Client-launched scripts (the bot menu's Scripts folder, scenario steps) go through
`ScriptLaunchAction`, which spawns Python and then serves the run on its own bot thread.

## Writing an op

Ops are `public static Object name(OpContext c, Args a) [throws InterruptedException]` methods with
an `@ApiOp` annotation, in one of the classes listed in `OpRegistry.OP_CLASSES`
(`src/nurgling/scripting/ops/*Ops.java`). The annotation is the documentation: the schema,
the Python bindings and the reference pages are generated from it (`ant apigen`).

```java
@ApiOp(name = "gobs.find", kind = QUERY,
        doc = "Finds game objects ... (Markdown; first sentence is the summary)",
        params = {
                @ApiParam(name = "name", type = "Alias", doc = "...", optional = true),
                @ApiParam(name = "radius", type = "float", doc = "...", optional = true, def = "100")},
        returns = "list[Gob]", returnsDoc = "Nearest first.",
        errors = {Errors.NO_SUCH_AREA},
        example = "trees = nc.gobs.find(name=\"trees/\", radius=200)")
public static Object find(OpContext c, Args a) { ... }
```

Rules:

- **Name** `namespace.verb`, snake_case verb. The namespace is the class's (`gobs.*` in `GobOps`).
  Never rename or remove a released op: add a new one and set `deprecated = "use x.y"`.
- **kind**: `QUERY` (read-only; retried automatically while resources load), `ACTION` (changes the
  game), `WAIT` (blocks until a condition), `CONTROL` (plumbing).
- **exec**: leave the default `BOT` for anything touching game state. `CONNECTION` ops run on the
  socket reader thread and must not touch the game, block, or call NInventory/NCore methods.
- **Types** in `type`/`returns`: `int float bool str any dict null Gob Item Inventory Widget Window
  Area Coord Tile Rect Alias list[T]`, unions with `|` (`"Gob|null"`). The SDK wraps `Gob`, `Item`,
  `Inventory`, `Widget`/`Window`, `Area` results in its snapshot classes — return snapshots from
  `Codec` (`Codec.gob(g)`, `Codec.item(w)`, `Codec.inventory(inv)`, `Codec.widget(w)`,
  `Codec.area(a)`), never your own shape for those types.
- **Return** any of: null, primitives, String, `JSONObject`/`JSONArray`, `Coord`/`Coord2d`
  (become `[x, y]`), `Gob`/`WItem`/`GItem`/`NArea` (become snapshots), `List`/`Map` of those.
  `Codec.toJson` does the conversion.
- **Arguments** through `Args`: `a.str/i/l/d/b(name[, default])`, `a.strs` (str or list),
  `a.alias` (str, list or {include, exclude} → `NAlias`), `a.coord2d` ([x, y] or a gob),
  `a.coord` (int pair), `a.rect`, `a.gob` (throws GobGone), `a.item` (WItem, throws ItemGone),
  `a.gitem`, `a.widget`, `a.window` (caption or handle), `a.inventory` ("main", caption, handle;
  absent = main inventory), `a.area` (id, name or snapshot; throws NoSuchArea), `a.map`, `a.obj`, `a.arr`.
  Unknown and missing required arguments are rejected before your handler runs.
- **Errors**: throw `ApiError` with a type from `Errors` (`ApiError.notFound(...)`,
  `new ApiError(Errors.GOB_GONE, ...)`). A Java action's `Results` → `c.require(r, "what")`
  (throws ActionFailed). Unexpected RuntimeExceptions are reported as Internal and keep the run alive.
- **Never catch `InterruptedException` or `Exception`** (project rule; the stop button depends on it).
  Declare `throws InterruptedException` and let it propagate.
- **Waiting**: `c.waitUntil(() -> cond, timeoutSec, "what")` (throws Timeout) or
  `c.waitFor(cond, timeoutSec)` (returns false). Conditions run on the UI thread: keep them cheap,
  no NInventory query methods inside (they block on the UI thread → deadlock; read widget fields).
  Do **not** use finite `NTask`s (their tick timeout raises InterruptedException = looks like a stop).
  Existing infinite NTasks via `NUtils.addTask` are fine, but prefer `waitUntil` so a timeout exists.
- **Session**: on the bot thread `NUtils.getGameUI()` is the run's session, but prefer `c.gui`/`c.ui`.
- **Client-side widgets** (forms, overlays): create and destroy them on the UI thread with
  `ScriptRuntime.onUiThread(c.ui, runnable)`; undo anything that outlives the op with
  `c.run.addCleanup(runnable)` (runs on the UI thread when the run ends, also on stop).
- **Events**: `ScriptEvents.publish(ui, "kind.name", jsonData)`; check `ScriptEvents.wanted(ui, kind)`
  first so nothing is built when nobody listens. Publishers run on whatever thread; publish is cheap.
- **Logging**: `System.out.println("[Scripting] ...")`, not `gui.msg`.
- Java 8 syntax (no `var`, records, switch expressions, text blocks); JDK 17 library APIs are OK.

## Building and regenerating

```
powershell.exe -Command "cd C:\Users\imbecil\nurgling2; ant"          # compile
powershell.exe -Command "cd C:\Users\imbecil\nurgling2; ant apigen"   # schema → SDK bindings + reference docs
python tools/apigen/apigen.py --check                                    # CI: fails if generated files are stale
```

Generated (do not edit by hand): `docs/scripting/schema/*.json`, `sdk/python/nurgling/_generated.py`,
`docs/scripting/docs/reference/*.md`.

## Gotchas collected from the codebase

- `NInventory.getTotalAmountItems` hangs on unstacked items; count with `getItems(alias).size()`.
- Stacked items live in `GItem.contents` (an `ItemStack`), not in the inventory's widget children.
- `Gob.findol(Class)` NPEs; iterate `gob.ols` and check `ol.spr`.
- An emptied stockpile gob is destroyed; re-find gobs instead of holding them across actions.
- `navigateToArea` may not walk when the area is "reachable"; unloaded gobs look like an empty area.
- Quality shown rounded; thresholds compare the exact float.
- `getModelAttribute()` sign-extends; markers ≥128 read negative.
- Item lookups are by display name; aliases with only `items/x` resource paths may never match.
