package nurgling.craft;

import java.util.*;
import org.json.*;

/** Per-character quality DAG. Edges carry quality, never counts or game actions. */
public final class CraftFlow {
    public static final int LIMIT = 128;
    public static final class Node {
        public final String id, recipe;
        public double x, y;
        public boolean expanded;
        public int crafts = 1;
        public String formula = "";
        public String inputSignature = "";
        public final Map<String, String> values = new LinkedHashMap<>();
        public final Map<String, String> inputNames = new LinkedHashMap<>();
        public final Map<String, Position> positions = new LinkedHashMap<>();
        Node(String id, String recipe, double x, double y) { this.id = id; this.recipe = recipe; this.x = x; this.y = y; }
        public boolean output() { return recipe.isEmpty(); }
    }
    public static final class Position {
        public final double x, y;
        public Position(double x, double y) { this.x = coordinate(x); this.y = coordinate(y); }
    }
    public static final class Edge {
        public final String from, to, port;
        Edge(String from, String to, String port) { this.from = from; this.to = to; this.port = port; }
    }
    public interface LiveValues { Double get(QualityModel.Port port); }
    public static final class Result {
        public final Double value; public final String error; public final boolean estimated;
        Result(Double value, String error, boolean estimated) { this.value = value; this.error = error; this.estimated = estimated; }
    }
    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public double panX = 40, panY = 50, zoom = 1;
    public int version;
    public Node node(String id) { return nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElse(null); }
    public Edge incoming(String id, String port) { return edges.stream().filter(e -> e.to.equals(id) && e.port.equals(port)).findFirst().orElse(null); }
    public Node add(String recipe, double x, double y) {
        if(nodes.size() >= LIMIT) throw new IllegalArgumentException("Maximum 128 nodes");
        Node node = new Node(UUID.randomUUID().toString(), recipe, x, y); nodes.add(node); version++; return node;
    }
    public void remove(String id) { nodes.removeIf(n -> n.id.equals(id)); edges.removeIf(e -> e.from.equals(id) || e.to.equals(id)); version++; }
    public void disconnect(String id, String port) { edges.removeIf(e -> e.to.equals(id) && e.port.equals(port)); version++; }
    public void connect(String from, String to, String port) {
        if(node(from) == null || node(to) == null || node(from).output() || from.equals(to)) throw new IllegalArgumentException("Invalid connection");
        Set<String> reachable = new HashSet<>(); Deque<String> todo = new ArrayDeque<>(); todo.add(to);
        while(!todo.isEmpty()) {
            String id = todo.removeFirst(); if(!reachable.add(id)) continue;
            if(id.equals(from)) throw new IllegalArgumentException("Cycle");
            for(Edge e : edges) if(e.from.equals(id)) todo.add(e.to);
        }
        disconnect(to, port); edges.add(new Edge(from, to, port)); version++;
    }
    public Map<String, Result> evaluate(AtlasCatalog catalog, LiveValues live, Map<String, QualityModel> models) {
        Map<String, Result> result = new HashMap<>();
        for(Node n : nodes) calculate(n, catalog, live, models, result, new HashSet<>());
        return result;
    }
    private Result calculate(Node n, AtlasCatalog catalog, LiveValues live, Map<String, QualityModel> models,
                             Map<String, Result> results, Set<String> stack) {
        if(results.containsKey(n.id)) return results.get(n.id);
        if(!stack.add(n.id)) return new Result(null, "Cycle", false);
        boolean[] estimate = {false}; Result result;
        try {
            if(n.output()) {
                Edge edge = incoming(n.id, "q");
                if(edge == null) throw new IllegalArgumentException("Connect a recipe output");
                result = calculate(node(edge.from), catalog, live, models, results, stack);
            } else {
                AtlasCatalog.Recipe recipe = catalog.get(n.recipe);
                if(recipe == null) throw new IllegalArgumentException("Recipe unavailable");
                QualityModel model = models.computeIfAbsent(n.recipe, key -> QualityModel.of(recipe));
                String signature = inputSignature(model);
                reconcileInputs(n, model, signature, recipe);
                estimate[0] = n.formula.isBlank() && model.estimated;
                String formula = n.formula.isBlank() ? model.expression : n.formula;
                if(formula.isBlank()) throw new IllegalArgumentException("Enter a quality formula");
                double q = QualityExpression.parse(formula).evaluate(id -> {
                    QualityModel.Port port = model.port(id);
                    if(port == null) throw new IllegalArgumentException("Unknown input: " + id);
                    Edge edge = incoming(n.id, id);
                    if(edge != null) {
                        Result upstream = calculate(node(edge.from), catalog, live, models, results, stack);
                        if(upstream.value == null) throw new IllegalArgumentException(port.name + ": " + upstream.error);
                        estimate[0] |= upstream.estimated; return upstream.value;
                    }
                    String override = n.values.getOrDefault(id, "").trim();
                    Double value;
                    if(!override.isEmpty()) value = enteredValue(override);
                    else value = live.get(port);
                    if(value == null || !Double.isFinite(value) || value < 0) throw new IllegalArgumentException(port.name + ": ?");
                    return value;
                });
                if(q < 0) throw new IllegalArgumentException("Negative quality");
                result = new Result(q, "", estimate[0]);
            }
        } catch(IllegalArgumentException e) { result = new Result(null, e.getMessage(), estimate[0]); }
        stack.remove(n.id); results.put(n.id, result); return result;
    }
    public static double enteredValue(String text) {
        String value = text.trim();
        if(value.matches("[+]?[0-9]+,[0-9]+")) value = value.replace(',', '.');
        double q = QualityExpression.parse(value).evaluate(v -> null);
        if(q < 0) throw new IllegalArgumentException("Negative quality");
        return q;
    }
    private static String inputSignature(QualityModel model) {
        StringBuilder description = new StringBuilder();
        for(QualityModel.Port p : model.ports) description.append(p.id).append(':').append(p.kind).append(':').append(p.name).append('\n');
        return UUID.nameUUIDFromBytes(description.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private void reconcileInputs(Node n, QualityModel model, String signature, AtlasCatalog.Recipe recipe) {
        Map<String,String> current = new LinkedHashMap<>();
        for(QualityModel.Port p : model.ports) current.put(p.id, p.kind + ":" + WikiRecipes.normalize(p.name));
        if(!n.inputSignature.isEmpty() && !n.inputSignature.equals(signature)) {
            // Exact migration of the previously shipped erroneous Kiln + Will layout.
            String oldKiln = UUID.nameUUIDFromBytes("i1:MATERIAL:Clay\na1:ATTRIBUTE:Will\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if(recipe.resource.equals("wiki:8910") && n.inputSignature.equals(oldKiln) && n.formula.isBlank()
                    && model.ports.size() == 1 && model.ports.get(0).name.equals("Clay")) {
                n.values.remove("a1"); n.inputNames.clear(); n.inputNames.put("i1", "MATERIAL:clay");
            }
            if(n.inputNames.isEmpty() && recipe.inputs.stream().anyMatch(p -> !p.choices.isEmpty())) {
                // The initial compiler stored alternatives as separate unknown-quantity inputs.
                // Recover only when the exact old layout hash verifies every former binding.
                List<AtlasCatalog.Material> expanded = new ArrayList<>();
                for(AtlasCatalog.Material p : recipe.inputs) { if(p.choices.isEmpty()) expanded.add(p); else expanded.addAll(p.choices); }
                QualityModel legacy = QualityModel.of(new AtlasCatalog.Recipe(recipe.resource, recipe.name, recipe.group,
                    expanded, recipe.outputs, recipe.quality, recipe.tools, recipe.recorded, recipe.wiki));
                if(n.inputSignature.equals(inputSignature(legacy)))
                    for(QualityModel.Port p : legacy.ports) n.inputNames.put(p.id, p.kind + ":" + WikiRecipes.normalize(p.name));
            }
            if(n.inputNames.isEmpty()) throw new IllegalArgumentException("Recipe inputs changed; add this recipe again to review its new inputs");
            Map<String,String> remap = new HashMap<>();
            for(Map.Entry<String,String> old : n.inputNames.entrySet()) {
                List<String> matches = new ArrayList<>();
                current.forEach((id,name) -> { if(name.equals(old.getValue())) matches.add(id); });
                if(matches.isEmpty()) for(AtlasCatalog.Material group : recipe.inputs) {
                    if(group.choices.stream().anyMatch(option -> old.getValue().equals("MATERIAL:" + WikiRecipes.normalize(option.name))))
                        for(QualityModel.Port p : model.ports) if(p.kind == QualityModel.Kind.MATERIAL && p.name.equals(group.name)) matches.add(p.id);
                }
                if(matches.size() == 1) remap.put(old.getKey(), matches.get(0));
            }
            Set<String> used = new HashSet<>();
            n.values.forEach((id,value) -> { if(!value.isBlank()) used.add(id); });
            for(Edge edge : edges) if(edge.to.equals(n.id)) used.add(edge.port);
            QualityExpression formula = n.formula.isBlank() ? null : QualityExpression.parse(n.formula);
            if(formula != null) used.addAll(formula.variables());
            if(!remap.keySet().containsAll(used)) throw new IllegalArgumentException("Recipe inputs changed; add this recipe again to review its new inputs");
            if(used.stream().map(remap::get).distinct().count() != used.size())
                throw new IllegalArgumentException("Several old inputs now form one choice; choose one ingredient before recalculating");
            Map<String,String> values = new LinkedHashMap<>();
            n.values.forEach((id,value) -> { if(remap.containsKey(id)) {
                if(!value.isBlank()) values.put(remap.get(id), value); else values.putIfAbsent(remap.get(id), value);
            } });
            String expression = formula == null ? "" : formula.bind(remap::get).toString();
            for(int i = 0; i < edges.size(); i++) {
                Edge edge = edges.get(i);
                if(edge.to.equals(n.id)) edges.set(i, new Edge(edge.from, edge.to, remap.get(edge.port)));
            }
            n.values.clear(); n.values.putAll(values); n.formula = expression;
            Map<String,Position> positions = new LinkedHashMap<>();
            n.positions.forEach((id,pos) -> { if(remap.containsKey(id)) positions.putIfAbsent(remap.get(id), pos); });
            n.positions.clear(); n.positions.putAll(positions);
        }
        if(!n.inputSignature.equals(signature) || !n.inputNames.equals(current)) {
            n.inputSignature = signature; n.inputNames.clear(); n.inputNames.putAll(current); version++;
        }
    }
    public JSONObject json() {
        JSONArray ns = new JSONArray(), es = new JSONArray();
        for(Node n : nodes) {
            JSONObject positions = new JSONObject(); n.positions.forEach((id,p) -> positions.put(id, new JSONArray().put(p.x).put(p.y)));
            ns.put(new JSONObject().put("id", n.id).put("recipe", n.recipe).put("x", n.x).put("y", n.y)
                .put("expanded", n.expanded).put("formula", n.formula).put("values", n.values).put("inputSignature", n.inputSignature)
                .put("inputNames", n.inputNames).put("positions", positions).put("crafts", n.crafts));
        }
        for(Edge e : edges) es.put(new JSONObject().put("from", e.from).put("to", e.to).put("port", e.port));
        return new JSONObject().put("version", 1).put("nodes", ns).put("edges", es).put("panX", panX).put("panY", panY).put("zoom", zoom);
    }
    public void load(JSONObject data) {
        if(data.getInt("version") != 1) throw new IllegalArgumentException("Unknown flow version");
        CraftFlow fresh = new CraftFlow(); JSONArray ns = data.getJSONArray("nodes"), es = data.getJSONArray("edges");
        if(ns.length() > LIMIT || es.length() > LIMIT * 100) throw new IllegalArgumentException("Flow too large");
        for(int i = 0; i < ns.length(); i++) {
            JSONObject j = ns.getJSONObject(i); String id = j.getString("id");
            if(fresh.node(id) != null) throw new IllegalArgumentException("Duplicate node");
            Node n = new Node(id, j.getString("recipe"), coordinate(j.getDouble("x")), coordinate(j.getDouble("y")));
            n.expanded = j.optBoolean("expanded"); n.formula = j.optString("formula");
            double crafts = j.has("crafts") ? j.getDouble("crafts") : 1;
            if(!Double.isFinite(crafts) || crafts != Math.rint(crafts) || crafts < 1 || crafts > 9999)
                throw new IllegalArgumentException("Craft count must be 1..9999");
            n.crafts = (int)crafts;
            n.inputSignature = j.optString("inputSignature");
            if(n.inputSignature.length() > 64) throw new IllegalArgumentException("Invalid input signature");
            JSONObject positions = j.optJSONObject("positions");
            if(positions != null) {
                if(positions.length() > 100) throw new IllegalArgumentException("Too many source positions");
                for(String key : positions.keySet()) {
                    JSONArray p = positions.getJSONArray(key);
                    if(key.length() > 64 || p.length() != 2) throw new IllegalArgumentException("Invalid source position");
                    n.positions.put(key, new Position(p.getDouble(0), p.getDouble(1)));
                }
            }
            JSONObject names = j.optJSONObject("inputNames");
            if(names != null) {
                if(names.length() > 100) throw new IllegalArgumentException("Too many input names");
                for(String key : names.keySet()) {
                    String name = names.getString(key);
                    if(key.length() > 64 || name.length() > 4096) throw new IllegalArgumentException("Input name too long");
                    n.inputNames.put(key, name);
                }
            }
            if(id.length() > 128 || n.recipe.length() > 1024 || n.formula.length() > 4096) throw new IllegalArgumentException("Flow field too long");
            JSONObject values = j.optJSONObject("values");
            if(values != null) {
                if(values.length() > 100) throw new IllegalArgumentException("Too many inputs");
                for(String key : values.keySet()) {
                    String value = values.getString(key);
                    if(key.length() > 64 || value.length() > 4096) throw new IllegalArgumentException("Input too long");
                    n.values.put(key, value);
                }
            }
            fresh.nodes.add(n);
        }
        for(int i = 0; i < es.length(); i++) { JSONObject j = es.getJSONObject(i); fresh.connect(j.getString("from"), j.getString("to"), j.getString("port")); }
        double z = data.optDouble("zoom", 1); if(!Double.isFinite(z) || z < .35 || z > 1.8) throw new IllegalArgumentException("Invalid zoom");
        double x = coordinate(data.optDouble("panX", 40)), y = coordinate(data.optDouble("panY", 50));
        nodes.clear(); nodes.addAll(fresh.nodes); edges.clear(); edges.addAll(fresh.edges); zoom = z; panX = x; panY = y; version++;
    }
    private static double coordinate(double x) { if(!Double.isFinite(x) || Math.abs(x) > 1_000_000) throw new IllegalArgumentException("Invalid position"); return x; }
}
