package nurgling;

import haven.*;

public class NCharWnd extends CharWnd {
    public NCharWnd(Glob glob) {
        super(glob);
    }

    @Override
    protected Deco makedeco() {
        return new NWindowDeco(this.large);
    }
}
