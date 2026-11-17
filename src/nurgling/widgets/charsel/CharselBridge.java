package nurgling.widgets.charsel;

import haven.*;
import nurgling.NConfig;
import nurgling.headless.Headless;
import nurgling.widgets.login.NBackdrop;

/**
 * Fills the gap between the login screen and the character-selection screen, which used to blink
 * black for about 180 ms (measured in a 144 fps capture). Two empty stretches make up that gap: the
 * login UI after its login screen is removed, while the session's UI is being built ({@link #cover});
 * and the session's fresh UI until the server sends the character screen ({@link #show}). Both get
 * the character screen's backdrop - the same art, scrim and placement - so the login art changes
 * straight to the character art. The bridge removes itself once the real screen (or a game, or the
 * login screen again) is there; in the login UI it simply goes away with that UI.
 */
public class CharselBridge extends Widget {
    /** Give up after this long, in case the session never shows the character screen. */
    private static final double TIMEOUT = 20;
    private double age = 0;

    private CharselBridge() {
        super(NCharselScreen.screenSize());
        add(new NBackdrop(() -> Img.getCharselBg(lastArt()), NBackdrop.SCRIMW), Coord.z);
    }

    /** Puts the bridge into a session UI that has just started. */
    public static void show(UI ui) {
        /* only a fresh, empty UI: a session switch can hand back a UI that already shows a game */
        if (Headless.isHeadless() || (ui == null) || (ui.root == null) || (ui.root.child != null))
            return;
        ui.root.add(new CharselBridge(), Coord.z);
    }

    private static Defer.Future<Tex> preloaded = null;

    /** Puts the bridge into the login UI as its login screen is removed, until that UI is replaced. */
    public static void cover(UI ui) {
        if (Headless.isHeadless() || (ui == null) || (ui.root == null))
            return;
        ui.root.add(new CharselBridge(), Coord.z);
    }

    /** Starts decoding the art the character screen will most likely open with, off the UI thread. */
    public static void preload() {
        int art = lastArt();
        preloaded = Defer.later(() -> Img.getCharselBg(art));
    }

    /**
     * Draws the preloaded art once, 1x1 at the corner, for the login screen to call before it draws
     * its own art over it - invisible, but it makes the renderer upload the texture now. Decoding
     * alone leaves the upload to the first real frame, and with an asynchronous upload that frame
     * comes out without the art: a one-frame blink.
     */
    public static void warm(GOut g) {
        Defer.Future<Tex> f = preloaded;
        if ((f != null) && f.done())
            g.image(f.get(), Coord.z, Coord.of(1, 1));
    }

    /** The art variant the character screen showed last; see {@link Img#getCharselBg(int)}. */
    static int lastArt() {
        Object v = NConfig.get(NConfig.Key.charselArt);
        return ((v instanceof Number) ? ((Number) v).intValue() & 3 : 0);
    }

    protected void added() {
        presize();
    }

    public void presize() {
        c = parent.sz.div(2).sub(sz.div(2));
    }

    public void tick(double dt) {
        super.tick(dt);
        age += dt;
        boolean done = age > TIMEOUT;
        for (Widget w = parent.child; (w != null) && !done; w = w.next) {
            if ((w != this) && ((w instanceof NCharselScreen) || (w instanceof GameUI) || (w instanceof LoginScreen)))
                done = true;
        }
        if (done)
            reqdestroy();
    }
}
