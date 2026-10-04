package nurgling.styles;

import haven.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Shared meter chrome with isolated illustrated badges and translucent fill shading. */
public final class MeterStyle {
    private MeterStyle() {}

    private static final Text.Furnace text = new PUtils.BlurFurn(
        new Text.Foundry(UIFont.regular, 11, Color.WHITE).aa(true), UI.scale(2), UI.scale(1), Color.BLACK);

    /** Login hint typography with white numbers for contrast against the coloured bars. */
    public static Tex renderText(String value) {
        return text.render(value).tex();
    }
    private static final Map<String, Tex> badges = new HashMap<>();
    private static final Map<Tex, Color> shades = new IdentityHashMap<>();

    private static synchronized Color originalShade(Tex original) {
        return shades.computeIfAbsent(original, key -> {
            if(!(key instanceof TexI)) return new Color(0, 0, 0, 0);
            BufferedImage image = ((TexI)key).back;
            return new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2), true);
        });
    }

    private static synchronized Tex badge(String name) {
        return badges.computeIfAbsent(name, key -> {
            try(InputStream in = MeterStyle.class.getResourceAsStream("assets/buttons/meter-" + key + ".png")) {
                if(in == null) throw new IOException("Missing meter badge: " + key);
                BufferedImage source = ImageIO.read(in);
                int width = UI.scale(key.equals("nrj") ? 38 : 30), height = UI.scale(30);
                double scale = Math.min((double)width / source.getWidth(), (double)height / source.getHeight());
                Coord size = new Coord(Math.max(1, (int)Math.round(source.getWidth() * scale)),
                                       Math.max(1, (int)Math.round(source.getHeight() * scale)));
                return new TexI(PUtils.uiscale(source, size));
            } catch(IOException e) { throw new RuntimeException(e); }
        });
    }

    public static void background(GOut g, Color color) {
        UITheme.panel(g, IMeter.off, IMeter.msz, color, null);
    }

    public static void finish(GOut g, Tex original, String resource, Tex text) {
        Coord off = IMeter.off, size = IMeter.msz;
        int edge = Math.max(1, UI.scale(2));
        // Each original meter has its own overlay alpha; preserve it over the server colours.
        UITheme.panel(g, off, size, originalShade(original), null);
        // The draggable host limits content to 155 logical pixels. Keep the right border
        // inside the bar's right edge instead of extending into the host's clipped margin.
        GeneratedButtons.frame(g, off.sub(edge, edge), size.add(edge, edge * 2), GeneratedButtons.State.SELECTED);
        String key = resource.substring(resource.lastIndexOf('/') + 1);
        switch(key) {
        case "hp": case "stam": case "nrj": case "mount": case "hast": case "boat": case "water":
            Tex icon = badge(key);
            g.image(icon, new Coord(0, (UI.scale(30) - icon.sz().y) / 2));
            break;
        default:
            g.image(original, Coord.z, Coord.z, new Coord(UI.scale(30), original.sz().y));
        }
        if(text != null)
            g.image(text, off.add(size.sub(text.sz()).div(2)));
    }
}
