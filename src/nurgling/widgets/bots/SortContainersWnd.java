package nurgling.widgets.bots;

import haven.*;

public class SortContainersWnd extends Window implements Checkable {

    public boolean sortStacks = false;
    boolean isReady = false;

    public SortContainersWnd() {
        super(new Coord(200, 200), "Sort Containers");
        prev = add(new Label("Settings"));
        prev = add(new CheckBox("Sort stacks") {
            @Override
            public void set(boolean a) {
                super.set(a);
                sortStacks = a;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Button(UI.scale(150), "Start") {
            @Override
            public void click() {
                super.click();
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        pack();
    }

    @Override
    public boolean check() {
        return isReady;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            isReady = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
}
