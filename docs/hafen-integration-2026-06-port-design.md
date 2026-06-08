# Hafen Integration 2026-06 — Port Design

> ## EXECUTION STATUS (2026-06-08): COMMITTED, builds clean, runtime-testing pending
> Branch `hafen-integration-2026-06`, merge commit `9852d1b92` (parents
> `7bccf9846` nurgling + `e16dcf24b` hafen). All 11 conflicts resolved,
> panels/HeadlessPanel removed, full `ant clean` build passes, `bin/hafen.jar`
> built. **Ancestry verified** (`git merge-base --is-ancestor e16dcf24b HEAD` → 0).
> Nurgling customizations confirmed intact post-merge: NGob field, Session
> `injectMessage`/`CachedRes`, Material `MaterialFactory`, container-color
> `customMask` forcing.
>
> **Not yet done:** in-game testing of normal login, multi-session
> switch/demote-to-headless, and headless `-bots` run. Not pushed.

**Status:** Design / pre-execution
**Merge-base:** `a2043d0d5` → **hafen/master `e16dcf24b`**
**Scope:** 159 new hafen commits, **101 Java files, +22,632 / −2,628**
**Risk:** HIGH — this is hafen's **"iosys" branch**, a ground-up rewrite of the
client I/O layer. It *deletes the classes nurgling's multi-session and headless
subsystems are built on*. This is far larger than the May-2026 merge (37 commits,
3 trivial conflicts).

> Read this whole doc before running `git merge`. The conflict count (11 files) is
> misleadingly small — the real work is re-homing nurgling subsystems onto deleted
> foundations, which `git` cannot flag because the classes simply vanished.

---

## 1. What hafen changed (the "iosys" rewrite)

Hafen replaced the AWT-`MainFrame` + `UIPanel`/`GLPanel` rendering model with a
layered abstraction:

- **`Client`** (new) — top-level owner. Creates a `Toolkit`, gets a `Windeye`,
  spins a `UILoop`, drives `UI.Runner`s. `MainFrame` is now a 6-line shim that
  calls `Client.main()`.
- **`haven.iosys.tk.Toolkit`** (new) — windowing backend abstraction.
  Implementations: `JOGLToolkit`, `LWJGLToolkit`, `AWTToolkit`, `DummyToolkit`,
  plus Panama-FFI GLX/WGL ones (in `opt/panama`, JDK ≥ 22 only).
- **`haven.iosys.tk.Windeye`** (new) — a single window. Provides `env()`,
  `size()`, `swapbuffers()`, `cursor()`, `state()`, `title()`, drop/clipboard.
- **`UILoop`** (new, abstract) — the render/tick thread. Owns `Windeye wnd`,
  `Environment env`, current `UI ui`, and **`newui(UI.Runner)`** — the single
  point where UIs are created/swapped. `Client.ClientLoop` is the concrete impl.
- **`HeadlessClient`** (new) — a `UILoop` (`HeadlessLoop`) backed by
  `DummyToolkit.DummyWindow` + a **real offscreen GL `Environment`** from
  `Acephal.instance().env()`.
- **`UI.Context` is removed** — replaced by `Windeye`. New ctor:
  `UI(Windeye wnd, Audio.Root audio, Coord sz, Runner fun)`.
- Audio/clipboard/drop rewritten (Promise-based, toolkit-routed). Cameras moved
  to fine-scroll already handled in the May merge.

### Old (nurgling) → New (hafen) mapping

| Nurgling depends on (deleted/changed) | New hafen equivalent |
|---|---|
| `UIPanel` (interface) | `UILoop` (loop) + `Windeye` (window) |
| `UI.Context` (interface) | `Windeye` |
| `GLPanel` / `JOGLPanel` / `LWJGLPanel` | `iosys.tk.Toolkit` + its `Windeye` |
| `GLPanel.Loop` (render thread) | `UILoop` / `Client.ClientLoop` |
| `panel.getLoop()` | the `UILoop` itself |
| `((GLPanel)panel).env()` | `loop.env` (or `wnd.env()`) |
| `ui.getContext()` (→ GLPanel) | back-ref to the `UILoop` (see §3) |
| `UI(Context, Coord, Runner)` | `UI(Windeye, Audio.Root, Coord, Runner)` |
| `new NUI(this, size, fun)` | `new NUI(wnd, audio, size, fun)` |
| `MainFrame.config` (nurgling static) | nurgling config holder (see §5) |
| `MainFrame.setupres()` | `Client.setupres()` |
| `HeadlessPanel` + `HeadlessEnvironment` | `UILoop`+`DummyToolkit`+`Acephal` |

