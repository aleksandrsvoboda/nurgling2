package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Results;
import nurgling.areas.NContext;
import nurgling.scenarios.CraftPreset;
import nurgling.scenarios.CraftPresetManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a CraftPreset's ingredient shortfalls against its inputs' fallback-recipe lists,
 * before any gathering or crafting happens for the top-level craft.
 * <p>
 * Phase A ({@link #plan}) is a read-only depth-first search with backtracking: for each input
 * short of stock, it tries that input's fallback presets in priority order, recursing into each
 * candidate's own inputs before accepting it. The first candidate whose whole subtree resolves
 * wins; if none do, the search backtracks to whichever candidate one level up was trying this
 * preset. Phase B ({@link #execute}) then actually crafts the chosen fallbacks, deepest first,
 * as ordinary {@link AutocraftBot} runs - nothing special, including their normal output
 * dispersal.
 */
public class ComplexCraftResolver {

    public static class PlanNode {
        public final CraftPreset preset;
        public final int quantity;
        public final List<PlanNode> dependencies;

        PlanNode(CraftPreset preset, int quantity, List<PlanNode> dependencies) {
            this.preset = preset;
            this.quantity = quantity;
            this.dependencies = dependencies;
        }
    }

    /**
     * Entry point: plan enough of {@code preset} x {@code quantity} to be craftable, recursively
     * resolving any input shortfall through its fallback-recipe list. Purely a read - nothing is
     * taken, moved, or crafted. Returns null if some input can't be satisfied by any means.
     */
    public static PlanNode plan(CraftPreset preset, int quantity, NGameUI gui) throws InterruptedException {
        NContext ncontext = new NContext(gui);
        Set<String> inProgress = new HashSet<>();
        inProgress.add(preset.getId());
        return plan(preset, quantity, gui, ncontext, inProgress);
    }

    private static PlanNode plan(CraftPreset preset, int quantity, NGameUI gui, NContext ncontext,
                                  Set<String> inProgress) throws InterruptedException {
        List<PlanNode> dependencies = new ArrayList<>();

        for (CraftPreset.InputSpec input : preset.getInputs()) {
            if (input.isIgnored()) {
                continue;
            }
            String effectiveName = input.getEffectiveName();
            if (effectiveName == null) {
                // Category input with no preferred ingredient chosen - Craft.java auto-selects
                // at craft time; out of scope for fallback resolution.
                continue;
            }

            int required = input.getCount() * quantity;
            int available = ncontext.countAvailable(effectiveName, gui);
            if (available >= required) {
                continue;
            }

            PlanNode resolved = null;
            for (String candidateId : input.getFallbackPresetIds()) {
                if (inProgress.contains(candidateId)) {
                    continue; // cycle guard - treat as an infeasible candidate, try the next one
                }
                CraftPreset candidate = CraftPresetManager.getInstance().getPreset(candidateId);
                if (candidate == null || candidate.getOutputs().isEmpty()) {
                    continue;
                }
                int outputPerCraft = Math.max(1, candidate.getOutputs().get(0).getCount());
                int deficit = required - available;
                int subCraftsNeeded = (deficit + outputPerCraft - 1) / outputPerCraft;

                Set<String> nextInProgress = new HashSet<>(inProgress);
                nextInProgress.add(candidateId);
                PlanNode sub = plan(candidate, subCraftsNeeded, gui, ncontext, nextInProgress);
                if (sub != null) {
                    resolved = sub;
                    break; // first viable candidate wins, per priority order
                }
            }

            if (resolved == null) {
                return null; // this input can't be satisfied by any candidate - backtrack
            }
            dependencies.add(resolved);
        }

        return new PlanNode(preset, quantity, dependencies);
    }

    /**
     * Phase B: actually craft every dependency in {@code node}, deepest first, as ordinary
     * AutocraftBot runs. Does not craft {@code node} itself - the caller (AutocraftBot) does
     * that afterward through its own normal flow.
     */
    public static Results execute(PlanNode node, NGameUI gui) throws InterruptedException {
        for (PlanNode dep : node.dependencies) {
            Results depsResult = execute(dep, gui);
            if (!depsResult.IsSuccess()) {
                return depsResult;
            }
            Map<String, Object> settings = new HashMap<>();
            settings.put("presetId", dep.preset.getId());
            settings.put("quantity", dep.quantity);
            Results craftResult = new AutocraftBot(settings).run(gui);
            if (!craftResult.IsSuccess()) {
                return craftResult;
            }
        }
        return Results.SUCCESS();
    }
}
