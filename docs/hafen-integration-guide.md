# Hafen Integration Guide

## Overview

This document explains how to properly integrate upstream hafen changes into nurgling2 while preserving nurgling's custom functionality.

## Critical Principle

**ALWAYS use git merge, NEVER cherry-pick or rebase hafen commits.**

### Why Merge?

- **Preserves git ancestry**: Future `git merge` commands will know which hafen commits are already integrated
- **Prevents duplicate commits**: Cherry-picking creates new commit SHAs, causing git to re-apply the same changes
- **Maintains history**: Shows the relationship between hafen and nurgling branches

### What Happens With Cherry-Picking (WRONG)

```bash
# DON'T DO THIS:
git cherry-pick <hafen-commits>
```

Result:
- Creates new commits with different SHAs
- Git doesn't know these are hafen commits
- Next integration will try to re-apply the same changes
- Merge conflicts on already-integrated code

## Integration Process

### Step 1: Prepare

```bash
# Ensure you're on master or create a new integration branch
git checkout master  # or: git checkout -b hafen-integration-YYYY-MM

# Fetch latest hafen changes
git fetch hafen
```

### Step 2: Check What's New

```bash
# See how many commits hafen is ahead
git log --oneline origin/master..hafen/master

# See what files changed (excluding resources)
git diff --stat origin/master hafen/master -- "*.java"

# Expected: ~20-30 commits, ~20-25 Java files for a typical integration
```

### Step 3: Merge (Not Cherry-Pick!)

```bash
# Merge hafen/master into your branch
git merge hafen/master

# This will likely cause merge conflicts in nurgling-customized files
```

### Step 4: Resolve Conflicts

The following files typically have conflicts:

#### Critical Files With Nurgling Customizations

**src/haven/Material.java**
- **Nurgling changes**: `Material.Res.get(int mask)` method, `HashMap<MaterialFactory.Status, Material> hm`
- **Resolution strategy**:
  - Keep hafen's `Buffer cons` field
  - Keep nurgling's `HashMap hm` field
  - Adapt `get(int mask)` to use hafen's new Buffer/Spec system
  - Example from working integration at commit 57d9570b2

**src/haven/Session.java**
- **Nurgling changes**:
  - `CachedRes` class made public (hafen made it private)
  - `injectMessage()` method for multi-session support
  - `res_id_cache` map and `getResName()` method
- **Resolution strategy**:
  - Accept hafen's changes but restore nurgling extensions
  - Make `CachedRes` and `Ref` public again
  - Add back `injectMessage()` method
  - Add back `res_id_cache` functionality
  - Example at commit 51a5a5f4e

**src/haven/QuestWnd.java**
- **Nurgling changes**: Usually minimal
- **Resolution strategy**: Take hafen's version (--theirs)

**src/haven/TexRender.java**
- **Nurgling changes**: Usually minimal
- **Resolution strategy**: Take hafen's version (--theirs)

### Step 5: Add Nurgling Customizations

After resolving conflicts, ensure these nurgling-specific files are included:

**Critical Customizations:**

1. **src/haven/ModSprite.java**
   - Purpose: Container status color system
   - Changes: customMask forcing logic for dframes/barrels
   - Lines: ~25 lines added in `Meshes.operate()` method

2. **src/haven/StaticSprite.java**
   - Purpose: Container status color system
   - Changes: customMask forcing logic for barrels/dframes
   - Lines: ~30 lines added in `lsparts()` method

3. **src/nurgling/NGob.java**
   - Purpose: Core nurgling game object extensions
   - No hafen conflicts (purely nurgling code)

4. **src/nurgling/tools/MaterialFactory.java**
   - Purpose: Provides custom materials for container status colors
   - No hafen conflicts (purely nurgling code)

### Step 6: Build and Test

```bash
# Build to check for compilation errors
ant clean compile

# Common compilation errors after hafen integration:
# - Session.CachedRes access issues → ensure CachedRes is public
# - Material.Res changes → check get(int mask) method
# - Missing imports → hafen may have reorganized packages
```

### Step 7: Commit the Merge

