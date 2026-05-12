package nurgling.widgets.bots;

import haven.*;

public class MultiAreaConfirm extends Window implements Checkable {

    public enum State {
        PENDING,
        ADD_ANOTHER,
        BUILD,
        CANCELLED
    }

    private State state = State.PENDING;
    private boolean ready = false;

    public MultiAreaConfirm(int positionsSoFar, int areasSoFar) {
        super(new Coord(260, 130), "Add another area?");

        String summary = areasSoFar + " area" + (areasSoFar == 1 ? "" : "s") +
                         " selected, " + positionsSoFar + " building" +
                         (positionsSoFar == 1 ? "" : "s") + " queued.";
        Widget prev = add(new Label(summary), new Coord(UI.scale(10), UI.scale(10)));

        prev = add(new Button(UI.scale(220), "Add another area") {
            @Override
            public void click() {
                super.click();
                state = State.ADD_ANOTHER;
                ready = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 15)));

        prev = add(new Button(UI.scale(220), "Start building") {
            @Override
            public void click() {
                super.click();
                state = State.BUILD;
                ready = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        pack();
    }

    @Override
    public boolean check() {
        return ready;
    }

    public State getState() {
        return state;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            state = State.CANCELLED;
            ready = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
}