---

## 2. Conflict set (11 files from `git merge-tree`)

| File | Kind | Resolution |
|---|---|---|
| `README` | content | trivial — keep nurgling header, take hafen body |
| `build.xml` | content | take hafen; verify lib jars + source roots (§6) |
| `src/haven/Utils.java` | content (76) | merge — hafen added utils; keep nurgling additions |
| `src/haven/GobIcon.java` | content (180) | merge carefully — large on both sides |
| `src/haven/OptWnd.java` | content (384!) | take hafen base, re-apply nurgling option panels; drop options for removed fullscreen/panel features |
| `src/haven/MainFrame.java` | content | reduce to hafen shim; re-home nurgling statics (§5) |
| `src/haven/UI.java` | content (80) | adopt `Windeye` ctor; restore nurgling `getInstance/setInstance`, add `loop` back-ref (§3) |
| `src/haven/GLPanel.java` | **modify/delete** | accept deletion; port lifecycle hooks → `UILoop` (§3) |
| `src/haven/JOGLPanel.java` | **modify/delete** | accept deletion; nurgling delta is 8 lines — verify nothing custom lost |
| `src/haven/LWJGLPanel.java` | **modify/delete** | accept deletion; 72-line nurgling delta — audit before discarding |
| `src/haven/UIPanel.java` | **modify/delete** | accept deletion; interface gone |

`Material.java`, `Session.java`, `ModSprite.java`, `StaticSprite.java` did **not**
conflict this round — verify post-merge that their nurgling customizations survived
(guide §"Verification Checklist").

---

## 3. Multi-session port (the hard part)

Nurgling's multi-session system (`src/nurgling/sessions/`) hooks the render loop to
reuse/demote UIs across accounts. It currently binds to `GLPanel.Loop`:

**Current coupling**
- `NRemoteUI.onInit`: `ui.getContext() instanceof GLPanel` → `panel.getLoop()` →
  `loop.get/setUILifecycleListener(...)`; `SessionUIController.initialize(panel)`.
- `NUILifecycleListener`: `beforeNewUI(Runner, UI, UIPanel)`,
  `afterNewUI(UI, UI)`, `onNewSessionRequested(UIPanel)`; uses
  `panel instanceof GLPanel` and `((GLPanel)panel).env()`.
- `SessionUIController`: stores a `UIPanel panel`.
- `UILifecycleListener` (haven pkg, nurgling-authored): signatures take `UIPanel`.
- Nurgling-added hook sites lived in `GLPanel.Loop.newui()` (lines ~469/520/534).

### Design

**3a. Move the lifecycle hook into `UILoop.newui()`.**
Patch hafen's `UILoop.newui(UI.Runner)` minimally — mirror exactly what nurgling
already did to `GLPanel.Loop.newui()`:
```java
// fields on UILoop
private UILifecycleListener lifecycleListener;
public void setUILifecycleListener(UILifecycleListener l){lifecycleListener=l;}
public UILifecycleListener getUILifecycleListener(){return lifecycleListener;}

// inside newui(), before constructing newui:
if(lifecycleListener != null){
    UI reuse = lifecycleListener.beforeNewUI(fun, this.ui, this);
    if(reuse != null){ /* swap in reuse, set reuse.env = this.env, return reuse */ }
}
// after newui constructed, before prevui.destroy():
boolean destroyOld = (lifecycleListener == null) ||
                     lifecycleListener.afterNewUI(newui, prevui);
if(prevui != null && destroyOld) prevui.destroy();
```
This keeps the hook in `haven` (smallest surface, matches prior approach). The
listener field lives on `UILoop`, so both `ClientLoop` and the headless loop get it
for free.

> Alternative considered: `NUILoop extends Client.ClientLoop`. Rejected —
> `ClientLoop` is `private static` with a private ctor, instantiated inside
> `Client.run()`; subclassing forces patching `Client` anyway, for no benefit over
> patching `UILoop` directly.

