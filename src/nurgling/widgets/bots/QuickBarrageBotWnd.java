package nurgling.widgets.bots;

import haven.Button;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import nurgling.NGameUI;
import nurgling.actions.bots.QuickBarrageBotRunner;
import nurgling.sessions.BotExecutor;

import java.awt.Color;

public class QuickBarrageBotWnd extends Window {
    private static final String PREF_THRESHOLD = "quickbarrage-threshold";
    private static final int MIN_THRESHOLD = 50, MAX_THRESHOLD = 100;

    private final NGameUI gui;
    private final TextEntry thresholdEntry;
    private final Button startStopButton;
    private volatile boolean running = false;
    private volatile int threshold;
    private Thread botThread = null;
    private QuickBarrageBotRunner botRunner = null;

    public QuickBarrageBotWnd(NGameUI gui) {
        super(UI.scale(250, 100), "Quick Barrage Bot", true);
        this.gui = gui;
        this.threshold = Utils.getprefi(PREF_THRESHOLD, 90);

        Widget prev = add(new Label("Cornered threshold (" + MIN_THRESHOLD + "-" + MAX_THRESHOLD + "):"),
                          UI.scale(0, 6));

        prev = thresholdEntry = add(new TextEntry(UI.scale(80), String.valueOf(threshold)) {
            @Override
            protected void changed() {
                readThreshold(false);
            }
        }, prev.pos("bl").adds(0, 6));

        startStopButton = add(new Button(UI.scale(100), "Stop") {
            @Override
            public void click() {
                if (running)
                    stopBot();
                else
                    startBot();
                defocus();
            }
        }, prev.pos("bl").adds(0, 8));

        pack();
    }

    /* Hands the keyboard back to the game after a click, so combat hotkeys keep working
     * while the window is open. */
    private void defocus() {
        if (gui.portrait != null)
            setfocus(gui.portrait);
    }

    /** @return the entered threshold, or -1 when it is not a usable number. */
    private int readThreshold(boolean complain) {
        int value;
        try {
            value = Integer.parseInt(thresholdEntry.buf.line().trim());
        } catch (NumberFormatException e) {
            if (complain)
                gui.msg("Invalid threshold value", Color.RED);
            return(-1);
        }
        if (value < MIN_THRESHOLD || value > MAX_THRESHOLD) {
            if (complain)
                gui.msg("Threshold must be between " + MIN_THRESHOLD + " and " + MAX_THRESHOLD, Color.RED);
            return(-1);
        }
        threshold = value;
        Utils.setprefi(PREF_THRESHOLD, value);
        return(value);
    }

    public void startBot() {
        if (running)
            return;
        if (readThreshold(true) < 0)
            return;

        running = true;
        startStopButton.change("Stop");
        botRunner = new QuickBarrageBotRunner(gui, threshold, this);
        botThread = BotExecutor.runTask("QuickBarrageBot", botRunner);
    }

    public void stopBot() {
        running = false;
        startStopButton.change("Start");
        if (botRunner != null) {
            botRunner.stop();
            botRunner = null;
        }
        if (botThread != null) {
            if (botThread.isAlive())
                botThread.interrupt();
            botThread = null;
        }
    }

    public boolean isRunning() {
        return(running);
    }

    public int getThreshold() {
        return(threshold);
    }

    /** Called from the runner thread when it stops on its own (target down, interrupt). */
    public void setRunning(boolean value) {
        running = value;
        if (startStopButton != null)
            startStopButton.change(value ? "Stop" : "Start");
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && msg.equals("close")) {
            stopBot();
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void destroy() {
        stopBot();
        super.destroy();
    }
}
