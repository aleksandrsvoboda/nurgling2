package nurgling;

import haven.*;

public class NCharWnd extends CharWnd {
    private Coord maxSz = null;
    private int navBtnY = -1;
    private boolean initialized = false;

    public NCharWnd(Glob glob) {
        super(glob);
    }

    @Override
    protected Deco makedeco() {
        return new NWindowDeco(this.large, new Coord(UI.scale(11), Window.dsmrgn.y));
    }

    @Override
    public void resize(Coord sz) {
        if(maxSz == null) {
            maxSz = sz;
        } else {
            maxSz = Coord.of(Math.max(sz.x, maxSz.x), Math.max(sz.y, maxSz.y));
        }
        super.resize(maxSz);
        if(initialized)
            alignNavButtons();
    }

    @Override
    public void show() {
        if(battr == null) {
            // Skip CharWnd.show() which accesses battr — just show the widget
            visible = true;
            return;
        }
        super.show();
        initialized = true;
        alignNavButtons();
    }

    private void alignNavButtons() {
        if(battr == null || battr.feps == null || battr.glut == null) return;
        if(tbbattrtab == null) return;

        if(tbbattrtab.c.y > navBtnY)
            navBtnY = tbbattrtab.c.y;

        int tabX = battrtab.c.x;
        int leftEdge = tabX + battr.feps.c.x;
        int rightEdge = tabX + battr.glut.c.x + battr.glut.sz.x;

        Widget[] buttons = {tbbattrtab, tbsattrtab, tbskilltab, tbfighttab, tbwoundtab, tbquesttab};
        int totalBtnW = 0;
        for(Widget btn : buttons) totalBtnW += btn.sz.x;

        int totalSpace = rightEdge - leftEdge - totalBtnW;
        int gapCount = buttons.length - 1;

        int x = leftEdge;
        int perror = 0;
        for(int i = 0; i < buttons.length; i++) {
            buttons[i].c = Coord.of(x, navBtnY);
            x += buttons[i].sz.x;
            if(i < gapCount) {
                perror += totalSpace;
                x += perror / gapCount;
                perror %= gapCount;
            }
        }
    }
}