**3b. Change `UILifecycleListener` signatures `UIPanel` → `UILoop`.**
```java
default UI beforeNewUI(UI.Runner r, UI cur, UILoop loop){return null;}
default boolean afterNewUI(UI newUI, UI oldUI){return true;}
default void onNewSessionRequested(UILoop loop){}
```
Update `NUILifecycleListener` accordingly: `panel instanceof GLPanel` →
(always a `UILoop` now, drop the check); `((GLPanel)panel).env()` → `loop.env`.

**3c. Give the UI a back-reference to its loop.**
`NRemoteUI.onInit` needs the loop. `UI.getContext()` returned the GLPanel; replace
with a `public UILoop loop` field on `UI`, set inside `UILoop.newui()`
(`newui.loop = this;`). Then:
```java
UILoop loop = ui.loop;                       // was: panel.getLoop()
if(loop.getUILifecycleListener() == null)
    loop.setUILifecycleListener(new NUILifecycleListener());
if(SessionUIController.getInstance() == null)
    SessionUIController.initialize(loop);
```
(Drop the `instanceof GLPanel` guard — there is always a loop now, including
headless. Confirm headless path is acceptable here, or guard on
`!(loop instanceof headless)` if tab-bar attach must be skipped headless.)

**3d. `SessionUIController.panel` → `UILoop`.**
Change the field type and `initialize(UILoop)` / `getPanel()` signature. The
`onAddAccountClicked` path calls `demoteToHeadless()` — loop-agnostic, unchanged.

**3e. Keep `UI.getInstance()/setInstance()`** — pure nurgling statics over
`SessionManager`, no `Context` dependency. Port verbatim into merged `UI.java`.

### Open questions for execution (inspect `SessionContext` / `SessionManager`)
- How does `SessionContext.demoteToHeadless()` keep a demoted UI ticking? If it
  relied on `HeadlessPanel.run()`, it must now use the headless `UILoop` (§4) or
  the existing background message pump.
- `NRemoteUI.spawnBackgroundMessageLoop` pumps `sess.getuimsg()` directly into the
  UI — **panel-independent, unaffected**. Good.
- Confirm tab-bar attach during headless newui is harmless (login screen path).

---

## 4. Headless rebuild (on hafen infra — per decision)

> **CORRECTION (verified):** nurgling's headless mode uses **no real GL**.
> `HeadlessEnvironment` is a pure software **stub** (`HeadlessRender` /
> `HeadlessFillBuffer` / `HeadlessDrawList` accept-and-ignore draw commands and
> fire `pget` callbacks immediately). Hit-testing runs with zero OpenGL. So we do
> **NOT** use `Acephal.instance().env()` (real offscreen GL, as hafen's
> `HeadlessClient` does) — we **keep the stub env**. No GPU/display required on the
> bot host; the "Acephal availability" risk does not apply.

**Keep** `HeadlessEnvironment`, `HeadlessRender`, `HeadlessFillBuffer`,
`HeadlessDrawList`. **Delete** only `HeadlessPanel.java` (the `UIPanel`/`UI.Context`
impl). Replace the panel with a `UILoop` subclass modeled on
`HeadlessClient.HeadlessLoop`, but fed nurgling's stub env via
`DummyToolkit.DummyWindow.of(size, env, swap)` (which accepts *any* `Environment`):

```java
class NHeadlessLoop extends UILoop {
    NHeadlessLoop(Coord size){
        super(DummyToolkit.DummyWindow.of(size, new HeadlessEnvironment(), null));
        // NOTE: stub env's render() processes immediately, so the FragColor/Depth
        // basestate() that HeadlessClient.HeadlessLoop adds is unnecessary — verify
        // UILoop.display()/Frame.run() are happy with the stub Render (they call
        // buf.clear/fence/env.submit, all no-ops in the stub). Adjust if needed.
    }
    protected UI mkui(Windeye w, Audio.Root a, Coord s, UI.Runner f){ // §7
        return new NUI(w, a, s, f);
    }
    protected void drawcursor(UI ui, GOut g){}   // no cursor
    protected void dispatch(UI ui){}             // no input
    protected boolean bgmode(){return false;}
    protected AudioSystem.SinkLine audiosink(){return DummyAudio.DummySink.instance;}
}
```

