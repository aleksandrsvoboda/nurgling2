package nurgling.widgets;

import haven.*;
import haven.Button;
import haven.Label;
import haven.render.MixColor;

import javax.swing.*;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class NColorWidget extends Widget
{
    public NColorButton cb;
    public Label label;

    public Color color = Color.BLACK;

    /** Where the swatch sits when the label is short enough, so rows line up in a column. */
    private static final int SWATCH_X = UI.scale(80);
    private static final int LABEL_GAP = UI.scale(6);

    public NColorWidget(String text){
        cb = new NColorButton();
        label = new Label(text + ":");
        add(label, UI.scale(0,8));
        // A fixed swatch position draws the swatch straight over any label wider than it.
        // Longer labels push it right instead; shorter ones keep the shared column, and
        // callers that pass no label at all are unaffected.
        add(cb, new Coord(Math.max(SWATCH_X, label.sz.x + LABEL_GAP), 0));
        pack();
    }
    public class NColorButton extends Button {
        JColorChooser colorChooser;
        public NColorButton(){
            super(Inventory.sqsz.x, "");
            sz.y = Inventory.sqsz.y;
            this.colorChooser = new JColorChooser();
            final AbstractColorChooserPanel[] panels = colorChooser.getChooserPanels();
            for (final AbstractColorChooserPanel accp : panels) {
                if (!accp.getDisplayName().equals("RGB")) {
                    colorChooser.removeChooserPanel(accp);
                }
            }
            colorChooser.setPreviewPanel(new JPanel());
            colorChooser.setColor(Color.WHITE);
        }

        @Override
        public void draw(GOut g) {
            int delta = 2;
            Coord size = new Coord(sz.x-2*delta,sz.y-2*delta);
            g.chcolor(color);
            g.frect(new Coord(delta,  delta), size);
            g.chcolor();
            g.chcolor(Color.BLACK);
            g.frect(new Coord(0,0), new Coord(sz.x,delta));
            g.frect(new Coord(0,sz.y-delta), new Coord(sz.x,delta));
            g.frect(new Coord(0,delta), new Coord(delta,size.y));
            g.frect(new Coord(sz.x-delta,delta), new Coord(delta,size.y));
            g.chcolor();
        }

        @Override
        public void click() {

            new Thread(new Runnable() {
                @Override
                public void run() {

                    JDialog chooser = JColorChooser.createDialog(null, "SelectColor", true, colorChooser, new AbstractAction() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            color = colorChooser.getColor();
                        }
                    }, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {

                        }
                    });
                    chooser.setVisible(true);
                }
            }).start();

        }
    }
}
