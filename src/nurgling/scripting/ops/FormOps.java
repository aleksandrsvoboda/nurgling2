package nurgling.scripting.ops;

import haven.*;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.i18n.L10n;
import nurgling.overlays.NCustomBauble;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.api.*;
import nurgling.scripting.ui.ScriptForm;
import nurgling.scripting.ui.ScriptPrompt;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.WAIT;

/**
 * Script UI: settings forms, confirmations, and asking the player to pick an object or an area on
 * the map. Every window belongs to the run and closes when the run ends.
 */
public final class FormOps {

    private static final String FIELD_DOC = "Each field is a dict `{name, type, label, default, values, required}`. "
            + "Types: `int` and `float` (a number entry), `bool` (a check box), `str` (a text entry), "
            + "`choice` (a dropdown of `values`), `area` (the saved areas, plus \"Select on map...\" which lets the player drag a rectangle after OK; set `\"map\": false` to allow saved areas only), "
            + "`gob` (a button that lets the player click an object), and `label` (static text, no value; give the text as `label`). "
            + "`label` defaults to `name`; `required` fields marked with * must be filled before OK is accepted.";

    /**
     * Shows a settings form on the run's session and blocks until the player confirms it.
     * Returns the values by field name; throws ApiError(Cancelled) if the form is dismissed.
     * Field spec: {name, type: int|float|bool|str|choice|area|gob|label, label, default, values, required}.
     */
    public static JSONObject ask(OpContext c, String title, JSONArray fields) throws InterruptedException {
        return ask(c, title, fields, 0);
    }

    static JSONObject ask(OpContext c, String title, JSONArray fields, double timeout) throws InterruptedException {
        ScriptForm.checkSpecs(fields);
        ScriptForm f = show(c, title, fields, L10n.get("common.ok"), L10n.get("common.cancel"), timeout);
        if (f.cancelled()) throw new ApiError(Errors.CANCELLED, "the form '" + title + "' was dismissed");
        JSONObject vals = f.values();
        for (String[] pick : f.mapPicks())
            vals.put(pick[0], Codec.rect(pickArea(c, pick[1], 0)));
        return vals;
    }

    /** Opens a form, waits until it is confirmed, dismissed or the timeout passes, and closes it. */
    private static ScriptForm show(OpContext c, String title, JSONArray fields, String ok, String cancel, double timeout) throws InterruptedException {
        requireVisible(c);
        AtomicReference<ScriptForm> ref = new AtomicReference<>();
        AtomicBoolean built = new AtomicBoolean(false);
        Runnable close = () -> {
            ScriptForm f = ref.get();
            if (f != null) f.reqdestroy();
        };
        c.run.addCleanup(close);
        NMapView map = (NMapView) c.gui.map;
        ScriptRuntime.onUiThread(c.ui, () -> {
            try {
                ScriptForm f = new ScriptForm(title == null ? c.run.title : title, fields, map, ok, cancel);
                NUtils.addCentered(c.gui, f);
                ref.set(f);
            } finally {
                built.set(true);
            }
        });
        try {
            c.waitUntil(() -> built.get() && (ref.get() == null || ref.get().finished()), timeout, "the player to answer the form");
            if (ref.get() == null) throw new ApiError(Errors.INTERNAL, "the form could not be built");
            return ref.get();
        } finally {
            c.run.removeCleanup(close);
            ScriptRuntime.onUiThread(c.ui, close);
        }
    }

    private static void requireVisible(OpContext c) {
        if (ScriptRuntime.sessionInfo(c.ui).optBoolean("headless"))
            throw ApiError.notSupported("forms and pickers need a visible session; this one runs in the background");
        if (c.gui == null || !(c.gui.map instanceof NMapView))
            throw ApiError.notSupported("the session has no map view");
    }

    private static String title(OpContext c, Args a) {
        return a.str("title", c.run.title);
    }

