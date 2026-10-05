package nurgling.craft;

import java.math.BigDecimal;
import java.util.*;

/** External inputs for repeated quality chains. Each result repeats its material
 * ancestry; quality edges do not imply output yields or automatic batch planning. */
public final class FlowMaterials {
    private FlowMaterials() {}
    public static final class Row {
        public final AtlasCatalog.Material material;
        public final boolean optional;
        public BigDecimal total = BigDecimal.ZERO;
        public boolean unknown;
        Row(AtlasCatalog.Material material) { this.material=material; optional=material.optional; }
        public String quantity() {
            String value=total.stripTrailingZeros().toPlainString();
            return (unknown ? total.signum()==0 ? "?" : value+" + ?" : value)
                + (material.unit.isEmpty() ? "" : " "+material.unit);
        }
    }
    public static final class Summary {
        public final List<Row> rows;
        public final List<String> unresolved;
        public Summary(List<Row> rows,List<String> unresolved) { this.rows=List.copyOf(rows); this.unresolved=List.copyOf(unresolved); }
    }
    public static Summary summarize(CraftFlow graph, AtlasCatalog catalog, Map<String,QualityModel> models) {
        Map<String,Row> totals=new LinkedHashMap<>(); List<String> unresolved=new ArrayList<>();
        Map<String,Integer> counts=craftCounts(graph,catalog,models);
        for(CraftFlow.Node node : graph.nodes) {
            if(node.output()) continue;
            AtlasCatalog.Recipe recipe=catalog.get(node.recipe);
            if(recipe==null) { unresolved.add(node.recipe); continue; }
            QualityModel model=models.computeIfAbsent(node.recipe,k -> QualityModel.of(recipe));
            // Staged/conditional source compositions are not additive quantities.
            if(recipe.wiki!=null && !recipe.recorded && !recipe.wiki.quantitiesKnown) {
                unresolved.add(recipe.name); continue;
            }
            for(AtlasCatalog.Material material : recipe.inputs) {
                QualityModel.Port port=model.ports.stream().filter(p -> p.kind==QualityModel.Kind.MATERIAL
                    && p.name.equalsIgnoreCase(material.name)).findFirst().orElse(null);
                if(port!=null && graph.incoming(node.id,port.id)!=null) continue;
                String identity=material.key().isEmpty() ? "name:"+WikiRecipes.normalize(material.name) : material.key();
                // Units and optional groups must never be silently mixed.
                String key=identity+"\u0000"+material.unit+"\u0000"+material.optional;
                Row row=totals.computeIfAbsent(key,k -> new Row(material));
                if(material.count<0) row.unknown=true;
                else row.total=row.total.add(BigDecimal.valueOf(material.count).multiply(BigDecimal.valueOf(counts.getOrDefault(node.id,1))));
            }
        }
        List<Row> rows=new ArrayList<>(totals.values());
        rows.sort(Comparator.comparing((Row row) -> row.optional).thenComparing(row -> row.material.name,String.CASE_INSENSITIVE_ORDER)
            .thenComparing(row -> row.material.unit));
        return new Summary(rows,unresolved);
    }
    private static Map<String,Integer> craftCounts(CraftFlow graph, AtlasCatalog catalog, Map<String,QualityModel> models) {
        Map<String,Integer> counts=new HashMap<>();
        for(CraftFlow.Node result : graph.nodes) if(result.output()) {
            CraftFlow.Edge input=graph.incoming(result.id,"q"); if(input==null) continue;
            Set<String> visited=new HashSet<>(); Deque<String> pending=new ArrayDeque<>(); pending.add(input.from);
            while(!pending.isEmpty()) {
                String id=pending.removeFirst(); if(!visited.add(id)) continue;
                CraftFlow.Node node=graph.node(id); if(node==null || node.output()) continue;
                counts.merge(id,result.crafts,Integer::sum);
                AtlasCatalog.Recipe recipe=catalog.get(node.recipe); if(recipe==null) continue;
                QualityModel model=models.computeIfAbsent(node.recipe,k -> QualityModel.of(recipe));
                // Tools are reused, and stats are qualities rather than consumed materials.
                for(QualityModel.Port p : model.ports) if(p.kind==QualityModel.Kind.MATERIAL) {
                    CraftFlow.Edge edge=graph.incoming(id,p.id); if(edge!=null) pending.add(edge.from);
                }
            }
        }
        return counts;
    }
}