```bash
git add -A
git commit -m "Merge hafen/master - integrate upstream changes

- Merged X commits from hafen/master
- Resolved conflicts in Material.java, Session.java, etc.
- Preserved nurgling's MaterialFactory integration
- Preserved container status color system
- Updated to hafen's new [describe major change, e.g., PType system]"
```

### Step 8: Verify History

```bash
# Verify hafen commits are in ancestry
git log --oneline --graph --decorate -20

# Should show merge commit with two parents:
# * <hash> (HEAD) Merge hafen/master
# |\
# | * <hash> (hafen/master) <hafen commit>
# | * <hash> <hafen commit>
# * | <hash> <nurgling commit>

# Test that hafen commits are ancestors
git merge-base --is-ancestor <hafen-commit-sha> HEAD
# Should exit with code 0 (success)
```

## Files With Nurgling Customizations

### Material System (3 files)
- `src/haven/Material.java` - MaterialFactory integration
- `src/haven/ModSprite.java` - customMask forcing
- `src/haven/StaticSprite.java` - customMask forcing

### Game Logic (9 files)
- `src/haven/Gob.java` - `public NGob ngob` field
- `src/haven/GItem.java` - Item tracking
- `src/haven/Inventory.java` - Inventory monitoring
- `src/haven/GobHealth.java` - Health tracking
- `src/haven/GobIcon.java` - Icon recognition
- `src/haven/ItemDrag.java` - Item interaction
- `src/haven/ItemInfo.java` - Extended item info
- `src/haven/WItem.java` - Widget extensions
- `src/haven/res/ui/stackinv/ItemStack.java` - Stack handling

### Resource Hooks (2 files)
- `src/haven/ResDrawable.java` - NGob integration
- `src/haven/Resource.java` - Resource loading hooks

### Session/Networking (1 file)
- `src/haven/Session.java` - Multi-session support, CachedRes access

### UI Extensions (93 files)
See `docs/nurgling-ui-modifications.md` for complete list.

## Common Pitfalls

### ❌ WRONG: Cherry-picking hafen commits

```bash
git cherry-pick <hafen-commit>  # DON'T DO THIS
```

**Problem**: Creates duplicate commits, breaks future integrations

### ❌ WRONG: Using --theirs for all conflicts

```bash
git merge hafen/master
git checkout --theirs .  # DON'T DO THIS
git commit
```

**Problem**: Loses all nurgling customizations

### ❌ WRONG: Forgetting to verify ancestry

```bash
git merge hafen/master
# resolve conflicts
git commit
# DONE! ...but is hafen actually in the ancestry?
```

**Problem**: May have accidentally created orphan commits

### ✅ CORRECT: Selective conflict resolution

```bash
git merge hafen/master
# Material.java: carefully merge nurgling + hafen
# Session.java: restore nurgling extensions on top of hafen
# QuestWnd.java: take hafen's version
git commit
# Verify ancestry with: git merge-base --is-ancestor <hafen-commit> HEAD
```

## Conflict Resolution Strategies

### Strategy 1: Take Hafen + Add Nurgling (Material.java)

```bash
# 1. Accept hafen's new architecture
git show hafen/master:src/haven/Material.java > src/haven/Material.java

# 2. Add nurgling's get(int mask) method
# Edit Material.java to add:
#   - private transient HashMap<MaterialFactory.Status, Material> hm
#   - public Material get(int mask) { ... }

# 3. Import MaterialFactory
# Add: import nurgling.tools.MaterialFactory;
```

### Strategy 2: Take Hafen + Restore Extensions (Session.java)

```bash
# 1. Accept most of hafen's changes
git checkout --theirs src/haven/Session.java

# 2. Manually restore nurgling extensions:
#   - Change "private static class CachedRes" → "public static class CachedRes"
#   - Add injectMessage() method
#   - Add res_id_cache field and getResName() method
```

### Strategy 3: Take Hafen Completely (QuestWnd.java)

```bash
# Simple case: no nurgling customizations needed
git checkout --theirs src/haven/QuestWnd.java
```

## Verification Checklist

After integration, verify:

