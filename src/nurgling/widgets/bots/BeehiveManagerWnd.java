package nurgling.widgets.bots;

import haven.*;

public class BeehiveManagerWnd extends Window implements Checkable {

    public boolean collectHoney = true;
    public boolean collectWax = true;
    boolean isReady = false;

    public BeehiveManagerWnd() {
        super(new Coord(200, 200), "Beehive Manager");
        prev = add(new Label("Settings"));

        prev = add(new CheckBox("Collect honey") {
            { a = true; }
            @Override
            public void set(boolean a) {
                super.set(a);
                collectHoney = a;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new CheckBox("Collect wax") {
            { a = true; }
            @Override
            public void set(boolean a) {
                super.set(a);
                collectWax = a;
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
