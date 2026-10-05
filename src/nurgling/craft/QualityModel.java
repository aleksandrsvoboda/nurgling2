package nurgling.craft;

import java.util.*;

/** Recipe-specific formula bindings; uncertain general rules remain explicitly marked estimates. */
public final class QualityModel {
    public enum Kind { MATERIAL, ATTRIBUTE, SKILL, TOOL }
    public static final class Port {
        public final String id, name, resource; public final Kind kind;
        Port(String id, String name, String resource, Kind kind) { this.id = id; this.name = name; this.resource = resource; this.kind = kind; }
    }
    public static final Map<String, String> STATS = new LinkedHashMap<>();
    public static final Set<String> ATTRIBUTES = Set.of("Strength", "Agility", "Intelligence", "Constitution", "Perception", "Charisma", "Dexterity", "Psyche", "Will");
    static {
        String[][] stats = {{"Strength","str"},{"Agility","agi"},{"Intelligence","int"},{"Constitution","con"},{"Perception","prc"},
            {"Charisma","csm"},{"Dexterity","dex"},{"Psyche","psy"},{"Will","wil"},{"Sewing","sewing"},{"Smithing","smithing"},
            {"Carpentry","carpentry"},{"Cooking","cooking"},{"Farming","farming"},{"Survival","survive"},{"Masonry","masonry"},
            {"Lore","lore"},{"Exploration","explore"},{"Stealth","stealth"}};
        for(String[] p : stats) STATS.put(p[0], p[1]);
    }
    public final List<Port> ports = new ArrayList<>();
    public String expression = "", sourceFormula = "";
    public boolean estimated = true;
    private final AtlasCatalog.Recipe recipe;
    private QualityModel(AtlasCatalog.Recipe recipe) { this.recipe = recipe; }
    public static QualityModel of(AtlasCatalog.Recipe recipe) {
        QualityModel m = new QualityModel(recipe);
        for(AtlasCatalog.Material input : recipe.inputs) m.add(input.name, input.resource, Kind.MATERIAL);
        for(String tool : recipe.tools) for(String name : tool.split("[,;]|\\s+and\\s+|\\s+or\\s+")) {
            name = name.trim();
            if(!name.isEmpty() && !name.equalsIgnoreCase("Hand") && !name.equalsIgnoreCase("Hands"))
                m.add(name, name.equals("Cauldron") ? "wiki-item:metal cauldron" : "", Kind.TOOL);
        }
        QualityExpression base = null, cap = null;
        if(recipe.wiki != null) for(String formula : recipe.wiki.formulas) {
            int before = m.ports.size();
            try {
                String rhs = formula;
                int equal = formula.indexOf('=');
                if(equal >= 0) {
                    // Do not confuse the quality of a tool with a formula describing its products.
                    String lhs = QualityExpression.key(formula.substring(0, equal)).replaceFirst("^q", "");
                    if(!lhs.equals(QualityExpression.key(recipe.name))) continue;
                    rhs = formula.substring(equal + 1);
                }
                QualityExpression parsed = QualityExpression.tex(rhs);
                Set<String> vars = parsed.variables();
                if(vars.isEmpty()) continue;
                boolean statsOnly = vars.stream().allMatch(v -> stat(v) != null);
                if(statsOnly) {
                    if(cap == null) cap = parsed.bind(v -> m.statPort(stat(v)).id);
                } else if(base == null) {
                    // A candidate must bind every symbol unambiguously to this recipe.
                    QualityExpression bound = parsed.bind(v -> {
                        Port p = m.resolve(v);
                        return p == null ? null : p.id;
                    });
                    if(bound.variables().stream().anyMatch(v -> m.port(v).kind == Kind.MATERIAL)) {
                        base = bound; m.sourceFormula = formula;
                    } else while(m.ports.size() > before) m.ports.remove(m.ports.size() - 1);
                }
            } catch(IllegalArgumentException ignored) {
                while(m.ports.size() > before) m.ports.remove(m.ports.size() - 1);
            }
        }
        if(base != null) {
            m.expression = cap == null ? base.toString() : "softcap(" + base + "," + cap + ")";
            m.estimated = cap == null && !recipe.quality.isEmpty();
        } else {
            for(String name : recipe.quality) if(STATS.containsKey(name)) m.statPort(name);
            String inputs = join(m.ports, Kind.MATERIAL), tools = join(m.ports, Kind.TOOL);
            String stats = String.join(",", m.ports.stream().filter(p -> p.kind == Kind.ATTRIBUTE || p.kind == Kind.SKILL).map(p -> p.id).toArray(String[]::new));
            if(!inputs.isEmpty()) {
                String value = "mean(" + inputs + ")";
                if(!tools.isEmpty()) value = "(3*" + value + "+mean(" + tools + "))/4";
                m.expression = stats.isEmpty() ? value : "softcap(" + value + ",geomean(" + stats + "))";
            }
        }
        return m;
    }
    private static String join(List<Port> ports, Kind kind) { return String.join(",", ports.stream().filter(p -> p.kind == kind).map(p -> p.id).toArray(String[]::new)); }
    public static boolean toolMatches(String tool, String item) {
        if(item == null) return false;
        String wanted = WikiRecipes.normalize(tool), actual = WikiRecipes.normalize(item);
        return wanted.equals(actual) || (wanted.equals("cauldron") && Set.of("clay cauldron", "metal cauldron").contains(actual));
    }
    public Port port(String id) { return ports.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    private Port add(String name, String resource, Kind kind) {
        for(Port p : ports) if(p.name.equalsIgnoreCase(name) && p.kind == kind) return p;
        String prefix = kind == Kind.MATERIAL ? "i" : kind == Kind.TOOL ? "t" : kind == Kind.ATTRIBUTE ? "a" : "s";
        Port p = new Port(prefix + (ports.stream().filter(v -> v.kind == kind).count() + 1), name, resource, kind); ports.add(p); return p;
    }
    private Port statPort(String name) { return add(name, "gfx/hud/chr/" + STATS.get(name), ATTRIBUTES.contains(name) ? Kind.ATTRIBUTE : Kind.SKILL); }
    private static String stat(String name) {
        String key = QualityExpression.key(name);
        Map<String,String> abbreviations = Map.ofEntries(Map.entry("per","Perception"), Map.entry("cha","Charisma"),
            Map.entry("sew","Sewing"), Map.entry("smi","Smithing"), Map.entry("car","Carpentry"), Map.entry("carp","Carpentry"),
            Map.entry("coo","Cooking"), Map.entry("far","Farming"), Map.entry("sur","Survival"), Map.entry("surv","Survival"),
            Map.entry("mas","Masonry"), Map.entry("exp","Exploration"), Map.entry("ste","Stealth"));
        if(abbreviations.containsKey(key)) return abbreviations.get(key);
        for(Map.Entry<String, String> s : STATS.entrySet()) if(key.equals(QualityExpression.key(s.getKey())) || key.equals(s.getValue())) return s.getKey();
        return null;
    }
    private Port resolve(String symbol) {
        String key = QualityExpression.key(symbol).replaceFirst("^avg", "");
        if(stat(key) != null) return statPort(stat(key));
        List<Port> matches = new ArrayList<>();
        for(Port p : ports) {
            String name = QualityExpression.key(p.name);
            if(name.equals(key)) return p;
            if(key.length() >= 3 && name.contains(key)) matches.add(p);
        }
        // Common explicit wiki shorthand; reject collisions such as two different metal groups.
        if(matches.size() == 1) return matches.get(0);
        if(key.equals("metal")) {
            matches.clear(); for(Port p : ports) if(p.kind == Kind.MATERIAL && p.name.startsWith("Bar of ")) matches.add(p);
            if(matches.size() == 1) return matches.get(0);
        }
        // Water used by boiling formulas is often omitted from the consumed-material list.
        if(key.equals("water") && ports.stream().anyMatch(p -> p.name.equals("Cauldron"))) return add("Water", "", Kind.MATERIAL);
        return null;
    }
}
