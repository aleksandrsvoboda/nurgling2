package nurgling.tasks;

import nurgling.widgets.bots.Checkable;

public class WaitCheckable extends NTask{
    Checkable widget;

    public WaitCheckable(Checkable widget) {
        this.widget = widget;
    }

    @Override
    public boolean check() {
        return widget.check();
    }

    // Waits for the user to finish with a widget - there is no upper bound.
    @Override
    public long stallTimeoutMs() {
        return Long.MAX_VALUE;
    }
}
