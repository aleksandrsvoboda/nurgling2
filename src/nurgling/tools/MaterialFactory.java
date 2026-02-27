package nurgling.tools;

import haven.*;
import haven.render.Pipe;
import haven.res.lib.vmat.Materials;
import nurgling.NGob;
import nurgling.NStyle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MaterialFactory {
    // Cache for loaded TexR objects to avoid duplicate loading
    private static final Map<String, TexR> texCache = new ConcurrentHashMap<>();

    // Texture path constants
    private static final String TEX_PINEFREE = "nurgling/tex/pinefree-tex";
    private static final String TEX_PINEFULL = "nurgling/tex/pinefull-tex";
    private static final String TEX_PINENF = "nurgling/tex/pinenf-tex";
    
    private static TexR getTexR(String path, int layer) {
        String key = path + "#" + layer;
        return texCache.computeIfAbsent(key, k -> {
            try {
                Resource res = Resource.local().loadwait(path);
                TexR texr = res.layer(TexR.class, layer);
                if (texr == null) {
                    System.err.println("[MaterialFactory] ERROR: TexR layer " + layer + " not found in resource: " + path);
                }
                return texr;
            } catch (Exception e) {
                System.err.println("[MaterialFactory] ERROR: Exception loading texture resource: " + path + " layer " + layer);
                e.printStackTrace();
                return null;
            }
        });
    }

    private static TexR getTexR(String path) {
        return texCache.computeIfAbsent(path, k -> {
            try {
                Resource res = Resource.local().loadwait(path);
                TexR texr = res.layer(TexR.class);
                if (texr == null) {
                    System.err.println("[MaterialFactory] ERROR: TexR not found in resource: " + path);
                }
                return texr;
            } catch (Exception e) {
                System.err.println("[MaterialFactory] ERROR: Exception loading texture resource: " + path);
                e.printStackTrace();
                return null;
            }
        });
    }

    /**
     * Get cached chest materials to prevent shader explosion.
     * By caching the Material objects, all chests of the same status share the same shader.
     */
    private static Map<Integer, Material> getCachedChestMaterials(String name, Status status) {
        getMaterialsCache.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
        Map<Status, Map<Integer, Material>> statusMap = getMaterialsCache.get(name);

        return statusMap.computeIfAbsent(status, s -> {
            Map<Integer, Material> result = new HashMap<>();
            TexR rt1 = Resource.remote().loadwait("gfx/terobjs/subst/wroughtiron").layer(TexR.class, 0);

            switch (status) {
                case FREE: {
                    TexR rt0 = getTexR("nurgling/tex/pinefree-tex", 0);
                    result.put(0, constructMaterial(rt0, null));
                    result.put(1, constructMaterial(rt1, null));
                    break;
                }
                case FULL: {
                    TexR rt0 = getTexR("nurgling/tex/pinefull-tex", 0);
                    result.put(0, constructMaterial(rt0, null));
                    result.put(1, constructMaterial(rt1, null));
                    break;
                }
                case NOTFREE: {
                    TexR rt0 = getTexR("nurgling/tex/pinenf-tex", 0);
                    result.put(0, constructMaterial(rt0, null));
                    result.put(1, constructMaterial(rt1, null));
                    break;
                }
            }
            return result;
        });
    }

    /**
     * Helper method to create standard material mapping for containers
     */
    private static Map<Integer, Material> createContainerMaterials(String tex0Path, int layer0, String tex1Path, int layer1, Material baseMat) {
        TexR rt0 = getTexR(tex0Path, layer0);
        TexR rt1 = getTexR(tex1Path, layer1);
        Map<Integer, Material> result = new HashMap<>();
        result.put(0, constructMaterial(rt0, baseMat));
        result.put(1, constructMaterial(rt1, baseMat));
        return result;
    }

    public static Map<Integer, Material> getMaterials(String name, Status status, Material mat) {
        switch (name){
            case "gfx/terobjs/cupboard":
            case "gfx/terobjs/cheeserack":
                switch (status)
                {
                    case FREE:
                        return createContainerMaterials(TEX_PINEFREE, 0, TEX_PINEFREE, 2, mat);
                    case FULL:
                        return createContainerMaterials(TEX_PINEFULL, 0, TEX_PINEFREE, 2, mat);
                    case NOTFREE:
                        return createContainerMaterials(TEX_PINENF, 0, TEX_PINEFREE, 2, mat);
                }
                break;
            case "gfx/terobjs/chest":
                    return getCachedChestMaterials(name, status);

            case "gfx/terobjs/map/jotunclam":
                switch (status) {
                    case FREE: {
                        TexR rt0 = getTexR("alttex/jotun/free");
                        Map<Integer, Material> result = new HashMap<>();
                        result.put(0, constructMaterial(rt0, mat));
                        return result;
                    }
                    case FULL: {
                        TexR rt0 = getTexR("alttex/jotun/full");
                        Map<Integer, Material> result = new HashMap<>();
                        result.put(0, constructMaterial(rt0, mat));
                        return result;
                    }
                    case NOTFREE: {
                        TexR rt0 = getTexR("alttex/jotun/notfree");
                        Map<Integer, Material> result = new HashMap<>();
                        result.put(0, constructMaterial(rt0, mat));
                        return result;
                    }
                }
                break;
            case "gfx/terobjs/barrel":
                switch (status) {
                    case FREE: {
                        TexR rt0 = getTexR("alttex/barrel/free");
                        Map<Integer, Material> result = new HashMap<>();
                        // Barrel has material IDs 1, 2, 3 - map all of them
                        result.put(1, constructMaterial(rt0, mat));
                        result.put(2, constructMaterial(rt0, mat));
                        result.put(3, constructMaterial(rt0, mat));
                        return result;
                    }
                    case FULL: {
                        TexR rt0 = getTexR("alttex/barrel/full");
                        Map<Integer, Material> result = new HashMap<>();
                        // Barrel has material IDs 1, 2, 3 - map all of them
                        result.put(1, constructMaterial(rt0, mat));
                        result.put(2, constructMaterial(rt0, mat));
                        result.put(3, constructMaterial(rt0, mat));
                        return result;
                    }
                }
                break;
            case "gfx/terobjs/dframe":
                switch (status) {
                    case FREE: {
                        TexR rt0 = getTexR("alttex/dframe/free");
                        Map<Integer, Material> result = new HashMap<>();
                        // Empty dframes now forced to render material ID 1
                        result.put(1, constructMaterial(rt0, mat));
                        return result;
                    }
                    case FULL: {
                        TexR rt0 = getTexR("alttex/dframe/full");
                        Map<Integer, Material> result = new HashMap<>();
                        // Full dframes use material ID 1
                        result.put(1, constructMaterial(rt0, mat));
                        return result;
                    }
                    case NOTFREE: {
                        TexR rt0 = getTexR("alttex/dframe/notfree");
                        Map<Integer, Material> result = new HashMap<>();
                        // Map ID 1
                        result.put(1, constructMaterial(rt0, mat));
                        return result;
                    }
                }
                break;
            case "gfx/terobjs/ttub":
                switch (status) {
                    case FREE:
                    case WARNING: {
                        TexR rt0 = getTexR("nurgling/tex/pinefull-tex", 0);
                        Map<Integer, Material> result = new HashMap<>();
                        // ID 1 = wooden planks (show status color: yellow/warning)
                        result.put(1, constructMaterial(rt0, mat));
                        // ID 2 = metal bands - leave unmapped for natural metal color
                        return result;
                    }
                    case INWORK: {
                        TexR rt0 = getTexR("nurgling/tex/pinenf-tex", 0);
                        Map<Integer, Material> result = new HashMap<>();
                        // ID 1 = wooden planks (show status color: red/not free)
                        result.put(1, constructMaterial(rt0, mat));
                        // ID 2 = metal bands - leave unmapped for natural metal color
                        return result;
                    }
                    case READY: {
                        TexR rt0 = getTexR("nurgling/tex/pinefree-tex", 0);
                        Map<Integer, Material> result = new HashMap<>();
                        // ID 1 = wooden planks (show status color: green/ready)
                        result.put(1, constructMaterial(rt0, mat));
                        // ID 2 = metal bands - leave unmapped for natural metal color
                        return result;
                    }
                }
                break;
        }
        return null;
    }


    public static Material constructMaterial(TexR texR, Material mat)
    {
        if (texR == null) {
            throw new RuntimeException("Cannot construct material: TexR is null");
        }
        Light.PhongLight lp = new Light.PhongLight(true, new FColor(0, 0, 0, 1.0f),
                new FColor(0.8f, 0.8f, 0.8f, 1.0f),
                new FColor(0.643137f, 0.643137f, 0.643137f, 1.0f),
                new FColor(0.643137f, 0.643137f, 0.643137f, 1.0f), 0.0f);
        Light.CelShade lc = new Light.CelShade(true, false);
        if(mat!=null) {
            if (mat.states instanceof Pipe.Op.Composed)
                for (Pipe.Op p : ((Pipe.Op.Composed) mat.states).ops) {
                    if (p instanceof Light.PhongLight) {
                        lp = (Light.PhongLight) p;
                    } else if (p instanceof Light.CelShade) {
                        lc = (Light.CelShade) p;
                    }
                }
        }
        return new Material(lp, texR.tex().draw, texR.tex().clip,lc);
    }

    public enum Status{
        NOTDEFINED,
        FREE,
        FULL,
        READY,
        INWORK,
        NOTFREE,
        WARNING
    }
    public static final Map<String,Map<Status, Materials>> materialsCashe = new ConcurrentHashMap<>();
    private static final Map<String, Map<Status, Map<Integer, Material>>> getMaterialsCache = new ConcurrentHashMap<>();


    public static Status getStatus(String name, int mask) {
        switch (name)
        {
            case "gfx/terobjs/chest":
            case "gfx/terobjs/cupboard":
            {
                int freeMask = VSpec.chest_state.get(NStyle.Container.FREE);
                int fullMask = VSpec.chest_state.get(NStyle.Container.FULL);

                if((mask & ~freeMask) == 0) {
                    return Status.FREE;
                }
                else if((mask & fullMask) == fullMask)
                {
                    return Status.FULL;
                }
                else
                {
                    return Status.NOTFREE;
                }
            }
            case "gfx/terobjs/map/jotunclam":
            {
                if((mask & ~VSpec.jotun_state.get(NStyle.Container.FREE)) == 0) {
                    return Status.FREE;
                }
                else if((mask & VSpec.jotun_state.get(NStyle.Container.FULL)) == VSpec.jotun_state.get(NStyle.Container.FULL))
                {
                    return Status.FULL;
                }
                else
                {
                    return Status.NOTFREE;
                }
            }
            case "gfx/terobjs/ttub":
            {
                if ((mask & 8) != 0) {
                    return Status.READY;
                } else if ((mask & 4) != 0) {
                    return Status.INWORK;
                } else if ((mask & 2) != 0) {
                    return Status.FREE;
                } else if ((mask & 1) != 0 || mask == 0) {
                    return Status.WARNING;
                }
                break;
            }
            case "gfx/terobjs/dframe":
            case "gfx/terobjs/cheeserack": {
                if(mask == 0)
                    return Status.FREE;
                else if(mask == 1)
                    return Status.NOTFREE;
                else if(mask == 2)
                    return Status.FULL;
                break;
            }
            case "gfx/terobjs/barrel": {
                if(mask == 4)
                    return Status.FULL;
                else
                    return Status.FREE;
            }
        }
        return Status.NOTDEFINED;
    }

}
