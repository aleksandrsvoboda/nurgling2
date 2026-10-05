package nurgling.widgets;

import haven.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Antialiased native canvas shapes. Side-directed cubic edges follow the React Flow
 * getBezierPath API concept: https://reactflow.dev/api-reference/utils/get-bezier-path
 * Geometry and rendering here are independent Java2D implementations, with bounded raster caching. */
final class FlowPainter {
    private final LinkedHashMap<String, Tex> cache = new LinkedHashMap<>(64, .75f, true);
    private long pixels;
    void panel(GOut out, Coord at, Coord size, Color fill, Color border, double scale, Coord viewport) {
        double stroke = Math.max(1, scale), r = Math.max(5, 7 * scale);
        Shape shape = new RoundRectangle2D.Double(at.x + stroke / 2, at.y + stroke / 2,
            Math.max(1, size.x - stroke), Math.max(1, size.y - stroke), r * 2, r * 2);
        paint(out, shape, fill, border, (float)stroke, viewport, "p:" + size + ":" + scale);
    }
    void curve(GOut out, Coord a, Coord aSide, Coord b, Coord bSide, Color color, double scale, Coord viewport) {
        double distance = Math.hypot(b.x - a.x, b.y - a.y);
        double lead = Math.max(24 * scale, Math.min(180 * scale, distance * .45));
        Shape shape = new CubicCurve2D.Double(a.x, a.y, a.x + aSide.x * lead, a.y + aSide.y * lead,
            b.x + bSide.x * lead, b.y + bSide.y * lead, b.x, b.y);
        paint(out, shape, null, color, (float)Math.max(1.2, 1.6 * scale), viewport,
            "c:" + b.sub(a) + ":" + aSide + ":" + bSide + ":" + scale);
    }
    /** Backward/stacked links use a rounded corridor outside both endpoint cards. */
    void corridor(GOut out, Coord a, Coord b, int lane, Color color, double scale, Coord viewport) {
        double lead = 34 * scale, radius = 12 * scale;
        Point2D.Double[] points = {new Point2D.Double(a.x,a.y), new Point2D.Double(a.x+lead,a.y),
            new Point2D.Double(a.x+lead,lane),new Point2D.Double(b.x-lead,lane),
            new Point2D.Double(b.x-lead,b.y),new Point2D.Double(b.x,b.y)};
        Path2D path = new Path2D.Double(); path.moveTo(a.x,a.y);
        for(int i=1;i<points.length-1;i++) {
            Point2D.Double p=points[i], prev=points[i-1], next=points[i+1];
            double in=p.distance(prev), after=p.distance(next), r=Math.min(radius,Math.min(in,after)/2);
            if(in==0 || after==0) { path.lineTo(p.x,p.y); continue; }
            path.lineTo(p.x+(prev.x-p.x)*r/in,p.y+(prev.y-p.y)*r/in);
            path.quadTo(p.x,p.y,p.x+(next.x-p.x)*r/after,p.y+(next.y-p.y)*r/after);
        }
        path.lineTo(b.x,b.y);
        paint(out,path,null,color,(float)Math.max(1.2,1.6*scale),viewport,"r:"+b.sub(a)+":"+(lane-a.y)+":"+scale);
    }
    private void paint(GOut out, Shape shape, Color fill, Color border, float stroke, Coord viewport, String geometry) {
        Rectangle bounds = shape.getBounds(); bounds.grow((int)Math.ceil(stroke) + 2, (int)Math.ceil(stroke) + 2);
        Rectangle visible = bounds.intersection(new Rectangle(0, 0, viewport.x, viewport.y));
        if(visible.isEmpty()) return;
        String key = geometry + ":" + (fill == null ? 0 : fill.getRGB()) + ":" + border.getRGB()
            + ":" + (visible.x - bounds.x) + ":" + (visible.y - bounds.y) + ":" + visible.width + ":" + visible.height;
        Tex texture = cache.get(key);
        if(texture == null) {
            BufferedImage image = new BufferedImage(visible.width, visible.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(-visible.x, -visible.y);
                if(fill != null) { g.setColor(fill); g.fill(shape); }
                g.setColor(border); g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(shape);
            } finally { g.dispose(); }
            texture = new TexI(image); cache.put(key, texture); pixels += (long)visible.width * visible.height;
            while(cache.size() > 1 && (cache.size() > 96 || pixels > 4_000_000)) {
                Iterator<Tex> it = cache.values().iterator(); Tex old = it.next(); it.remove();
                pixels -= (long)old.sz().x * old.sz().y; old.dispose();
            }
        }
        out.image(texture, new Coord(visible.x, visible.y));
    }
    void dispose() { cache.values().forEach(Tex::dispose); cache.clear(); pixels = 0; }
}
