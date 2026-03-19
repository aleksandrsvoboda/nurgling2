package nurgling;

import haven.*;
import java.awt.image.BufferedImage;

public class NCloseButton extends IButton {
    public NCloseButton(BufferedImage up, BufferedImage down, BufferedImage hover) {
        super(up, down, hover);
    }

    @Override
    public boolean checkhit(Coord c) {
        return c.isect(Coord.z, sz);
    }
}
