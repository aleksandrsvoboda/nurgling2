package nurgling.styles;

import haven.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** ImageGen artwork. Only sprite extraction, scaling and state composition happen in code. */
public final class GeneratedButtons {
    private GeneratedButtons() {}
    public enum State { NORMAL, HOVER, PRESSED, SELECTED, DISABLED }
    private static final Map<String, BufferedImage> sources = new HashMap<>();
    private static final Map<String, Tex> textures = new LinkedHashMap<>();

    private static synchronized BufferedImage source(String name) {
        BufferedImage found = sources.get(name);
        if(found != null) return found;
        try(InputStream in = GeneratedButtons.class.getResourceAsStream("assets/buttons/" + name + ".png")) {
            if(in == null) throw new IOException("Missing generated UI sprite: " + name);
            found = ImageIO.read(in);
            if(found == null) throw new IOException("Invalid generated UI sprite: " + name);
            sources.put(name, found);
            return found;
        } catch(IOException e) { throw new RuntimeException(e); }
    }

    public static State state(boolean hover, boolean pressed, boolean selected, boolean disabled) {
        return disabled ? State.DISABLED : pressed ? State.PRESSED : selected ? State.SELECTED : hover ? State.HOVER : State.NORMAL;
    }

    public static void plate(Graphics2D g, int w, int h, State state) {
        plate(g, w, h, state, false);
    }

    private static void plate(Graphics2D g, int w, int h, State state, boolean frameOnly) {
        if(w <= 0 || h <= 0) return;
        BufferedImage src = source(state.name().toLowerCase(Locale.ROOT));
        int sw = src.getWidth(), sh = src.getHeight(), slice = 12;
        int edge = Math.min(Math.min(w, h) / 2, Math.max(1, UI.scale(2)));
        int[] sx = {0, slice, sw - slice, sw}, sy = {0, slice, sh - slice, sh};
        int[] dx = {0, edge, w - edge, w}, dy = {0, edge, h - edge, h};
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for(int y = 0; y < 3; y++) for(int x = 0; x < 3; x++) {
            if(frameOnly && x == 1 && y == 1) continue;
            g.drawImage(src, dx[x], dy[y], dx[x+1], dy[y+1], sx[x], sy[y], sx[x+1], sy[y+1], null);
        }
    }

    private static synchronized Tex texture(String key, java.util.function.Supplier<BufferedImage> make) {
        Tex tex = textures.get(key);
        if(tex == null) {
            tex = new TexI(make.get()); textures.put(key, tex);
            if(textures.size() > 256) {
                Iterator<Tex> it = textures.values().iterator();
                Tex old = it.next(); it.remove(); old.dispose();
            }
        }
        return tex;
    }

    public static void plate(GOut g, Coord pos, Coord size, State state) {
        if(size.x <= 0 || size.y <= 0) return;
        Tex tex = texture("plate/" + size + "/" + state, () -> {
            BufferedImage img = new BufferedImage(size.x, size.y, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D cg = img.createGraphics(); plate(cg, size.x, size.y, state); cg.dispose(); return img;
        });
        g.image(tex, pos);
    }

    public static void frame(GOut g, Coord pos, Coord size, State state) {
        if(size.x <= 0 || size.y <= 0) return;
        g.image(texture("frame/" + size + "/" + state, () -> {
            BufferedImage img = new BufferedImage(size.x, size.y, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D cg = img.createGraphics();
            plate(cg, size.x, size.y, state, true);
            cg.dispose();
            return img;
        }), pos);
    }

    public static BufferedImage iconImage(String name, int side) {
        BufferedImage src = source(name);
        int max = Math.max(src.getWidth(), src.getHeight());
        Coord target = new Coord(Math.max(1, src.getWidth() * side / max), Math.max(1, src.getHeight() * side / max));
        BufferedImage scaled = PUtils.uiscale(src, target);
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = square.createGraphics();
        g.drawImage(scaled, (side - target.x) / 2, (side - target.y) / 2, null);
        if(name.equals("close")) {
            g.setComposite(AlphaComposite.SrcIn);
            g.setColor(UITheme.ACCENT);
            g.fillRect(0, 0, side, side);
        }
        g.dispose();
        return square;
    }

    public static BufferedImage squareButtonImage(String name, int side) {
        return PUtils.uiscale(source(name), new Coord(side, side));
    }

    public static void icon(GOut g, String name, Coord pos, int side) {
        g.image(texture("icon/" + name + "/" + side, () -> iconImage(name, side)), pos);
    }

    /** One shared orange close/delete glyph; side is the scaled 16/32/64px size. */
    public static void close(GOut g, Coord pos, int side) {
        icon(g, "close", pos, side);
    }

    public static void mutedIcon(GOut g, String name, Coord pos, int side) {
        g.image(texture("muted/" + name + "/" + side, () -> {
            BufferedImage img = iconImage(name, side);
            Graphics2D cg = img.createGraphics();
            cg.setComposite(AlphaComposite.SrcIn);
            cg.setColor(UITheme.DISABLED);
            cg.fillRect(0, 0, side, side);
            cg.dispose();
            return img;
        }), pos);
    }
}
