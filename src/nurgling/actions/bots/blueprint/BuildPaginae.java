package nurgling.actions.bots.blueprint;

import haven.Loading;
import haven.MenuGrid;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.bots.BuildCatalog;
import nurgling.tools.NFileUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Build menu button places which gob resource. A planner ghost only stores the gob resource
 * ("gfx/terobjs/cupboard"); the bot has to click a "paginae/bld/..." button to place it.
 * <p>
 * Every time a build button is used (by the user or a bot) and the server answers with a placement
 * cursor, the pair is recorded in {@code build_paginae.json}. Unlearned resources fall back to
 * {@link BuildCatalog} and to a same-basename match; callers verify the cursor resource before
 * trusting any candidate.
 */
public final class BuildPaginae
{
    private static final String FILE = "build_paginae.json";
    /** A placement cursor this long after a button press still belongs to that press. */
    private static final long LEARN_WINDOW_MS = 10000;

    private static final Map<String, String> learned = new HashMap<>();
    private static boolean loaded = false;

    private static volatile String lastBuildPagina = null;
    private static volatile long lastBuildAt = 0;

    /**
     * Buttons whose resource is named unlike what they place (Barter Stand is paginae/bld/vstand), so a
     * character that never pressed them still finds them. Taken from the game's build list.
     */
    private static final Map<String, List<String>> renamed = new HashMap<>();

    static
    {
        known("gfx/terobjs/pclaim", "paginae/bld/claim");
        known("gfx/terobjs/embankmentscaffold", "paginae/bld/rscaffold");
        known("gfx/terobjs/villageidol", "paginae/bld/vilgate");
        known("gfx/terobjs/barterstand", "paginae/bld/vstand");
        known("gfx/terobjs/bonfire", "paginae/bld/symbelfire");
        known("gfx/terobjs/pow", "paginae/bld/fireplace");
        known("gfx/terobjs/arch/primitivetent", "paginae/bld/primtent");
        known("gfx/terobjs/arch/waterwheelhouse", "paginae/bld/watermill");
        known("gfx/terobjs/minevator-top", "paginae/bld/minevator");
        known("gfx/terobjs/road/milestone-stone-e", "paginae/bld/stoneendstone");
        known("gfx/terobjs/road/milestone-wood-e", "paginae/bld/woodendstone");
        known("gfx/terobjs/ancestralshrine", "paginae/bld/ancaltar");
        known("gfx/terobjs/archerytarget", "paginae/bld/archtgt");
        known("gfx/terobjs/studydesk-big", "paginae/bld/finestudydesk");
        known("gfx/terobjs/furn/bed-sturdy", "paginae/bld/sturdybed");
        known("gfx/terobjs/furn/armchair-cloth", "paginae/bld/clotharmchair");
        known("gfx/terobjs/furn/chair-cloth", "paginae/bld/clothchair");
        known("gfx/terobjs/furn/chair-rustic", "paginae/bld/rusticchair");
        known("gfx/terobjs/furn/chair-rocking", "paginae/bld/rockingchair");
        known("gfx/terobjs/furn/footstool-cloth", "paginae/bld/clothfootstool");
        known("gfx/terobjs/mannequin-stand", "paginae/bld/mannequin");
        known("gfx/terobjs/furn/table-elegant", "paginae/bld/eleganttable");
        known("gfx/terobjs/furn/table-rustic", "paginae/bld/rustictable");
        known("gfx/terobjs/furn/table-stone", "paginae/bld/stonetable");
        known("gfx/terobjs/curdingtub", "paginae/bld/ctub");
        known("gfx/terobjs/archerytower", "paginae/bld/atower");
        known("gfx/terobjs/vehicle/wreckingball", "paginae/bld/wball");
        known("gfx/terobjs/vehicle/plow", "paginae/bld/metalplow");
        known("gfx/terobjs/vehicle/plow", "paginae/bld/woodenplow");
        known("gfx/terobjs/cauldron", "paginae/bld/ccauldron");
        known("gfx/terobjs/winepress", "paginae/bld/press");
        known("gfx/terobjs/cauldron", "paginae/bld/mcauldron");
    }

    private static void known(String gobRes, String paginaRes)
    {
        renamed.computeIfAbsent(gobRes, k -> new ArrayList<>()).add(paginaRes);
    }

    private BuildPaginae() {}