**`HeadlessMain` retargets** (keep its auth + bot-mode + loop/restart orchestration):
- `MainFrame.setupres()` → `Client.setupres()`
- `MainFrame.config = nconfig` → nurgling config holder (§5)
- Replace the `HeadlessPanel`-thread plumbing in `runSession`/`runUILoop` with
  `new NHeadlessLoop(size).run(remoteUI)`-style driving (mirror
  `HeadlessClient.run`: `loop.start(); while(task!=null) task = task.run(loop.newui(task));`).
- `Headless.setHeadless(true)` stays (gates widget classes).

**Watch item (not a host risk):** the old `HeadlessPanel.run()` ticked the UI at a
fixed 20 Hz via its own loop. The new `UILoop.run()` ticks via `framedur()` from
`GSettings.hz`. Confirm the demoted/headless tick cadence is still sane for bots
(it drives `sess.glob.ctick()` + `map.sendreqs()`), and that `UILoop`'s render
`run()` doesn't busy-spin against the stub env (stub `render()`/`submit()` are
effectively free, so framedur sleeping still applies).

---

## 5. Entry point, config, setupres

- `MainFrame` → hafen shim (`Client.main(args)`).
- `MainFrame.config` was a **nurgling-added static** holding `NConfig`. Re-home it.
  Options: (a) `NConfig.current` static; (b) keep a static on a nurgling holder.
  Recommend a single static accessor used by both `NCore` and `HeadlessMain`.
  Update `NCore.java:234` `config = MainFrame.config;` to the new accessor.
- `MainFrame.setupres()` → `Client.setupres()` (now `public static`, same body).
- Nurgling's normal (graphical) startup currently flows MainFrame→panel. Confirm
  how nurgling injects `NUI`/`NConfig`/`Widget.initnames()` into the new
  `Client.main2()` path — likely a small `NClient` or a patch to `Client.main2`
  to call nurgling init before `new Client(Toolkit.instance())`.

---

## 6. build.xml / dependencies

- Take hafen's `build.xml`. New bits:
  - `opt/panama` source tree, compiled **only if JDK ≥ 22** (`has-panama`
    condition) into `hafen-panama.jar`. **Main client builds without it** — the
    JOGL/LWJGL/AWT toolkits are pure Java. So no forced JDK bump.
  - Manifest `Class-Path` gains `hafen-panama.jar` + LWJGL jars
    (`lwjgl-fat.jar`, `lwjgl-awt.jar`, `lwjgl-opengl-fat.jar`).
- **Action:** confirm `lib/` contains the LWJGL jars (the `LWJGLToolkit` needs
  them at compile time). If missing, either add them or strip `LWJGLToolkit` from
  the build. Check `lib/jglob.jar` present (used by Acephal discovery).
- Ensure nurgling source roots (`src/` covers `src/nurgling`) still in `srcdir`.

---

## 7. NUI construction

`new NUI(Context, Coord, Runner)` → must become `new NUI(Windeye, Audio.Root, Coord, Runner)`.
But UIs are now built **inside `UILoop.newui()`** (`new UI(wnd, audio, sz, fun)`),
not by nurgling. To get `NUI` instead of `UI`, either:
- **(a)** make `UILoop.newui()` call an overridable factory
  `protected UI mkui(Windeye, Audio.Root, Coord, Runner)` that nurgling overrides
  to return `NUI` (cleanest, one small haven patch); or
- **(b)** override `newui()` in `NHeadlessLoop`/the client loop to construct `NUI`
  and replicate the swap logic (more duplication).

Recommend **(a)**: add `mkui()` to `UILoop`, default returns `new UI(...)`,
nurgling overrides to `new NUI(...)`. This is the single most load-bearing patch —
it makes *every* loop (client + headless) produce `NUI` without duplicating swap
logic.

---

## 8. Execution order

0. **Branch:** `git checkout -b hafen-integration-2026-06` off `master`.
1. **Merge:** `git merge hafen/master` (expect the 11 conflicts).
2. **Trivial:** README, Utils, build.xml. Verify lib jars (§6).
3. **Accept deletions:** `git rm` GLPanel/JOGLPanel/LWJGLPanel/UIPanel **after**
   auditing their nurgling deltas (LWJGLPanel 72 / GLPanel 63 lines — confirm
   nothing custom is silently dropped; capture anything real into the new arch).
