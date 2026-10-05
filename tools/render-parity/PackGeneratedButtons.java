import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Crop sprite cells from ImageGen masters; does not redraw or replace their artwork. */
public class PackGeneratedButtons {
    static final Path OUT = Paths.get("src/nurgling/styles/assets/buttons");
    static void save(BufferedImage src, int x, int y, int w, int h, String name) throws Exception {
        ImageIO.write(src.getSubimage(x, y, w, h), "png", OUT.resolve(name + ".png").toFile());
        System.out.println(name + ": " + x + "," + y + " " + w + "x" + h);
    }
    static void cells(String file, int cols, int rows, String[] names) throws Exception {
        BufferedImage src = ImageIO.read(Paths.get("art/ui/imagegen", file).toFile());
        for(int i = 0; i < names.length; i++) {
            int x0 = src.getWidth() * (i % cols) / cols, x1 = src.getWidth() * ((i % cols) + 1) / cols;
            int y0 = src.getHeight() * (i / cols) / rows, y1 = src.getHeight() * ((i / cols) + 1) / rows;
            int left = x1, top = y1, right = x0, bottom = y0;
            for(int y = y0; y < y1; y++) for(int x = x0; x < x1; x++) {
                if((src.getRGB(x, y) >>> 24) >= 128) {
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
            if(right <= left || bottom <= top) throw new IllegalStateException(names[i]);
            save(src, left, top, right - left + 1, bottom - top + 1, names[i]);
        }
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        if(args.length == 1 && args[0].equals("--flow-canvases")) {
            cells("flow-new-canvas-master.png",1,1,new String[]{"flow-new-canvas"});
            cells("flow-rename-canvas-master.png",1,1,new String[]{"flow-rename-canvas"});
            return;
        }
        if(args.length == 1 && args[0].equals("--calculator")) {
            cells("quality-calculator-master.png",1,1,new String[]{"quality-calculator"});
            return;
        }
        if(args.length == 1 && args[0].equals("--flow-history")) {
            cells("flow-history-atlas.png", 2, 1, new String[]{"flow-undo", "flow-redo"});
            return;
        }
        if(args.length > 0 && args[0].equals("storage")) {
            cells("storage-items.png", 1, 1, new String[]{"storage-items"});
            return;
        }
        if(args.length > 0 && args[0].equals("maps")) {
            for(String key : new String[]{"maptools", "fish", "tree", "ores", "gems", "stone", "forage", "vector", "home", "mark", "hmark", "wnd", "prov"})
                cells("map-" + key + ".png", 1, 1, new String[]{"map-" + key});
            return;
        }
        for(String key : new String[]{"area-folder", "area-add", "area-categories", "area-import", "area-export"})
            cells(key + ".png", 1, 1, new String[]{key});
        if(args.length > 0 && args[0].equals("areas")) return;
        Files.copy(Paths.get("art/ui/imagegen/resize-corner.png"), OUT.resolve("resize-corner.png"), StandardCopyOption.REPLACE_EXISTING);
        // Remove the generated frames; NCal draws one shared pixel-exact border.
        for(String key : new String[]{"rain", "wolf", "dawn", "mantle"}) {
            BufferedImage src = ImageIO.read(Paths.get("art/ui/imagegen/calendar-" + key + "-v2.png").toFile());
            int inset = 64;
            save(src, inset, inset, src.getWidth() - 2 * inset, src.getHeight() - 2 * inset, "calendar-" + key);
        }
        if(args.length > 0 && args[0].equals("calendar")) return;
        cells("visibility-atlas.png", 2, 1, new String[]{"eye-closed", "eye-open"});
        cells("meter-main-atlas.png", 2, 2, new String[]{"meter-hp", "meter-stam", "meter-nrj", "meter-mount"});
        cells("meter-extra-atlas.png", 2, 2, new String[]{"meter-hast", "meter-boat", "meter-water"});
        BufferedImage speed = ImageIO.read(Paths.get("art/ui/imagegen/speed-atlas.png").toFile());
        int[] speedX = {85, 452, 819, 1186}, speedY = {71, 382, 696};
        String[] speedStates = {"off", "on", "dis"};
        for(int row = 0; row < 3; row++) for(int col = 0; col < 4; col++)
            save(speed, speedX[col], speedY[row], 265, row == 1 ? 257 : 255,
                "speed-" + col + "-" + speedStates[row]);
        cells("disclosure-atlas.png", 2, 1, new String[]{"triangle-right", "triangle-down"});
        cells("lock-atlas.png", 2, 1, new String[]{"unlock", "lock"});
        cells("step-atlas.png", 2, 1, new String[]{"plus", "minus"});
        cells("toolbar-atlas.png", 4, 4, new String[]{"group", "npc", "credo", "world", "search", "settings", "check", "close", "left", "right", "up", "down", "trash", "grid", "sort", "expand"});
        BufferedImage skin = ImageIO.read(Paths.get("art/ui/imagegen/button-atlas.png").toFile());
        if(skin.getWidth() != 1536 || skin.getHeight() != 1024) throw new IllegalStateException("Unexpected skin master dimensions");
        String[] names = {"normal", "hover", "pressed", "selected", "disabled", "alternate"};
        int[] xs = {40, 540, 1040};
        for(int i = 0; i < names.length; i++) save(skin, xs[i % 3], i < 3 ? 233 : 591, 456, i < 3 ? 163 : 160, names[i]);
    }
}
