package nurgling.tools;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class TileColorsTest {
    @Test
    void averagesAllPixelsNotTheCorner() {
	BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
	img.setRGB(0, 0, 0xff000000);
	img.setRGB(1, 0, 0xffff0000);
	img.setRGB(0, 1, 0xff00ff00);
	img.setRGB(1, 1, 0xff0000ff);
	assertEquals(0xff3f3f3f, TileColors.average(img));
    }

    @Test
    void skipsTransparentPixels() {
	BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
	img.setRGB(0, 0, 0x00ffffff);
	img.setRGB(1, 0, 0xff204060);
	assertEquals(0xff204060, TileColors.average(img));
    }

    @Test
    void fullyTransparentIsBlack() {
	assertEquals(0xff000000, TileColors.average(new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)));
    }
}
