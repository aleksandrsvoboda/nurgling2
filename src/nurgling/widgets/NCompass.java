package nurgling.widgets;

import haven.*;
import haven.res.ui.locptr.Pointer;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.navigation.CompassBearing;
import nurgling.styles.UITheme;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

/** Session-local compass. The server's pointer widgets remain the source of truth. */
public class NCompass extends Widget {
    private final NGameUI gui;
    private final Set<Pointer> pointers = new LinkedHashSet<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Tex> text = new LinkedHashMap<>();
    private final Text.Foundry font = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 12).aa(true);
    private final Text.Foundry directions = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 14).aa(true);
    private Tex shade;
    private Pointer selected;
    private Coord mouse = new Coord(-1, -1);
    private boolean enabled = Utils.getprefb("navigation-compass", true);

    private static class Target {
        Pointer pointer;
        double distance;
        int x;
    }
    private static class Group {
        final List<Target> targets = new ArrayList<>();
        Coord at;
        Target shown;
    }

    public NCompass(NGameUI gui) {
        super(UI.scale(520, 78));
        this.gui = gui;
        show(enabled);
    }

    public boolean enabled() { return enabled; }
    public void toggle() {
        enabled = !enabled;
        Utils.setprefb("navigation-compass", enabled);
        show(enabled);
    }
    public void register(Pointer pointer) { pointers.add(pointer); }
    public void unregister(Pointer pointer) {
        pointers.remove(pointer);
        if(selected == pointer) selected = null;
    }
    @Override public void tick(double dt) {
        super.tick(dt);
        boolean preference = Utils.getprefb("navigation-compass", true);
        if(preference != enabled) { enabled = preference; show(enabled && !nurgling.render.Photo.on); }
    }

    @Override public void resize(Coord size) {
        if(shade != null) { shade.dispose(); shade = null; }
        super.resize(size);
    }

    private Tex label(String value) {
        return label(value, false, UITheme.TEXT);
    }
    private Tex label(String value, boolean direction, Color color) {
        String key = direction + "/" + color.getRGB() + "/" + value;
        Tex tex = text.get(key);
        if(tex == null) {
            tex = (direction ? directions : font).renderstroked(value, color, new Color(0, 0, 0, 235)).tex();
            text.put(key, tex);
            if(text.size() > 128) {
                Iterator<Tex> it = text.values().iterator();
                Tex old = it.next(); it.remove(); old.dispose();
            }
        }
        return tex;
    }

    /** Only the heading strip is shaded, fading to clear at every edge. */
    private Tex shade() {
        if(shade == null) {
            int height = UI.scale(33);
            BufferedImage image = new BufferedImage(sz.x, height, BufferedImage.TYPE_INT_ARGB);
            for(int y = 0; y < height; y++) for(int x = 0; x < sz.x; x++) {
                double horizontal = Math.min(1, Math.min(x, sz.x - 1 - x) / (double)UI.scale(55));
                double vertical = Math.sin(Math.PI * (y + .5) / height);
                int alpha = (int)(85 * horizontal * vertical);
                image.setRGB(x, y, (alpha << 24) | 0x0c1214);
            }
            shade = new TexI(image);
        }
        return shade;
    }

    private int x(double bearing, double angle) {
        int pad = UI.scale(20);
        return pad + (int)Math.round(CompassBearing.fraction(bearing, angle) * (sz.x - pad * 2));
    }

    protected Coord2d playerPosition() {
        return gui == null || gui.map == null || gui.map.camera == null || gui.map.player() == null ? null : gui.map.player().rc;
    }
    protected double cameraAngle() { return gui.map.camera.angle(); }

    @Override public void draw(GOut g) {
        groups.clear();
        Coord2d origin = playerPosition();
        if(origin == null) return;
        double angle = cameraAngle();
        g.image(shade(), Coord.z);
        String[] cardinal = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        for(int i = 0; i < 24; i++) {
            int xx = x(i * Math.PI / 12, angle);
            int bottom = UI.scale(i % 3 == 0 ? 30 : 27);
            g.chcolor(0, 0, 0, 210);
            g.line(new Coord(xx, UI.scale(23)), new Coord(xx, bottom), UI.scale(3));
            g.chcolor(i % 3 == 0 ? new Color(238, 240, 229) : new Color(200, 210, 207, 190));
            g.line(new Coord(xx, UI.scale(23)), new Coord(xx, bottom), UI.scale(1));
            g.chcolor();
            if(i % 3 == 0) g.aimage(label(cardinal[i / 3], i % 6 == 0, i == 18 ? UITheme.ACCENT : UITheme.TEXT), new Coord(xx, UI.scale(1)), .5, 0);
        }
        g.chcolor(UITheme.ACCENT);
        g.line(new Coord(sz.x / 2, UI.scale(22)), new Coord(sz.x / 2, UI.scale(33)), UI.scale(2));
        g.chcolor();
        List<Target> targets = new ArrayList<>();
        for(Pointer pointer : pointers) {
            if(pointer.parent == null || !pointer.visible) continue;
            try {
                Coord2d pos = pointer.compassPosition();
                if(pos == null) continue;
                Target target = new Target();
                target.pointer = pointer;
                target.distance = origin.dist(pos) / MCache.tilesz.x;
                target.x = x(Math.atan2(pos.y - origin.y, pos.x - origin.x), angle);
                targets.add(target);
            } catch(Loading ignored) {}
        }
        targets.sort(Comparator.comparingInt(t -> t.x));
        for(Target target : targets) {
            Group group = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if(group == null || target.x - group.at.x >= UI.scale(34)) {
                group = new Group(); group.at = new Coord(target.x, UI.scale(45)); groups.add(group);
            }
            group.targets.add(target);
        }
        for(Group group : groups) {
            group.targets.sort(Comparator.comparingDouble(t -> t.distance));
            group.shown = group.targets.stream().filter(t -> t.pointer == selected).findFirst().orElse(group.targets.get(0));
            Target t = group.shown;
            Coord at = group.at.sub(UI.scale(13, 13));
            UITheme.panel(g, at, UI.scale(26, 26), t.pointer == selected ? new Color(80, 54, 26, 175) : new Color(12, 18, 20, 110),
                          t.pointer == selected ? UITheme.ACCENT : new Color(209, 222, 216, 145));
            try {
                Resource.Image icon = t.pointer.icon == null ? null : t.pointer.icon.get().layer(Resource.imgc);
                if(icon != null) g.image(icon.tex(), at.add(UI.scale(3, 3)), UI.scale(20, 20));
                else g.aimage(label("•"), group.at, .5, .5);
            } catch(Loading ignored) { g.aimage(label("…"), group.at, .5, .5); }
            if(group.targets.size() > 1)
                g.aimage(label("+" + (group.targets.size() - 1)), group.at.add(UI.scale(12, -14)), 1, 0);
        }
        Target focus = groups.stream().flatMap(gr -> gr.targets.stream()).filter(t -> t.pointer == selected).findFirst().orElse(null);
        Group hover = hit(mouse);
        if(hover != null) focus = hover.shown;
        if(!targets.isEmpty()) {
            String footer = focus != null ? focus.pointer.compassLabel() + "  ·  " + distance(focus)
                : L10n.get("compass.targets", targets.size());
            Text.Line fitted = font.ellipsize(footer, sz.x - UI.scale(24));
            g.aimage(label(fitted.text), new Coord(sz.x / 2, UI.scale(61)), .5, 0);
            fitted.dispose();
        }
    }

    private String distance(Target t) {
        return (t.pointer.compassApproximate() ? "≈ " : "") +
            (t.distance >= 1000 ? String.format(Locale.ROOT, "%.1f km", t.distance / 1000) : Math.round(t.distance) + " m");
    }
    private Group hit(Coord c) {
        for(Group group : groups)
            if(c.isect(group.at.sub(UI.scale(16, 16)), UI.scale(32, 32))) return group;
        return null;
    }
    @Override public void mousemove(MouseMoveEvent ev) { mouse = ev.c; super.mousemove(ev); }
    @Override public Object tooltip(Coord c, Widget prev) {
        Group group = hit(c);
        if(group == null) return L10n.get("compass.tip");
        StringBuilder tip = new StringBuilder();
        for(Target t : group.targets) tip.append(t.pointer.compassLabel()).append(" · ").append(distance(t)).append('\n');
        return tip.append(L10n.get("compass.target_tip")).toString();
    }
    @Override public boolean mousedown(MouseDownEvent ev) {
        Group group = hit(ev.c);
        if(group == null) return false;
        selected = group.shown.pointer;
        if(!pointers.contains(selected) || selected.parent == null) return true;
        if(ev.b == 1 || ev.b == 3) {
            selected.compassClick(ev.b);
            return true;
        }
        return false;
    }
    @Override public boolean mousewheel(MouseWheelEvent ev) {
        Group group = hit(ev.c);
        if(group == null || group.targets.size() < 2) return false;
        int idx = group.targets.indexOf(group.shown);
        selected = group.targets.get(Math.floorMod(idx + (ev.a > 0 ? 1 : -1), group.targets.size())).pointer;
        return true;
    }
    @Override public void dispose() {
        if(shade != null) shade.dispose();
        text.values().forEach(Tex::dispose); text.clear(); pointers.clear();
        super.dispose();
    }
}