- [ ] Build succeeds: `ant clean compile`
- [ ] Hafen commits in ancestry: `git merge-base --is-ancestor <hafen-commit> HEAD` → exit 0
- [ ] Container colors work: barrels/dframes/ttubs show correct status colors
- [ ] MaterialFactory integration intact: `grep -r "MaterialFactory" src/haven/Material.java`
- [ ] Session extensions present: `grep -r "injectMessage\|CachedRes" src/haven/Session.java`
- [ ] NGob field exists: `grep "public NGob ngob" src/haven/Gob.java`
- [ ] No duplicate hafen commits: `git log --oneline --all | grep "Add PType utility" | wc -l` → should be 1

## Quick Reference Commands

```bash
# Check hafen commits not yet integrated
git log --oneline origin/master..hafen/master

# See file changes (Java only)
git diff --stat origin/master hafen/master -- "*.java"

# Merge hafen
git merge hafen/master

# During conflicts - see what each side changed
git diff HEAD:src/haven/Material.java hafen/master:src/haven/Material.java

# Take hafen's version of a file
git checkout --theirs src/haven/QuestWnd.java

# Take nurgling's version of a file
git checkout --ours src/haven/Material.java

# Verify ancestry after merge
git merge-base --is-ancestor $(git rev-parse hafen/master) HEAD && echo "✓ Ancestry verified"

# See merge commit structure
git log --oneline --graph --decorate -20
```

## Example: Actual Integration (Feb 2026)

Branch: `hafen-integration-proper-v2`
Merge commit: `57d9570b2`

**What was integrated:**
- 20 hafen commits (PType, Maybe, Material refactor)
- 21 Java files from hafen
- Preserved nurgling customizations in 7 additional files

**Conflicts resolved:**
- Material.java: Merged Buffer/Spec system + get(int mask)
- Session.java: Restored public CachedRes + injectMessage()
- QuestWnd.java: Took hafen's version
- TexRender.java: Took hafen's version

**Result:**
- ✅ Build successful
- ✅ Hafen commits in ancestry
- ✅ Container status colors working
- ✅ All nurgling features preserved

**Command used:**
```bash
git checkout origin/master -b hafen-integration-proper-v2
git merge hafen/master
# resolve conflicts
git commit -m "Merge hafen/master into nurgling2 - proper integration with commit history"
```

## Future Integrations

For the next hafen integration:

1. Follow this guide exactly
2. Use `git merge hafen/master` (not cherry-pick!)
3. Resolve conflicts using the strategies above
4. Build and test thoroughly
5. Verify ancestry with `git merge-base --is-ancestor`
6. Update this document if new patterns emerge

## References

- Hafen upstream: https://github.com/dolda2000/hafen-client
- Nurgling2: https://github.com/Katodiy/nurgling2
- Material system details: `docs/material-system.md`
- Container status colors: `docs/container-status-colors.md`

---

**Last Updated:** 2026-06-18
**Last Integration:** hafen-integration-2026-06b (merge commit f924bf54d, branch off master)
**Hafen Commits Integrated:** 49 commits (merge-base e16dcf24b → hafen/master dcb2e1b70)

