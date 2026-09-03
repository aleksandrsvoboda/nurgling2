package nurgling.widgets;

import haven.*;
import nurgling.NStyle;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.scenarios.BotStep;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Reusable add/remove/reorder editor for an ordered {@link BotStep} list, shared by ScenarioPanel and Forager waypoint steps. */
public class StepListWidget extends Widget {
    private final Supplier<List<BotStep>> stepsSupplier;
    private final Consumer<BotStep> onSelect;
    private final Runnable onChanged;
    private final Predicate<BotDescriptor> botFilter;

    private final SListBox<BotStep, Widget> listBox;
    private BotStep selected = null;
    private ScenarioBotSelectionDialog stepDialog = null;

    /** stepsSupplier may return null/empty; onSelect gets the newly-selected step (or null); onChanged fires after any add/remove/reorder. */
    public StepListWidget(Coord sz, Supplier<List<BotStep>> stepsSupplier, Consumer<BotStep> onSelect, Runnable onChanged) {
        this(sz, stepsSupplier, onSelect, onChanged, b -> b.allowedAsStepInScenario);
    }

    /** botFilter controls which bots the "Add Step" picker offers. */
    public StepListWidget(Coord sz, Supplier<List<BotStep>> stepsSupplier, Consumer<BotStep> onSelect, Runnable onChanged, Predicate<BotDescriptor> botFilter) {
        super(sz);
        this.stepsSupplier = stepsSupplier;
        this.onSelect = onSelect;
        this.onChanged = onChanged;
        this.botFilter = botFilter;

        listBox = add(new SListBox<BotStep, Widget>(sz, UI.scale(32)) {
            @Override
            protected List<BotStep> items() {
                List<BotStep> steps = stepsSupplier.get();
                return steps != null ? steps : Collections.emptyList();
            }

            @Override
            public void change(BotStep item) {
                super.change(item);
                selected = item;
                if (onSelect != null) onSelect.accept(item);
            }

            @Override
            protected Widget makeitem(BotStep step, int idx, Coord isz) {
                return new ItemWidget<BotStep>(this, isz, step) {{
                    String botId = step.getId();
                    BotDescriptor desc = BotRegistry.byId(botId);
                    Tex iconTex = null;
                    if (desc != null) {
                        botId = desc.getDisplayName();
                        try {
                            BufferedImage iconImg = Resource.loadsimg(desc.getUpIconPath());
                            if (iconImg != null)
                                iconTex = new TexI(iconImg);
                        } catch (Exception e) {
                            iconTex = null;
                        }
                    }

                    // Mark ✪ for bots that have settings
                    boolean hasSettings = desc != null && ("goto_area".equals(desc.id) || "forager".equals(desc.id) || "gate".equals(desc.id));
                    String marker = hasSettings ? " ✪" : "";
                    Label label = new Label(botId + marker);

                    int iconMargin = UI.scale(4);
                    int iconSize = UI.scale(24);
                    int labelX = iconTex != null ? iconSize + iconMargin * 2 : UI.scale(10);
                    int labelY = (isz.y - label.sz.y) / 2;

                    if (iconTex != null) {
                        Tex finalIconTex = iconTex;
                        add(new Widget(new Coord(iconSize, iconSize)) {
                            @Override
                            public void draw(GOut g) {
                                g.image(finalIconTex, Coord.z, new Coord(iconSize, iconSize));
                            }
                        }, new Coord(iconMargin, (isz.y - iconSize) / 2));
                    }

                    add(label, new Coord(labelX, labelY));

                    // Move Up button
                    int upBtnX = isz.x - UI.scale(90);
                    add(new IButton(NStyle.upSquareArrow[0].back, NStyle.upSquareArrow[1].back, NStyle.upSquareArrow[2].back) {
                        @Override
                        public void click() {
                            moveStep(step, -1);
                        }
                    }, new Coord(upBtnX, (isz.y - UI.scale(22)) / 2));

                    // Move Down button
                    int downBtnX = isz.x - UI.scale(60);
                    add(new IButton(NStyle.downSquareArrow[0].back, NStyle.downSquareArrow[1].back, NStyle.downSquareArrow[2].back) {
                        @Override
                        public void click() {
                            moveStep(step, 1);
                        }
                    }, new Coord(downBtnX, (isz.y - UI.scale(22)) / 2));

                    int removeBtnX = isz.x - UI.scale(30);
                    add(new IButton(NStyle.crossSquare[0].back, NStyle.crossSquare[1].back, NStyle.crossSquare[2].back) {
                        @Override
                        public void click() {
                            removeStep(step);
                        }
                    }, new Coord(removeBtnX, (isz.y - UI.scale(22)) / 2));
                }
                    @Override
                    public void draw(GOut g) {
                        if (list.sel == this.item) {
                            g.chcolor(50, 80, 120, 120);
                            g.frect(Coord.z, sz);
                            g.chcolor();
                        }
                        super.draw(g);
                    }

                    @Override
                    public boolean mousedown(MouseDownEvent ev) {
                        if (super.mousedown(ev)) return true;
                        if (ev.b == 1) {
                            list.change(this.item);
                            return true;
                        }
                        return false;
                    }
                };
            }
        }, Coord.z);
    }

    public BotStep selected() {
        return selected;
    }

    public void refresh() {
        listBox.update();
        BotStep steps0 = null;
        List<BotStep> steps = stepsSupplier.get();
        if (steps != null && !steps.isEmpty()) {
            steps0 = steps.get(0);
        }
        // Keep the current selection if still present, else fall back to the first step.
        if (steps == null || !steps.contains(selected)) {
            listBox.change(steps0);
        }
    }

    /** Opens the bot-picker dialog, appending the chosen bot as a new step. */
    public void showAddStepDialog() {
        closeAddStepDialog();
        stepDialog = new ScenarioBotSelectionDialog(botFilter, bot -> {
            List<BotStep> steps = stepsSupplier.get();
            if (steps != null && bot != null) {
                steps.add(new BotStep(bot.id));
                listBox.update();
                if (onChanged != null) onChanged.run();
            }
            stepDialog = null;
        });
        ui.root.add(stepDialog, this.c.add(50, 50));
    }

    /** Closes the add-step dialog if open; callers should invoke this when navigating away. */
    public void closeAddStepDialog() {
        if (stepDialog != null) {
            stepDialog.reqdestroy();
            stepDialog = null;
        }
    }

    private void moveStep(BotStep step, int direction) {
        List<BotStep> steps = stepsSupplier.get();
        if (steps == null) return;
        int idx = steps.indexOf(step);
        int newIdx = idx + direction;
        if (idx < 0 || newIdx < 0 || newIdx >= steps.size()) return;

        Collections.swap(steps, idx, newIdx);
        listBox.update();

        selected = step;
        if (onSelect != null) onSelect.accept(step);
        if (onChanged != null) onChanged.run();
    }

    private void removeStep(BotStep step) {
        List<BotStep> steps = stepsSupplier.get();
        if (steps == null) return;
        steps.remove(step);
        listBox.update();
        if (selected == step) {
            selected = null;
            if (onSelect != null) onSelect.accept(null);
        }
        if (onChanged != null) onChanged.run();
    }
}
