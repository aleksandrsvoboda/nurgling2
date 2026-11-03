package nurgling.scripting.ui;

import haven.*;
import nurgling.NMapView;
import nurgling.areas.NArea;
import nurgling.i18n.L10n;
import nurgling.overlays.NCustomBauble;
import nurgling.scripting.api.ApiError;
import nurgling.widgets.AreaPicker;
import nurgling.widgets.NDropbox;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A settings form a script asks for: one control per field, built from a declarative spec, in the
 * same building blocks as the client's own bot windows (labels, text entries, check boxes,
 * {@link NDropbox}, {@link AreaPicker} with "Select on map...").
 *
 * Field spec: {name, type: int|float|bool|str|choice|area|gob|label, label, default, values, required}.
 * The form is created and read on the UI thread; the bot thread only polls {@link #finished()}.
 */
public class ScriptForm extends Window {
    /** Area choice meaning "let me drag a rectangle on the map after OK" (same as the Area Mover's). */
    public static final int SELECT_ON_MAP = nurgling.widgets.bots.AreaMoverWnd.SELECT_ON_MAP;
    public static final String[] TYPES = {"int", "float", "bool", "str", "choice", "area", "gob", "label"};

    private static final int W = UI.scale(240);
    private static final Color ERROR = new Color(255, 110, 90);

    private final List<Field> fields = new ArrayList<>();
    private final NMapView map;
    private final Label error;
    private final boolean hasCancel;
    private GobField picking = null;
    private volatile int state = 0; // 0 open, 1 confirmed, 2 cancelled
    private volatile JSONObject values = null;

    /**
     * @param okText     text of the confirm button
     * @param cancelText text of the cancel button, or null for a form with a single button
     */
    public ScriptForm(String title, JSONArray specs, NMapView map, String okText, String cancelText) {
        super(Coord.z, title);
        this.map = map;
        this.hasCancel = cancelText != null;
        int y = 0;
        for (int i = 0; i < specs.length(); i++) {
            Field f = build(specs.getJSONObject(i));
            y = f.layout(this, y) + UI.scale(8);
            fields.add(f);
        }
        error = add(new Label(""), Coord.of(0, y));
        error.setcolor(ERROR);
        y += error.sz.y + UI.scale(4);
        Widget ok = add(new Button(UI.scale(80), okText) {
            @Override
            public void click() {
                super.click();
                confirm();
            }
        }, Coord.of(0, y));
        if (cancelText != null) {
            add(new Button(UI.scale(80), cancelText) {
                @Override
                public void click() {
                    super.click();
                    cancel();
                }
            }, ok.pos("ur").adds(10, 0));
        }
        pack();
    }

    /** Checks a field list before anything is shown; throws BadArgs for a malformed spec. */
    public static void checkSpecs(JSONArray specs) {
        if (specs == null) throw ApiError.badArgs("'fields' must be a list");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < specs.length(); i++) {
            Object o = specs.get(i);
            if (!(o instanceof JSONObject)) throw ApiError.badArgs("field " + i + " must be an object");
            JSONObject f = (JSONObject) o;
            String type = f.optString("type", "str");
            boolean known = false;
            for (String t : TYPES) if (t.equals(type)) known = true;
            if (!known) throw ApiError.badArgs("field " + i + ": unknown type '" + type + "'");
            if (type.equals("label")) {
                if (!f.has("label") && !f.has("default")) throw ApiError.badArgs("label field " + i + " needs 'label'");
                continue;
            }
            String name = f.optString("name", "");
            if (name.isEmpty()) throw ApiError.badArgs("field " + i + " needs a 'name'");
            if (!names.add(name)) throw ApiError.badArgs("duplicate field name '" + name + "'");
            if (type.equals("choice")) {
                JSONArray v = f.optJSONArray("values");
                if (v == null || v.length() == 0) throw ApiError.badArgs("choice field '" + name + "' needs a non-empty 'values' list");
            }
            if (f.has("default") && !f.isNull("default")) {
                Object d = f.get("default");
                if ((type.equals("int") || type.equals("float")) && !(d instanceof Number))
                    throw ApiError.badArgs("default of '" + name + "' must be a number");
                if (type.equals("bool") && !(d instanceof Boolean))
                    throw ApiError.badArgs("default of '" + name + "' must be a bool");
            }
        }
    }

    public boolean finished() {
        return state != 0;
    }

    public boolean cancelled() {
        return state == 2;
    }

    /** Values by field name once confirmed; area fields left on "Select on map..." are absent (see {@link #mapPicks()}). */
    public JSONObject values() {
        return values;
    }

    /** Area fields the player left on "Select on map...", in form order: name to label. */
    public List<String[]> mapPicks() {
        List<String[]> res = new ArrayList<>();
        for (Field f : fields) {
            if (f instanceof AreaField && ((AreaField) f).allowMap && ((AreaField) f).onMap())
                res.add(new String[]{f.name, f.label});
        }
        return res;
    }

    private void confirm() {
        JSONObject v = new JSONObject();
        for (Field f : fields) {
            if (f.name == null) continue;
            String err = f.check();
            if (err != null) {
                error.settext(err);
                pack();
                return;
            }
            Object val = f.value();
            if (val != null) v.put(f.name, val);
            else if (!(f instanceof AreaField)) v.put(f.name, JSONObject.NULL);
        }
        values = v;
        state = 1;
        stopPicking();
        reqdestroy();
    }

    private void cancel() {
        if (state == 0) state = 2;
        stopPicking();
        reqdestroy();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            if (hasCancel) cancel();
            else confirm();
        } else {
            super.wdgmsg(msg, args);
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (picking != null && map != null && !map.isGobSelectionMode.get()) {
            Gob g = map.selectedGob;
            map.selectedGob = null;
            if (g != null) picking.set(g);
            picking = null;
        }
    }

    @Override
    public void destroy() {
        stopPicking();
        if (state == 0) state = 2;
        super.destroy();
    }

    private void stopPicking() {
        if (picking != null && map != null) {
            map.isGobSelectionMode.set(false);
            map.selectedGob = null;
        }
        picking = null;
    }

    private void startPicking(GobField f) {
        if (map == null) return;
        if (map.isGobSelectionMode.get() || map.isAreaSelectionMode.get()) {
            error.settext("Finish the selection already in progress first.");
            pack();
            return;
        }
        error.settext("");
        map.selectedGob = null;
        map.isGobSelectionMode.set(true);
        picking = f;
        Gob player = map.player();
        if (player != null)
            player.addcustomol(new NCustomBauble(player, Resource.loadsimg("baubles/selectobject"), map.isGobSelectionMode));
    }

    private Field build(JSONObject s) {
        String type = s.optString("type", "str");
        String name = s.optString("name", null);
        String label = s.optString("label", name == null ? "" : name);
        boolean req = s.optBoolean("required", false);
        Object def = s.has("default") && !s.isNull("default") ? s.get("default") : null;
        switch (type) {
            case "int":
            case "float":
                return new NumberField(name, label, req, def, type.equals("int"));
            case "bool":
                return new BoolField(name, label, def instanceof Boolean && (Boolean) def);
            case "choice":
                return new ChoiceField(name, label, req, def, s.getJSONArray("values"));
            case "area":
                return new AreaField(name, label, req, def, s.optBoolean("map", true));
            case "gob":
                return new GobField(name, label, req, def);
            case "label":
                return new TextField(null, s.has("label") ? label : String.valueOf(def), false, null, true);
            default:
                return new TextField(name, label, req, def, false);
        }
    }

    private abstract static class Field {
        final String name, label;
        final boolean required;

        Field(String name, String label, boolean required) {
            this.name = name;
            this.label = label;
            this.required = required;
        }

        /** Adds the controls at {@code y}; returns the bottom edge. */
        abstract int layout(Widget parent, int y);

        /** Error text, or null when the value is acceptable. */
        abstract String check();

        abstract Object value();

        int caption(Widget parent, int y) {
            Label l = parent.add(new Label(required ? label + " *" : label), Coord.of(0, y));
            return y + l.sz.y + UI.scale(2);
        }
    }

    private static class TextField extends Field {
        final boolean plain;
        final String def;
        TextEntry entry;

        TextField(String name, String label, boolean required, Object def, boolean plain) {
            super(name, label, required);
            this.plain = plain;
            this.def = def == null ? "" : String.valueOf(def);
        }

        int layout(Widget parent, int y) {
            if (plain) {
                Label l = parent.add(new Label(label, W), Coord.of(0, y));
                return y + l.sz.y;
            }
            y = caption(parent, y);
            entry = parent.add(new TextEntry(W, def), Coord.of(0, y));
            return y + entry.sz.y;
        }

        String check() {
            if (entry != null && required && entry.text().trim().isEmpty()) return "'" + label + "' is required.";
            return null;
        }

        Object value() {
            return entry == null ? null : entry.text();
        }
    }

    private static class NumberField extends Field {
        final boolean integer;
        final Object def;
        TextEntry entry;

        NumberField(String name, String label, boolean required, Object def, boolean integer) {
            super(name, label, required);
            this.integer = integer;
            this.def = def;
        }

        int layout(Widget parent, int y) {
            y = caption(parent, y);
            String t = "";
            if (def instanceof Number) t = integer ? String.valueOf(((Number) def).longValue()) : String.valueOf(((Number) def).doubleValue());
            entry = parent.add(new TextEntry(UI.scale(100), t), Coord.of(0, y));
            return y + entry.sz.y;
        }

        String check() {
            String t = entry.text().trim();
            if (t.isEmpty()) return required ? "'" + label + "' is required." : null;
            try {
                if (integer) Long.parseLong(t);
                else Double.parseDouble(t);
                return null;
            } catch (NumberFormatException e) {
                return "'" + label + "' must be " + (integer ? "a whole number." : "a number.");
            }
        }

        Object value() {
            String t = entry.text().trim();
            if (t.isEmpty()) return null;
            return integer ? (Object) Long.parseLong(t) : (Object) Double.parseDouble(t);
        }
    }

    private static class BoolField extends Field {
        final boolean def;
        CheckBox box;

        BoolField(String name, String label, boolean def) {
            super(name, label, false);
            this.def = def;
        }

        int layout(Widget parent, int y) {
            box = parent.add(new CheckBox(label), Coord.of(0, y));
            box.a = def;
            return y + box.sz.y;
        }

        String check() {
            return null;
        }

        Object value() {
            return box.a;
        }
    }

    private static class ChoiceField extends Field {
        final List<Object> values = new ArrayList<>();
        final Object def;
        NDropbox<Object> box;

        ChoiceField(String name, String label, boolean required, Object def, JSONArray vals) {
            super(name, label, required);
            for (int i = 0; i < vals.length(); i++) values.add(vals.get(i));
            this.def = def;
        }

        int layout(Widget parent, int y) {
            y = caption(parent, y);
            box = parent.add(new NDropbox<Object>(W, Math.min(values.size(), 10), UI.scale(22)) {
                @Override
                protected Object listitem(int i) { return values.get(i); }
                @Override
                protected int listitems() { return values.size(); }
                @Override
                protected void drawitem(GOut g, Object item, int i) {
                    g.text(String.valueOf(item), Coord.z);
                }
            }, Coord.of(0, y));
            Object initial = values.get(0);
            for (Object v : values) {
                if (def != null && String.valueOf(v).equals(String.valueOf(def))) initial = v;
            }
            box.change(initial);
            return y + box.sz.y;
        }

        String check() {
            return box.sel == null && required ? "'" + label + "' is required." : null;
        }

        Object value() {
            return box.sel;
        }
    }

    private class AreaField extends Field {
        final Object def;
        /** False when only a saved area will do (script start parameters are area ids). */
        final boolean allowMap;
        AreaPicker picker;

        AreaField(String name, String label, boolean required, Object def, boolean allowMap) {
            super(name, label, required);
            this.def = def;
            this.allowMap = allowMap;
        }

        int layout(Widget parent, int y) {
            y = caption(parent, y);
            List<AreaPicker.Entry> choices = new ArrayList<>();
            choices.add(new AreaPicker.Entry(SELECT_ON_MAP, L10n.get("areamover.select_on_map"), true));
            int selected = SELECT_ON_MAP;
            if (map != null) {
                List<NArea> areas = new ArrayList<>(map.glob.map.areas.values());
                areas.sort(Comparator.comparing(a -> a.name));
                for (NArea a : areas) {
                    choices.add(new AreaPicker.Entry(a.id, a.name + " [ID " + a.id + "]", false));
                    if ((def instanceof Number && ((Number) def).intValue() == a.id) || (def instanceof String && def.equals(a.name)))
                        selected = a.id;
                }
            }
            picker = parent.add(new AreaPicker(W, choices, selected, null), Coord.of(0, y));
            return y + picker.sz.y;
        }

        boolean onMap() {
            Integer id = picker.selectedId();
            return id == null || id == SELECT_ON_MAP;
        }

        String check() {
            if (!allowMap && required && onMap())
                return label + ": choose a saved area";
            return null;
        }

        Object value() {
            return onMap() ? null : picker.selectedId();
        }
    }

    private class GobField extends Field {
        long gobId = 0;
        boolean has = false;
        Label shown;

        GobField(String name, String label, boolean required, Object def) {
            super(name, label, required);
            long id = 0;
            if (def instanceof Number) id = ((Number) def).longValue();
            else if (def instanceof JSONObject && ((JSONObject) def).has("gob")) id = ((JSONObject) def).optLong("gob");
            Gob g = (id != 0 && map != null) ? map.glob.oc.getgob(id) : null;
            if (g != null) {
                gobId = g.id;
                has = true;
            }
        }

        int layout(Widget parent, int y) {
            y = caption(parent, y);
            Button b = parent.add(new Button(UI.scale(110), L10n.get("areamover.select_on_map")) {
                @Override
                public void click() {
                    super.click();
                    startPicking(GobField.this);
                }
            }, Coord.of(0, y));
            shown = parent.add(new Label(has ? describe(map.glob.oc.getgob(gobId), gobId) : L10n.get("common.none")),
                    b.pos("ur").adds(8, 4));
            return y + b.sz.y;
        }

        void set(Gob g) {
            gobId = g.id;
            has = true;
            shown.settext(describe(g, g.id));
            error.settext("");
            ScriptForm.this.pack();
        }

        String check() {
            return required && !has ? "Pick an object for '" + label + "'." : null;
        }

        Object value() {
            return has ? (Object) gobId : null;
        }
    }

    private static String describe(Gob g, long id) {
        String nm = (g != null && g.ngob != null) ? g.ngob.name : null;
        if (nm == null) return "#" + id;
        return nm.substring(nm.lastIndexOf('/') + 1) + " #" + id;
    }
}