4. **UI.java:** adopt `Windeye` ctor, add `loop` field + `mkui()` consumer, restore
   `getInstance/setInstance`. (§3c, §7)
5. **UILoop patch:** lifecycle-listener field+hooks (§3a) + `mkui()` factory (§7).
6. **UILifecycleListener + NUILifecycleListener + SessionUIController + NRemoteUI:**
   retarget `UIPanel`/`GLPanel` → `UILoop`. (§3)
7. **Config/entry:** MainFrame shim, config holder, `Client.setupres`, nurgling
   `Client` init injection. (§5)
8. **Headless:** `NHeadlessLoop` + `HeadlessMain` retarget; delete `HeadlessPanel`
   + `HeadlessEnvironment`. (§4)
9. **OptWnd / GobIcon:** finish the large content merges.
10. **Build:** `ant clean` then full build (incremental misses subclass-override
    breakage — see memory). Fix compile errors iteratively.
11. **Verify:** guide checklist + ancestry
    (`git merge-base --is-ancestor $(git rev-parse hafen/master) HEAD`).
12. **Update** `docs/hafen-integration-guide.md` footer + add an "iosys" notes
    section.

---

## 8b. Deferred / dropped on purpose

- **LWJGLPanel GL-cleanup hardening** (nurgling delta, ~60 lines): a Windows-focused
  robustness fix for the *LWJGL* backend — `activePanels` set + JVM shutdown hook
  disposing `env`, `swapBuffers()` wrapped in try/catch (DWM-conflict tolerance),
  and a `finally` block disposing the GL env on loop exit. The new arch replaces
  `LWJGLPanel` with `haven.iosys.tk.LWJGLToolkit`; JOGL is the default backend, so
  this is **deferred, not lost**. If LWJGL-backend users hit GPU-hook hangs on
  shutdown, re-apply the equivalent (shutdown hook + swapbuffers guard + env
  dispose) to `LWJGLToolkit`/its `Windeye.dispose()`. The multi-session delegates
  from the same file (`requestNewSession`/`getLoop`) are NOT deferred — they're
  ported to `UILoop` (§3).

## 9. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Multi-session loop hook port (§3) is subtly wrong → sessions don't reuse/demote | HIGH | Mirror old `GLPanel.Loop.newui` semantics exactly; test 2-account switch + demote-to-headless |
| ~~`Acephal.instance()` unavailable on true-headless host~~ | N/A | **Resolved:** headless keeps the GL-free `HeadlessEnvironment` stub; no Acephal/GPU needed (§4) |
| LWJGL/jglob jars missing from `lib/` (§6) | MED | Check `lib/` early; add or strip `LWJGLToolkit` |
| OptWnd 384-line merge drops a nurgling option | MED | Diff nurgling OptWnd vs base; re-apply panel-by-panel |
| Silent loss of LWJGLPanel/GLPanel nurgling delta on deletion (§8.3) | MED | Audit deltas before `git rm` |
| `NUI` not produced by loops (§7) | MED | `mkui()` factory; grep all `new NUI(` / `new UI(` call sites |
| Input event mapping changed (Toolkit events → AWTCompat) breaks nurgling input hooks | MED | Check nurgling key/mouse hooks vs new `Client.EventQueue.dispatch` |

---

## 10. Reference: key new hafen sources

- `src/haven/Client.java` — entry, `ClientLoop`, `EventQueue`, `setupres`, `connect`
- `src/haven/UILoop.java` — abstract loop; `newui()`, `setenv()`, render `run()`
- `src/haven/HeadlessClient.java` — `HeadlessLoop` over `DummyToolkit`+`Acephal`
- `src/haven/iosys/tk/Windeye.java` — window interface (`env/size/swapbuffers/...`)
- `src/haven/iosys/tk/Toolkit.java` — backend interface
- `src/haven/iosys/tk/DummyToolkit.java` — headless window
- `src/haven/iosys/tk/Acephal.java` — offscreen renderer discovery

(Working copies dumped under `.tmp_integration/hafen/` during investigation —
delete when done; it's untracked.)
