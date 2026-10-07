package nurgling.render;

import haven.*;
import haven.res.gfx.fx.rain.Rain;
import haven.res.gfx.fx.snow.Snow;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;

/** Per-view, unsaved preview. Never changes server astronomy, weather or simulation clocks. */
public class SceneDebug implements Disposable {
    private int minutes = -1;
    private boolean rain, heavyRain, snow;
    private Rain rainEffect;
    private Snow snowEffect;

    public boolean hasTime() { return minutes >= 0; }
    /** True while any weather override is active; these force a preview regardless of terrain. */
    public boolean hasWeather() { return rain || snow; }
    public int minutes() { return minutes; }
    public void time(int minutes) { this.minutes = Math.max(0, Math.min(1439, minutes)); }
    public boolean night() { return minutes < 360 || minutes >= 1080; }
    public boolean rain() { return rain; }
    public boolean heavyRain() { return heavyRain; }
    public boolean snow() { return snow; }
    public void rain(boolean enabled) { rain = enabled; if(!enabled) heavyRain = false; }
    public void heavyRain(boolean enabled) { heavyRain = enabled; if(enabled) rain = true; }
    public void snow(boolean enabled) { snow = enabled; }

    public void reset() {
        minutes = -1;
        rain = heavyRain = snow = false;
    }

    /** An illustrative daily light cycle, independent of season and server updates. */
    public DirLight light() {
        double angle = (minutes / 1440.0 - .25) * Math.PI * 2;
        double altitude = Math.sin(angle);
        float day = (float)Math.max(0, Math.min(1, altitude * 3 + .3));
        float gold = day * (float)Math.max(0, 1 - Math.max(0, altitude) * 2);
        Color ambient = mix(new Color(24, 30, 47), new Color(100, 100, 96), day);
        Color diffuse = mix(new Color(35, 45, 61), new Color(180, 170, 155), day);
        diffuse = mix(diffuse, new Color(200, 120, 65), gold * .65f);
        Coord3f direction = Coord3f.o.sadd((float)(.08 + Math.abs(altitude) * 1.1), (float)angle, 1);
        DirLight light = new DirLight(ambient, diffuse, diffuse, direction);
        light.prio(100);
        return light;
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                         Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                         Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Called on the UI tick before attaching weather nodes. Resource loads never block it. */
    public void prepare() {
        if(rain) {
            float rate = heavyRain ? 36000f : 12000f;
            if(rainEffect == null) rainEffect = new Rain(rate);
            else rainEffect.rate = rate;
        }
        if(snow && snowEffect == null) {
            try {
                Collection<Material> materials = new ArrayList<>();
                for(Material.Res material : Resource.remote().load("gfx/fx/snow-1", 1).get().layers(Material.Res.class))
                    materials.add(material.get());
                if(!materials.isEmpty())
                    snowEffect = new Snow(materials.toArray(new Material[0]), 1500f);
            } catch(Loading loading) {
                // Retry on the next tick while the regular resource loader fetches the flakes.
            }
        }
    }

    public Collection<Glob.Weather> weather(Collection<Glob.Weather> live) {
        if(!rain && !snow) return live;
        Collection<Glob.Weather> result = new ArrayList<>();
        for(Glob.Weather weather : live) {
            if(rain && rainEffect != null && weather instanceof Rain) continue;
            if(snow && snowEffect != null && weather instanceof Snow) continue;
            result.add(weather);
        }
        if(rain && rainEffect != null) result.add(rainEffect);
        if(snow && snowEffect != null) result.add(snowEffect);
        return result;
    }

    /** Retire disabled effects only after MapView has detached their render nodes. */
    public void tick(double dt) {
        if(rainEffect != null) {
            if(rain) rainEffect.tick(Math.min(dt, .1));
            else { rainEffect.dispose(); rainEffect = null; }
        }
        if(snowEffect != null) {
            if(snow) snowEffect.tick(Math.min(dt, .1));
            else { snowEffect.dispose(); snowEffect = null; }
        }
    }

    public void dispose() {
        if(rainEffect != null) { rainEffect.dispose(); rainEffect = null; }
        if(snowEffect != null) { snowEffect.dispose(); snowEffect = null; }
    }
}
