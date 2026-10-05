package nurgling.craft;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.regex.*;
import org.scilab.forge.jlatexmath.*;

/** Local TeX math layout; wiki text is restricted to mathematical commands, never file/macro operations. */
public final class FormulaImages {
    private FormulaImages() {}
    private static final Set<String> COMMANDS = Set.of("frac", "sqrt", "over", "operatorname", "mathbf", "mathrm", "mathit",
        "text", "Big", "big", "left", "right", "cdot", "times", "div", "pm", "mp", "le", "ge", "leq", "geq",
        "neq", "approx", "sum", "prod", "min", "max", "log", "ln", "exp", "pi", "alpha", "beta", "gamma", "infty");
    private static final Pattern COMMAND = Pattern.compile("\\\\([A-Za-z]+)");
    private static final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> entry) { return size() > 64; }
    };
    public static synchronized BufferedImage render(String source, int pointSize, int maxWidth) {
        if(source.length() > 4096) throw new IllegalArgumentException("Formula too long");
        Matcher command = COMMAND.matcher(source);
        while(command.find()) if(!COMMANDS.contains(command.group(1)))
            throw new IllegalArgumentException("Unsupported math command: " + command.group(1));
        int depth = 0;
        for(char c : source.toCharArray()) {
            if(c == '{' && ++depth > 40) throw new IllegalArgumentException("Formula too deep");
            if(c == '}') depth--;
        }
        String key = pointSize + "/" + maxWidth + "/" + source;
        BufferedImage cached = cache.get(key);
        if(cached != null) return cached;
        TeXIcon icon = new TeXFormula(source).createTeXIcon(TeXConstants.STYLE_DISPLAY, pointSize);
        icon.setForeground(new Color(225, 231, 226));
        icon.setInsets(new Insets(3, 3, 3, 3));
        int w = icon.getIconWidth(), h = icon.getIconHeight();
        if(w > 8192 || h > 2048 || w <= 0 || h <= 0) throw new IllegalArgumentException("Formula too large");
        double scale = Math.min(1.0, Math.max(32, maxWidth) / (double)w);
        BufferedImage image = new BufferedImage(Math.max(1, (int)Math.ceil(w * scale)), Math.max(1, (int)Math.ceil(h * scale)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(scale, scale);
            icon.paintIcon(null, g, 0, 0);
        } finally { g.dispose(); }
        cache.put(key, image);
        return image;
    }
}