> Note: The Feb 2026 reference above (57d9570b2 / d58dcb242) is historical and is
> NOT in the current master's ancestry — a later, undocumented integration brought
> hafen history up to merge-base `20dc6f473` ("Handle cached icon resources more
> robustly", 2026-05-03). Always derive the real merge-base with
> `git merge-base HEAD hafen/master` rather than trusting this footer.

### May 2026 integration notes (37 commits)

- **Conflicts (3, the rest auto-merged):**
  - `Makewindow.java` — took hafen's rewrite, re-added the `NMakewindow` factory override + import.
  - `WItem.java` — took hafen's reusable `GItem.RStateInfo.combine` (nurgling side was the old inline copy).
  - `GLPanel.java` — adopted hafen's `import haven.GSettings.SyncMode` (enum moved from `JOGLPanel`), kept nurgling imports.
- **Compile fix after merge:** hafen converted cameras to fine-scrolling, changing
  `Camera.wheel(Coord, int)` → `wheel(MouseWheelEvent)` (uses `ev.s`). Updated nurgling's
  custom cameras `NOrthoCam` and `RSTCam` in `MapView.java`. (`mmousewheel(Coord, int)` is a
  separate, unchanged interface — left alone.)
- **MiniMap redisplay moved into `tick()`** (with `DisplayMarker.dispupdate()`); `NMiniMap`
  still works because its `tick()` calls `super.tick()` and it computes marker positions itself.
- **Make-window feature port (NMakewindow):** ported hafen's two new features into nurgling's
  reimplementation — server `use` msg (in-use red overlay on inputs) and `inprcps` msg +
  `choose`/`findrcps` clicks (input recipe-choice popup). Split cleanly: autoMode = nurgling
  automation, normal mode = hafen input-choice.
- **Verified safe (no nurgling refs):** GSettings `SyncMode` move, profiling-switch removal,
  `SListBox` fine-scroll (internal `cury` int→double, public API unchanged).

### June 2026 integration notes (159 commits — the "iosys" rewrite) — MAJOR

This was hafen's `iosys` branch: a ground-up rewrite of the client I/O layer. Far
larger than a normal integration. Full port design + recovery info:
`docs/hafen-integration-2026-06-port-design.md`. Merge commit `9852d1b92`.

- **Architecture change:** `MainFrame`(AWT Frame) + `UIPanel`/`GLPanel`/`JOGLPanel`/
  `LWJGLPanel` + `UI.Context` were **deleted**, replaced by `Client` +
  `haven.iosys.tk.Toolkit`/`Windeye` + abstract `UILoop`. `MainFrame` is now a
  6-line `Client.main()` shim upstream. Panama FFI lives in `opt/panama` (compiled
  only on JDK ≥ 22 via `has-panama`); the main client still builds on the current JDK.
- **Conflicts (11):** README, build.xml (kept nurgling Main-Class=MainFrame + extra
  jars, added hafen-panama.jar + Add-Exports/Enable-Native-Access), Utils
  (kept public `imgsz` + hafen `initlocale`), GobIcon (imports), UI
  (`UI.Context`→added `UILoop loop` back-ref; kept get/setInstance; took hafen
  CommandQueue.drain), OptWnd (took hafen ui-param panels + audio API, re-grafted
  L10n + nqolwnd), MainFrame, and modify/delete on the 4 panel classes.
- **The real work (git couldn't flag it — classes vanished):** nurgling's
  multi-session + headless subsystems were built on the deleted classes.
  - Added to `UILoop`: a `mkui()` factory (so every loop builds `NUI` not `UI`), a
    `UI.loop` back-reference, and the multi-session lifecycle hooks
    (`beforeNewUI`/`afterNewUI`) that formerly lived on `GLPanel.Loop.newui()`.
  - `MainFrame` rebuilt as a pure nurgling **launcher** (config/l10n/logging/error-
    handling/headless-dispatch/NBootstrap factory) delegating windowing to `Client`.
    Kept `MainFrame.config` (NConfig) + `setupres()`→`Client.setupres()` so NCore /
    HeadlessMain were untouched. Edited the merged `Client.java` in place
    (Option A): `ClientLoop.mkui()`→`NUI`, `Client.Main`→`NBootstrap.create()`.
  - Multi-session (`NRemoteUI`/`NUILifecycleListener`/`SessionUIController`/
    `UILifecycleListener`): retargeted `UIPanel`/`GLPanel`/`ui.getContext()` →
    `UILoop`/`ui.getLoop()`/`loop.env`.
  - **Headless rebuilt on hafen infra:** new `NHeadlessLoop extends UILoop` backed by
    `DummyToolkit.DummyWindow.of(size, new HeadlessEnvironment(), null)`. Kept the
    GL-free `HeadlessEnvironment`/`HeadlessRender`/… stub (no GPU/Acephal needed —
    headless never used real GL). Deleted `HeadlessPanel`; `HeadlessMain` drives the
    loop via `task.run(loop.newui(task))` (UI ctor calls `fun.init`).
- **Compile-fix ripples after merge (caught by `ant clean`, not incremental):**
  - Audio rewrite removed static `Audio.play`/`Audio.volume` → `ui.sfx(...)` /
    `ui.audio.sys.volume()` (NAlarmManager routes through `UI.getInstance()`).
  - `WebBrowser` deleted → `ui.wnd.toolkit().browse(URI)` (NMappingClient, NLoginScreen).
- **Deferred (recorded, not lost):** LWJGLPanel's GL-cleanup hardening (shutdown
  hook / swapBuffers guard / env-dispose). LWJGL-backend only; JOGL is default.
- **Status:** `ant clean` builds; ancestry verified. Runtime testing (visual login,
  multi-session switch/demote, headless `-bots`) still pending at time of writing.

### June 2026 round 2 (49 commits — make-window v31 + MenuSearch refactor)

Normal-size integration on top of the iosys merge (merge-base `e16dcf24b` →
hafen/master `dcb2e1b70`). Merge commit `f924bf54d`, branch
`hafen-integration-2026-06b`. Only **3 git conflicts** — but the real risk was a
nurgling-only file that auto-merged clean yet would break at runtime.

- **⚠ Hidden runtime break — NMakewindow (no git conflict).** Hafen bumped
  `Session.PVER` 30 → 31 and rewrote the make-window `inpop`/`opop` wire format
  (modular: each spec wrapped in an `OBJS` array; indexed updates when first arg is
  an INT; new `constraint` sub-arg; `Spec` ctor → `ResData`). Nurgling's
  `NMakewindow` is a parallel reimplementation (`extends Widget`, its own `Spec`,
  its own flat-format parser) — git/ant can't flag it, but the v31 server would
  send the new format and crafting/autocraft/presets would misparse. **Ported**
  `parsespec()` + dual-form `inpop`/`opop` + `constraint` field; added
  `ui.modflags()` to the `choose` send. Category detection is name-based
  (`VSpec.categories.get(s.name)`), unaffected by the constraint change.
- **Conflicts (3):**
  - `MenuSearch.java` — hafen made it `abstract` (base + `Main` subclass + abstract
    `generate()` + `recons`/`tvisible()`/`pagseq` + `reqclose();settext();refilter()`
    in `activate`). Re-grafted nurgling features onto the new shape: `Result.bot`,
    `Fuzzy.fuzzyFilterAndSort` in `refilter()`, drag-drop+grab+`draw()`+`tooltip()`
    in `Results`, bots from `BotRegistry.allowedInBotMenu()` in base `updlist()`,
    and global-paginae accumulation moved into `Main.generate()` (uses `menu.pagseq`
    to retrigger; nurgling search stays global, ignoring the current category root).
  - `GameUI.java` — hafen replaced `wdgmsg("close")` listening with per-window
    `reqclose(Runnable)` callbacks and made the search window always-present
    (`MenuSearch.Main` created at `place=="menu"`). The old close-router `wdgmsg`
    override auto-merged away; csearch button auto-merged to the toggle form. Kept
    nurgling's `NMapWnd`/`NMenuGridWdg`/`NMiniMapWnd` and the intentionally
    commented-out `MapMenu` buttons; added the always-present srchwnd alongside the
    nurgling menu-grid widget; added the map-window `reqclose` callback to `NMapWnd`.
  - `MainFrame.java` — modify/delete: hafen deleted its own 7-line compatibility
    shim (`092e98b92`); nurgling's MainFrame is our launcher → kept ours.
- **Auto-merged, verified intact:** Makewindow (3-line nurgling delta), Window
  (`reqclose(Runnable)` setter), GobIcon, Audio/JavaSound/DummyAudio (NAlarmManager
  already uses `ui.sfx()`), Session (injectMessage/CachedRes), Material
  (MaterialFactory), container-color customMask. All `opt/panama/**` additions
  (DBus/desktop-portal/OSX/ALSA FFI) are JDK ≥ 22-only and don't touch the main build.
- **Status:** `ant clean` builds; ancestry verified (`f924bf54d` two-parent merge,
  hafen tip `dcb2e1b70` is an ancestor of HEAD). **Runtime testing pending** —
  especially crafting/autocraft/craft-presets (NMakewindow v31 port), action search
  incl. bot drag-drop, window close behavior, and audio/alarms. Not pushed.
