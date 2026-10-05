package nurgling.craft;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reads the actual shipped binary and exercises source/character overlay boundaries. */
public class WikiRecipesTest {
    static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
    static AtlasCatalog.Recipe find(WikiRecipes db, String name) {
        return db.recipes.stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing " + name));
    }
    static void rejects(byte[] bytes, String label) throws Exception {
        try { WikiRecipes.read(new ByteArrayInputStream(bytes)); throw new AssertionError("Accepted " + label); }
        catch(IOException expected) {}
    }
    static void updateTests(WikiRecipes db, byte[] binary, AtlasCatalog catalog) throws Exception {
        AtlasCatalog.Recipe bucket = find(db, "Bucket"), axe = find(db, "Stone Axe");
        catalog.toggleFavorite(axe.resource);
        AtlasCatalog.WikiInfo old = bucket.wiki;
        AtlasCatalog.WikiInfo updated = new AtlasCatalog.WikiInfo(old.source, old.date, old.kind, old.actionName,
            old.inputText, old.revision + 1, old.quantitiesKnown, old.requirements, old.formulas);
        List<AtlasCatalog.Recipe> next = new ArrayList<>(db.recipes);
        next.removeIf(r -> r.resource.equals(axe.resource) || r.resource.equals(bucket.resource));
        next.add(new AtlasCatalog.Recipe(bucket.resource, "Bucket renamed", bucket.group, bucket.inputs, bucket.outputs,
            bucket.quality, bucket.tools, false, updated));
        next.add(new AtlasCatalog.Recipe("wiki:test-added", "Added recipe", "", bucket.inputs, bucket.outputs,
            bucket.quality, bucket.tools, false, updated));
        catalog.replaceWiki(next);
        check(catalog.get(axe.resource) == null, "Removed wiki entry retained in active catalog");
        check(catalog.get("wiki:test-added") != null, "Added entry missing after reload");
        check(catalog.get(bucket.resource).name.equals("Bucket renamed") && catalog.get(bucket.resource).wiki.revision == old.revision + 1,
              "Renamed/revised entry did not reload under stable ID");
        check(catalog.get(bucket.resource).inputs.get(0).count == 7 && catalog.favorite(bucket.resource), "Reload lost server data/favorite");
        catalog.replaceWiki(db.recipes);
        check(catalog.favorite(axe.resource), "Favorite lost when removed wiki page returned");
        next.removeIf(r -> r.resource.equals(bucket.resource));
        catalog.replaceWiki(next);
        check(catalog.get(bucket.resource).recorded, "Removing a wiki page erased a personal observation");

        Path directory = Files.createTempDirectory(Paths.get("build/wiki-recipes"), "reload-test-");
        Path path = directory.resolve("recipes.bin");
        try {
            Files.write(path, binary);
            WikiRecipes first = WikiRecipes.loadAvailable(path);
            check(WikiRecipes.loadAvailable(path) == first, "Unchanged file needlessly reloaded");
            byte[] bad = binary.clone(); bad[16] ^= 1;
            Files.write(path, bad);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
            try { WikiRecipes.loadAvailable(path); throw new AssertionError("Broken update accepted"); }
            catch(IOException expected) {}
            Files.delete(path);
            check(WikiRecipes.loadAvailable(path) == first, "Broken/missing update discarded working database");
            Files.write(path, binary);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 4000));
            check(WikiRecipes.loadAvailable(path) != first, "Fixed database was not retried");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }
    public static void main(String[] args) throws Exception {
        byte[] binary = Files.readAllBytes(Paths.get("bin/recipes.bin"));
        WikiRecipes db = WikiRecipes.read(new ByteArrayInputStream(binary));
        check(db.recipes.size() >= 1000 && db.recipes.size() < 5000, "Incomplete or unexpectedly large database");
        try(InputStream in = WikiRecipes.class.getResourceAsStream("recipes.bin")) {
            check(in != null && Arrays.equals(binary, in.readAllBytes()), "External and embedded databases differ");
        }
        check(db.source.equals("https://ringofbrodgar.com/"), "Missing origin");
        check(db.attribution.contains("contributors"), "Missing attribution");
        check(find(db, "Bucket").inputs.get(0).quantity(3).equals("6"), "Bucket amount");
        AtlasCatalog.Recipe rawPie = find(db, "Unbaked Apple Pie"), bakedPie = find(db, "Apple Pie");
        check(rawPie.inputs.get(0).quantity(3).equals("1.5 l"), "Fractional liquid amount");
        check(rawPie.inputs.get(1).quantity(3).equals("1.65 kg"), "Fractional flour amount");
        check(bakedPie.inputs.get(0).resource.equals(rawPie.outputs.get(0).resource), "Processing stage link");
        check(bakedPie.outputs.get(0).quantity(1).equals("?"), "Unspecified output invented");
        check(find(db, "Boar Boudin").wiki.quantitiesKnown && find(db, "Boar Boudin").inputs.get(0).choices.size() == 2,
              "Alternative inputs not grouped");
        AtlasCatalog.Recipe canape = find(db, "Caviar Canapé");
        check(canape.wiki.quantitiesKnown && canape.inputs.size() == 4, "Canape left unparsed");
        check(canape.inputs.get(1).quantity(4).equals("1 l") && canape.inputs.get(2).quantity(4).equals("1 kg"), "Choice/liquid amount scaling");
        check(canape.inputs.get(2).choices.size() == 3 && canape.inputs.get(3).optional, "Choice/optional flags lost");
        check(!find(db, "Bar of Cast Iron").wiki.quantitiesKnown, "Alternative smelting feedstocks treated as cumulative");
        check(find(db, "Palisade").wiki.inputText.contains("Cornerpost") && !find(db, "Palisade").wiki.quantitiesKnown,
              "Construction stages collapsed");
        for(AtlasCatalog.Recipe recipe : db.recipes) {
            check(recipe.wiki != null && !recipe.recorded, "Wiki recipe misrepresented as observed");
            check(recipe.wiki.source.contains("oldid=" + recipe.wiki.revision), "Missing source revision");
            for(AtlasCatalog.Material material : recipe.inputs) check(Double.isFinite(material.count), "Nonfinite amount");
        }
        AtlasCatalog catalog = new AtlasCatalog(); db.recipes.forEach(catalog::put);
        AtlasCatalog roundTrip = new AtlasCatalog(); roundTrip.load(catalog.json());
        check(roundTrip.get(canape.resource).inputs.get(2).choices.size() == 3, "Choice journal round-trip failed");
        check(catalog.uses("wiki-item:roe").contains(canape), "Alternative reverse dependency lost");
        check(catalog.journalJson().getJSONArray("recipes").length() == 0, "Binary database duplicated into character JSON");
        check(catalog.findWikiCraft("Apple Pie").resource.equals(rawPie.resource), "Baked item matched instead of raw crafting action");
        check(catalog.producer(bakedPie.inputs.get(0)).resource.equals(rawPie.resource), "Recipe dependency not found");
        check(catalog.uses(rawPie.outputs.get(0).key()).contains(bakedPie), "Reverse dependency not found");
        AtlasCatalog.Recipe bucket = find(db, "Bucket");
        AtlasCatalog.Recipe recorded = new AtlasCatalog.Recipe("paginae/craft/bucket", "Bucket", "Vessels",
            Collections.singletonList(new AtlasCatalog.Material("gfx/invobjs/board", "Board", 7, false, false)),
            Collections.singletonList(new AtlasCatalog.Material("gfx/invobjs/bucket", "Bucket", 1, false, false)),
            Collections.singletonList("Carpentry"), Collections.emptyList(), true);
        catalog.put(recorded); catalog.toggleFavorite(recorded.resource);
        catalog.migrate(recorded.resource, bucket);
        check(catalog.get(recorded.resource) == null && catalog.favorite(bucket.resource), "Legacy migration duplicated recipe or lost favorite");
        check(catalog.get(bucket.resource).inputs.get(0).count == 7, "Wiki overwrote server observation");
        AtlasCatalog restored = new AtlasCatalog(); db.recipes.forEach(restored::put); restored.mergeJournal(catalog.journalJson());
        check(restored.recipes().size() == db.recipes.size(), "Journal merge duplicated base entries");
        check(restored.get(bucket.resource).inputs.get(0).count == 7 && restored.favorite(bucket.resource), "Journal lost observation/favorite");
        check(restored.get(bucket.resource).wiki.source.equals(bucket.wiki.source), "Journal lost source provenance");
        check(db.reference("Unbaked Apple Pie").equals(rawPie.outputs.get(0).resource), "Live material reference not resolved");
        updateTests(db, binary, restored);
        check(catalog.findWikiCraft("Bucket") != null, "Unique wiki action missing");
        catalog.put(new AtlasCatalog.Recipe("wiki:test-duplicate", "Bucket", "", bucket.inputs, bucket.outputs,
            bucket.quality, bucket.tools, false, bucket.wiki));
        check(catalog.findWikiCraft("Bucket") == null, "Cached name lookup accepted ambiguous action");
        byte[] corrupt = binary.clone(); corrupt[0] = 'X'; rejects(corrupt, "unknown version");
        corrupt = binary.clone(); corrupt[16] ^= 1; rejects(corrupt, "bad checksum");
        corrupt = binary.clone(); Arrays.fill(corrupt, 8, 12, (byte)127); rejects(corrupt, "excessive size");
        rejects(Arrays.copyOf(binary, binary.length - 1), "truncated file");
        rejects(Arrays.copyOf(binary, binary.length + 1), "trailing data");
        System.out.println("PASS: " + db.recipes.size() + " wiki recipes, binary integrity, decimal units, source links, processing stages, live updates and server/journal overlays");
    }
}