    @ApiOp(name = "form.ask", kind = WAIT,
            doc = "Shows a settings form in the client's own style and waits until the player presses OK. "
                    + FIELD_DOC + " An `area` field returns the area id, or a Rect `[[x1, y1], [x2, y2]]` when the player chose \"Select on map...\"; "
                    + "a `gob` field returns the object id (null if none was picked and the field is optional). "
                    + "Empty optional number fields are null. Needs a visible session.",
            params = {
                    @ApiParam(name = "title", type = "str", doc = "Window caption."),
                    @ApiParam(name = "fields", type = "list[dict]", doc = "Field specs, shown top to bottom."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait; 0 waits until the player answers.", optional = true, def = "0")},
            returns = "dict", returnsDoc = "Values by field name (label fields are left out).",
            errors = {Errors.CANCELLED, Errors.NOT_SUPPORTED, Errors.TIMEOUT},
            example = "cfg = nc.form.ask(\"Chopper\", [\n"
                    + "    {\"name\": \"area\", \"type\": \"area\", \"label\": \"Trees\", \"required\": True},\n"
                    + "    {\"name\": \"count\", \"type\": \"int\", \"label\": \"How many\", \"default\": 10},\n"
                    + "    {\"name\": \"axe\", \"type\": \"choice\", \"values\": [\"Stone Axe\", \"Metal Axe\"]},\n"
                    + "    {\"name\": \"stack\", \"type\": \"bool\", \"label\": \"Stack logs\", \"default\": True},\n"
                    + "])\nprint(cfg[\"count\"], cfg[\"area\"])")
    public static Object askOp(OpContext c, Args a) throws InterruptedException {
        return ask(c, a.str("title"), a.arr("fields"), a.d("timeout", 0));
    }

    @ApiOp(name = "form.confirm", kind = WAIT,
            doc = "Asks a yes/no question in a small window and waits for the answer. Closing the window counts as No. Needs a visible session.",
            params = {
                    @ApiParam(name = "text", type = "str", doc = "The question."),
                    @ApiParam(name = "title", type = "str", doc = "Window caption; defaults to the script's name.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait; 0 waits until the player answers.", optional = true, def = "0")},
            returns = "bool", returnsDoc = "True for Yes.",
            errors = {Errors.NOT_SUPPORTED, Errors.TIMEOUT},
            example = "if nc.form.confirm(\"Chop all 40 trees?\"):\n    chop_all()")
    public static Object confirm(OpContext c, Args a) throws InterruptedException {
        JSONArray fields = new JSONArray().put(new JSONObject().put("type", "label").put("label", a.str("text")));
        ScriptForm f = show(c, title(c, a), fields, L10n.get("common.yes"), L10n.get("common.no"), a.d("timeout", 0));
        return !f.cancelled();
    }

    @ApiOp(name = "form.text", kind = WAIT,
            doc = "Asks the player to type a line of text and waits for OK. Needs a visible session.",
            params = {
                    @ApiParam(name = "prompt", type = "str", doc = "What to ask, shown above the entry."),
                    @ApiParam(name = "default", type = "str", doc = "Initial text.", optional = true, def = "\"\""),
                    @ApiParam(name = "title", type = "str", doc = "Window caption; defaults to the script's name.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait; 0 waits until the player answers.", optional = true, def = "0")},
            returns = "str", returnsDoc = "The text as typed (may be empty).",
            errors = {Errors.CANCELLED, Errors.NOT_SUPPORTED, Errors.TIMEOUT},
            example = "name = nc.form.text(\"Name for the new area:\", default=\"Logs\")")
    public static Object text(OpContext c, Args a) throws InterruptedException {
        JSONArray fields = new JSONArray().put(new JSONObject().put("name", "text").put("type", "str")
                .put("label", a.str("prompt")).put("default", a.str("default", "")));
        JSONObject v = ask(c, title(c, a), fields, a.d("timeout", 0));
        return v.optString("text", "");
    }

    @ApiOp(name = "form.message", kind = ACTION,
            doc = "Shows an information window with an OK button and returns at once. "
                    + "The window closes when the player presses OK, or when the run ends unless `keep` is true. Needs a visible session.",
            params = {
                    @ApiParam(name = "text", type = "str", doc = "The message; long text wraps."),
                    @ApiParam(name = "title", type = "str", doc = "Window caption; defaults to the script's name.", optional = true),
                    @ApiParam(name = "keep", type = "bool", doc = "Leave the window open after the run ends (for a final report).", optional = true, def = "false")},
            errors = {Errors.NOT_SUPPORTED},
            example = "nc.form.message(f\"Done: {n} logs stacked.\", keep=True)")
    public static Object message(OpContext c, Args a) {
        requireVisible(c);
        JSONArray fields = new JSONArray().put(new JSONObject().put("type", "label").put("label", a.str("text")));
        String title = title(c, a);
        boolean keep = a.b("keep", false);
        NMapView map = (NMapView) c.gui.map;
        ScriptRuntime.onUiThread(c.ui, () -> {
            ScriptForm f = NUtils.addCentered(c.gui, new ScriptForm(title, fields, map, L10n.get("common.ok"), null));
            if (!keep) c.run.addCleanup(f::reqdestroy);
        });
        return null;
    }

    @ApiOp(name = "form.pick_gob", kind = WAIT,
            doc = "Asks the player to click an object on the map and returns it, the same way built-in bots ask for a container or a vehicle. "
                    + "A small window shows the prompt with a Cancel button. Needs a visible session.",
            params = {
                    @ApiParam(name = "prompt", type = "str", doc = "What to click.", optional = true, def = "\"Click an object\""),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait; 0 waits until the player picks or cancels.", optional = true, def = "0")},
            returns = "Gob", returnsDoc = "The clicked object.",
            errors = {Errors.CANCELLED, Errors.NOT_SUPPORTED, Errors.TIMEOUT, Errors.ACTION_FAILED},
            example = "barrel = nc.form.pick_gob(\"Click the water barrel\")\nprint(barrel.name)")
    public static Object pickGob(OpContext c, Args a) throws InterruptedException {
        requireVisible(c);
        NMapView map = (NMapView) c.gui.map;
        if (map.isGobSelectionMode.get() || map.isAreaSelectionMode.get())
            throw ApiError.failed("another selection is already in progress on the map");
        String prompt = a.str("prompt", "Click an object");
        map.selectedGob = null;
        map.isGobSelectionMode.set(true);
        Gob player = NUtils.player();
        if (player != null)
            player.addcustomol(new NCustomBauble(player, Resource.loadsimg("baubles/selectobject"), map.isGobSelectionMode));
        boolean cancelled = waitPick(c, map.isGobSelectionMode, prompt, a.d("timeout", 0), "the player to click an object");
        Gob g = map.selectedGob;
        map.selectedGob = null;
        if (g == null || cancelled) throw new ApiError(Errors.CANCELLED, "no object was picked");
        return g;
    }

    @ApiOp(name = "form.pick_area", kind = WAIT,
            doc = "Asks the player to drag a rectangle on the map, exactly like a bot's \"select area\" step, and returns it in world coordinates. "
                    + "The rectangle snaps to whole tiles. A small window shows the prompt with a Cancel button. Needs a visible session.",
            params = {
                    @ApiParam(name = "prompt", type = "str", doc = "What to select.", optional = true, def = "\"Drag a rectangle on the map\""),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait; 0 waits until the player selects or cancels.", optional = true, def = "0")},
            returns = "Rect", returnsDoc = "`[[x1, y1], [x2, y2]]`, top-left and bottom-right corners.",
            errors = {Errors.CANCELLED, Errors.NOT_SUPPORTED, Errors.TIMEOUT, Errors.ACTION_FAILED},
            example = "(x1, y1), (x2, y2) = nc.form.pick_area(\"Select the field to plough\")")
    public static Object pickAreaOp(OpContext c, Args a) throws InterruptedException {
        return Codec.rect(pickArea(c, a.str("prompt", "Drag a rectangle on the map"), a.d("timeout", 0)));
    }

    static Pair<Coord2d, Coord2d> pickArea(OpContext c, String prompt, double timeout) throws InterruptedException {
        requireVisible(c);
        NMapView map = (NMapView) c.gui.map;
        if (map.isGobSelectionMode.get() || map.isAreaSelectionMode.get())
            throw ApiError.failed("another selection is already in progress on the map");
        map.areaSpace = null;
        map.isAreaSelectionMode.set(true);
        Gob player = NUtils.player();
        if (player != null)
            player.addcustomol(new NCustomBauble(player, Resource.loadsimg("baubles/inputArea"), null, map.isAreaSelectionMode));
        boolean cancelled = waitPick(c, map.isAreaSelectionMode, prompt, timeout, "the player to select an area");
        NArea.Space space = map.areaSpace;
        map.areaSpace = null;
        if (space == null || cancelled) throw new ApiError(Errors.CANCELLED, "no area was selected");
        Pair<Coord2d, Coord2d> r = toRect(c, space);
        if (r == null) throw ApiError.failed("the selected area is not on loaded map grids");
        return r;
    }

    /**
     * Shows the pick prompt and waits until the map leaves the selection {@code mode} (picked or
     * cancelled); always resets the mode and closes the prompt. Returns true if the player cancelled.
     */
    private static boolean waitPick(OpContext c, AtomicBoolean mode, String prompt, double timeout, String what) throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ScriptPrompt> ref = new AtomicReference<>();
        Runnable undo = () -> {
            mode.set(false);
            ScriptPrompt p = ref.get();
            if (p != null) p.reqdestroy();
        };
        c.run.addCleanup(undo);
        ScriptRuntime.onUiThread(c.ui, () -> {
            ScriptPrompt p = new ScriptPrompt(c.run.title, prompt, () -> {
                cancelled.set(true);
                mode.set(false);
            });
            ref.set(p);
            ScriptPrompt.show(c.gui, p);
        });
        try {
            c.waitUntil(() -> !mode.get(), timeout, what);
        } finally {
            c.run.removeCleanup(undo);
            ScriptRuntime.onUiThread(c.ui, undo);
        }
        return cancelled.get();
    }

    /** World rectangle of a selection, as {@code SelectArea.getRCArea} computes it but against this run's map. */
    private static Pair<Coord2d, Coord2d> toRect(OpContext c, NArea.Space space) {
        Coord begin = null, end = null;
        for (java.util.Map.Entry<Long, NArea.VArea> e : space.space.entrySet()) {
            MCache.Grid grid = c.gui.map.glob.map.findGrid(e.getKey());
            if (grid == null) return null;
            Area area = e.getValue().area;
            Coord b = area.ul.add(grid.ul), en = area.br.add(grid.ul);
            begin = (begin == null) ? b : Coord.of(Math.min(begin.x, b.x), Math.min(begin.y, b.y));
            end = (end == null) ? en : Coord.of(Math.max(end.x, en.x), Math.max(end.y, en.y));
        }
        if (begin == null) return null;
        return new Pair<>(begin.mul(MCache.tilesz), end.sub(1, 1).mul(MCache.tilesz).add(MCache.tilesz));
    }

    private FormOps() {}
}
