package nurgling;

import haven.*;

public class NCharWnd extends CharWnd {
    public NCharWnd(Glob glob) {
        super(glob);
    }

    @Override
    protected Deco makedeco() {
        return new NWindowDeco(this.large, new Coord(UI.scale(11), Window.dsmrgn.y));
    }

    @Override
    public void show() {
        super.show();
        alignNavButtons();
    }

    private void alignNavButtons() {
        if(battr == null || battr.feps == null || battr.glut == null) return;

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
            buttons[i].c = Coord.of(x, buttons[i].c.y);
            x += buttons[i].sz.x;
            if(i < gapCount) {
                perror += totalSpace;
                x += perror / gapCount;
                perror %= gapCount;
            }
        }
    }
}
