package nurgling.render;

import haven.*;
import haven.iosys.tk.*;
import java.awt.Color;
import java.lang.reflect.*;
import java.util.Arrays;

/** Real MapView lighting with terrain-identified interiors, server light and night vision. */
public class InteriorLightingTest {
    static void require(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static void main(String[] args) throws Exception {
        nurgling.NConfig.getGlobalInstance();
        NGfx.Settings previous = NGfx.get();
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        try {
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            UI ui = new UI(window, new Audio.Root(haven.iosys.audio.DummyAudio.instance), Coord.of(64,64), null);
            ui.env = window.env();
            Glob glob = new Glob(null);
            MapView view = new MapView(Coord.of(64,64), glob, Coord2d.z, -1);
            view.ui = ui;
            NGfx.set(NGfx.classic.with("enabled",true).with("worldlight",true).with("bettershadows",true));
            Method update = MapView.class.getDeclaredMethod("amblight"); update.setAccessible(true);
            glob.lightamb = new Color(30,25,20);
            glob.lightdif = glob.tlightdif = Color.BLACK;
            glob.lightspc = new Color(10,8,6);
            glob.blightamb = new Color(160,150,140);
            glob.blightdif = new Color(130,120,110); // Black raw diffuse lifted by night vision.
            glob.blightspc = new Color(40,35,30);
            // Vulkan uses raw server light; legacy night-vision values deliberately differ.
            DirLight baseline = new DirLight(glob.lightamb,glob.lightdif,glob.lightspc,Coord3f.zu);
            field(MapView.class,"rainCloudCover").setFloat(view,1);
            field(MapView.class,"clearWeatherLight").setFloat(view,1);
            view.sceneDebug.rain(true);
            for(int minutes : new int[]{0,360,720,1080}) {
                view.sceneDebug.time(minutes);
                update.invoke(view);
                require(Arrays.equals(view.amblight.amb,baseline.amb),"Interior ambient follows outdoor clock/rain");
                require(Arrays.equals(view.amblight.dif,baseline.dif),"Interior diffuse follows outdoor clock/rain");
                require(Arrays.equals(view.amblight.spc,baseline.spc),"Interior specular changed");
                require(view.basic.state().get(WorldLighting.Smooth.slot)==null,"Interior lost classic cel lighting");
                require(RainLighting.intensity(view.weather())==0,"Debug rain leaked indoors");
            }
            // Interior server light can be non-black: terrain must take precedence.
            glob.lightdif = glob.tlightdif = Color.WHITE;
            baseline = new DirLight(glob.lightamb,glob.lightdif,glob.lightspc,Coord3f.zu);
            glob.map.sets[0] = new Resource.Spec(Resource.local(), "gfx/tiles/field");
            glob.map.sets[1] = new Resource.Spec(Resource.local(), "gfx/tiles/mine");
            MCache.Grid grid = glob.map.new Grid(Coord.z);
            grid.seq = 0;
            glob.map.grids.put(Coord.z, grid);
            require(view.outdoorLighting(), "Outdoor terrain disabled");
            for(String tile : new String[]{"mine", "cave", "nil", "deepcave", "deeptangle"}) {
                glob.map.sets[1] = new Resource.Spec(Resource.local(), "gfx/tiles/" + tile);
                grid.tiles[grid.tiles.length - 1] = 1; grid.seq++;
                for(int minutes : new int[]{0,360,720,1080}) {
                    view.sceneDebug.time(minutes);
                    require(!view.outdoorLighting(), "Non-black interior light mistaken for outdoors: " + tile);
                    update.invoke(view);
                    require(Arrays.equals(view.amblight.amb, baseline.amb) && Arrays.equals(view.amblight.dif, baseline.dif),
                            "Outdoor time/rain recolored " + tile);
                    require(view.basic.state().get(WorldLighting.Smooth.slot)==null, "Enhanced cel override inside " + tile);
                }
                grid.tiles[grid.tiles.length - 1] = 0; grid.seq++;
                require(view.outdoorLighting(), "Tile update did not restore outdoors");
            }
            MCache.Grid neighbor = glob.map.new Grid(Coord.of(1,0)); neighbor.seq = 0;
            neighbor.tiles[0] = 1; glob.map.grids.put(neighbor.gc, neighbor);
            require(!view.outdoorLighting(), "Paved interior not recognized by neighboring grid");
            neighbor.removed = true;
            require(view.outdoorLighting(), "Removed interior grid leaks into outdoor scene");
            glob.map.grids.clear();
            MCache.Grid replacement = glob.map.new Grid(Coord.z); replacement.seq = grid.seq;
            glob.map.grids.put(Coord.z, replacement);
            require(view.outdoorLighting(), "Replaced grid retained old environment");
            replacement.tiles[0] = 2; replacement.seq++;
            InteriorTiles detector = new InteriorTiles();
            require(detector.inside(glob.map,Coord.z)==null, "Unresolved terrain treated as known outdoors");
            glob.map.sets[2] = new Resource.Spec(Resource.local(), "gfx/tiles/nil");
            require(Boolean.TRUE.equals(detector.inside(glob.map,Coord.z)), "Unresolved tile was cached permanently");
            MapView other = new MapView(Coord.of(64,64), new Glob(null), Coord2d.z, 42);
            other.glob.lightdif = other.glob.tlightdif = Color.WHITE;
            require(!other.outdoorLighting(), "Unloaded player terrain enabled outdoor effects");
            require(other.glob.map.grids.isEmpty(), "Detector requested or borrowed another session's terrain");
            other.dispose();
            glob.map.grids.clear();
            glob.tlightdif = Color.BLACK;
            glob.lightdif = Color.WHITE; // Entry transition still has outdoor current light.
            require(!view.outdoorLighting(),"Interior transition waits for fade");
            update.invoke(view);
            require(Arrays.equals(view.amblight.dif,baseline.dif),"Outdoor transition light recolored indoors");
            glob.lightdif = Color.BLACK; glob.tlightdif = new Color(1,1,2);
            require(view.outdoorLighting(),"Faint moonlight misclassified as indoors");
            update.invoke(view);
            require(view.basic.state().get(WorldLighting.Smooth.slot)!=null,"Outdoor enhanced lighting not restored");
            require(!Arrays.equals(view.amblight.dif,baseline.dif),"Outdoor palette disabled");
            glob.tlightdif = Color.BLACK;
            update.invoke(view);
            require(view.basic.state().get(WorldLighting.Smooth.slot)==null,"Smooth lighting persisted after re-entry");
            glob.lightdif = glob.tlightdif = Color.WHITE;
            glob.map.grids.put(Coord.z, replacement); // nil, despite non-black incoming light.
            update.invoke(view);
            NPostFX.Manager effects = new NPostFX.Manager(view,()->{},()->{});
            field(NPostFX.Manager.class,"cur").set(effects,NGfx.get().with("wet",true));
            field(NPostFX.Manager.class,"wetness").setFloat(effects,1);
            field(NPostFX.Manager.class,"snowcover").setFloat(effects,1);
            SceneFX.Shafts shafts = new SceneFX.Shafts(view);
            shafts.sun = new float[]{1,1,1,1};
            field(NPostFX.Manager.class,"shafts").set(effects,shafts);
            WaterSurface water = new WaterSurface(view);
            water.rainIntensity = 1;
            field(NPostFX.Manager.class,"waterSurface").set(effects,water);
            effects.tick(view,0);
            require(water.sheltered && water.rainIntensity==0,"Tile-detected interior did not calm water");
            require(shafts.sun==null,"Sun shafts persisted indoors");
            require(field(NPostFX.Manager.class,"wetness").getFloat(effects)==0,"Outdoor wetness persisted indoors");
            require(field(NPostFX.Manager.class,"snowcover").getFloat(effects)==0,"Outdoor snow persisted indoors");
            require(!WorldLighting.outdoors(null,null),"Unknown environment manufactured sunlight");
            require(WorldLighting.outdoors(Color.WHITE,null),"Current raw light fallback missing");
            glob.map.grids.clear();
            MCache.Grid outdoors = glob.map.new Grid(Coord.z); outdoors.seq=0;
            glob.map.grids.put(Coord.z,outdoors);
            effects.tick(view,0);
            require(!water.sheltered,"Outdoor waves not restored on exit");
            water.dispose(); shafts.dispose(); view.dispose();
            System.out.println("PASS: mine/cave/nil and paved interiors with non-black server light; day/night/rain, grid updates and reloads, session isolation, classic cel and weather carryover");
        } finally { NGfx.set(previous); window.dispose(); toolkit.dispose(); }
        System.exit(0);
    }
}