    /** True for a "bp" (build placement) menu button. */
    public static boolean isBuildButton(MenuGrid.Pagina pag)
    {
        try
        {
            MenuGrid.PagButton btn = pag.button();
            if (btn == null) return false;
            Resource.AButton act = btn.act();
            return act != null && act.ad != null && act.ad.length > 0 && "bp".equals(act.ad[0]);
        } catch (Loading l)
        {
            return false;
        }
    }

    /** Resource name of a menu button, or null while it is still loading. */
    public static String paginaRes(MenuGrid.Pagina pag)
    {
        try
        {
            MenuGrid.PagButton btn = pag.button();
            return (btn == null) ? null : btn.res.name;
        } catch (Loading l)
        {
            return null;
        }
    }

    /** Hook: a menu button was used (MenuGrid.PagButton.use). */
    public static void noteUsed(MenuGrid.Pagina pag)
    {
        if (!isBuildButton(pag)) return;
        String res = paginaRes(pag);
        if (res == null) return;
        lastBuildPagina = res;
        lastBuildAt = System.currentTimeMillis();
    }

    /** Hook: the server started a placement cursor for {@code gobRes} (NMapView "place"). */
    public static void notePlaced(String gobRes)
    {
        String pag = lastBuildPagina;
        if (pag == null || gobRes == null) return;
        if (System.currentTimeMillis() - lastBuildAt > LEARN_WINDOW_MS) return;
        lastBuildPagina = null;
        learn(gobRes, pag);
    }

    public static synchronized void learn(String gobRes, String paginaRes)
    {
        load();
        if (paginaRes.equals(learned.put(gobRes, paginaRes))) return;
        JSONObject root = new JSONObject();
        for (Map.Entry<String, String> e : learned.entrySet()) root.put(e.getKey(), e.getValue());
        try
        {
            NFileUtils.writeAtomically(NUtils.getDataFilePath(FILE).toString(), root.toString(2));
        } catch (IOException e)
        {
            System.out.println("[BlueprintBuilder] Can't save " + FILE + ": " + e.getMessage());
        }
    }

    private static synchronized String learnedFor(String gobRes)
    {
        load();
        return learned.get(gobRes);
    }

    private static void load()
    {
        if (loaded) return;
        loaded = true;
        Path path = NUtils.getDataFilePath(FILE);
        if (!Files.isRegularFile(path)) return;
        try
        {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
            for (String key : root.keySet()) learned.put(key, root.getString(key));
        } catch (IOException | org.json.JSONException e)
        {
            System.out.println("[BlueprintBuilder] Can't read " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * Unlocked build buttons that may place {@code gobRes}, most trusted first: the learned
     * button, the catalog's menu name, then any button with the same basename. Empty when the
     * character has none of them.
     */
    public static List<MenuGrid.Pagina> candidates(NGameUI gui, String gobRes)
    {
        List<MenuGrid.Pagina> out = new ArrayList<>();
        if (gui == null || gui.menu == null || gobRes == null) return out;
        String learnedRes = learnedFor(gobRes);
        String catalogName = null;
        for (BuildCatalog.BuildingDef def : BuildCatalog.all())
        {
            if (def.resName.equals(gobRes))
            {
                catalogName = def.name;
                break;
            }
        }
        String base = basename(gobRes);
        List<String> renamedRes = renamed.getOrDefault(gobRes, Collections.emptyList());
        MenuGrid.Pagina byLearned = null, byCatalog = null;
        List<MenuGrid.Pagina> byRenamed = new ArrayList<>(), byBase = new ArrayList<>();
        synchronized (gui.menu.paginae)
        {
            for (MenuGrid.Pagina pag : gui.menu.paginae)
            {
                if (!isBuildButton(pag)) continue;
                String res = paginaRes(pag);
                if (res == null) continue;
                if (res.equals(learnedRes)) byLearned = pag;
                else if (catalogName != null && catalogName.equals(pag.button().name())) byCatalog = pag;
                else if (renamedRes.contains(res)) byRenamed.add(pag);
                else if (basename(res).equals(base)) byBase.add(pag);
            }
        }
        if (byLearned != null) out.add(byLearned);
        if (byCatalog != null) out.add(byCatalog);
        out.addAll(byRenamed);
        out.addAll(byBase);
        return out;
    }

    private static String basename(String res)
    {
        int slash = res.lastIndexOf('/');
        return (slash >= 0) ? res.substring(slash + 1) : res;
    }
}
