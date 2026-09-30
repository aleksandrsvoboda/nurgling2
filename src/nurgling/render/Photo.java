package nurgling.render;

/*
 * Photo mode (Vulkan only): the interface is hidden, a click on the
 * scene sets the focus, and what is nearer or further than that goes
 * soft (see SceneFX.DoF). Shift and the mouse wheel set how soft.
 */
public class Photo {
    public static volatile boolean on = false;
    /* View distance in focus (0 until clicked: everything sharp),
     * and how strong the blur is. */
    public static volatile float focus = 0, aperture = 1.0f;
    public static volatile double since = 0;
}
