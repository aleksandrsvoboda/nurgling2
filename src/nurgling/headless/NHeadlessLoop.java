package nurgling.headless;

import haven.*;
import haven.iosys.tk.*;
import haven.iosys.audio.*;
import nurgling.NUI;

/**
 * Headless UI loop.
 *
 * Replaces the old {@code HeadlessPanel} (which implemented the now-removed
 * {@code UIPanel}/{@code UI.Context}) by building on hafen's new iosys
 * architecture: a {@link UILoop} driven by a {@link DummyToolkit} window. The
 * window is backed by the GL-free {@link HeadlessEnvironment} stub, so no GPU,
 * display or offscreen OpenGL context is required on the host.
 */
public class NHeadlessLoop extends UILoop {
    public NHeadlessLoop(Coord size) {
	super(DummyToolkit.DummyWindow.of(size, new HeadlessEnvironment(), null));
    }

    /* Build NUI so bot/automation code sees the nurgling UI, same as the visual loop. */
    protected UI mkui(Windeye wnd, Audio.Root audio, Coord sz, UI.Runner fun) {
	return new NUI(wnd, audio, sz, fun);
    }

    /* No input source and no cursor in headless mode. DummyToolkit.makecursor()
     * throws, so drawcursor() must be a no-op. */
    protected void dispatch(UI ui) {}
    protected void drawcursor(UI ui, GOut g) {}

    protected AudioSystem.SinkLine audiosink() {
	return DummyAudio.DummySink.instance;
    }

    protected boolean bgmode() {
	return false;
    }
}
