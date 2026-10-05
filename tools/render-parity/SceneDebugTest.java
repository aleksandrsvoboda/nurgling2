package nurgling.render;

import haven.*;
import haven.res.gfx.fx.rain.Rain;
import haven.res.gfx.fx.snow.Snow;
import java.util.*;

/** Preview lifecycle and isolation from the live weather collection. */
public class SceneDebugTest {
    private static void require(boolean value, String message) {
        if(!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SceneDebug preview = new SceneDebug();
        Rain serverRain = new Rain(7f);
        Glob.Weather other = new Glob.Weather() { public void update(Object... args) {} };
        Collection<Glob.Weather> live = Arrays.asList(serverRain, other);
        require(!preview.hasTime() && preview.weather(live) == live, "Preview changes the default scene");
        float noon = 0, midnight = 0;
        for(int minute = 0; minute < 1440; minute++) {
            preview.time(minute);
            DirLight light = preview.light();
            for(float v : light.dir) require(Float.isFinite(v), "Invalid sun direction");
            for(float v : light.amb) require(Float.isFinite(v) && v >= 0 && v <= 1, "Invalid light color");
            if(minute == 0) { midnight = light.dif[0]; require(preview.night(), "Midnight is day"); }
            if(minute == 720) { noon = light.dif[0]; require(!preview.night(), "Noon is night"); }
        }
        require(noon > midnight, "Time slider does not change light strength");
        for(int cycle = 0; cycle < 3; cycle++) {
            preview.rain(true);
            preview.prepare();
            Collection<Glob.Weather> shown = preview.weather(live);
            require(shown.size() == 2 && shown.contains(other) && !shown.contains(serverRain), "Duplicate/lost weather");
            require(serverRain.rate == 7 && live.size() == 2, "Live weather mutated");
            Rain local = (Rain)shown.stream().filter(w -> w instanceof Rain).findFirst().get();
            float normalRate = local.rate;
            preview.heavyRain(true);
            preview.prepare();
            require(preview.rain() && preview.heavyRain() && local.rate == normalRate * 3, "Heavy rain does not increase intensity");
            require(preview.weather(live).contains(local) && serverRain.rate == 7, "Heavy rain replaces particle buffers or changes live weather");
            preview.heavyRain(false);
            preview.prepare();
            require(preview.rain() && local.rate == normalRate, "Normal rain not restored");
            preview.rain(false);
            preview.heavyRain(true);
            require(preview.rain(), "Heavy rain alone does not enable rain");
            preview.rain(false);
            require(!preview.heavyRain(), "Disabling rain leaves heavy rain enabled");
            preview.heavyRain(true);
            preview.snow(true);
            preview.reset();
            require(!preview.hasTime() && !preview.rain() && !preview.heavyRain() && !preview.snow(), "Incomplete reset");
            require(preview.weather(live) == live, "Reset does not restore server weather");
            preview.tick(.016);
            require(local.dropspr.va == null && local.splashspr.va == null, "Rain buffers retained");
        }
        Snow snow = new Snow(new Material[]{new Material()}, 1);
        snow.addflake(snow.new Flake(snow.flakemats[0]));
        snow.dispose();
        snow.dispose();
        require(snow.nf == 0 && snow.matmap.isEmpty(), "Snow particles retained after disposal");
        preview.dispose();
        serverRain.dispose();
        System.out.println("Scene debug: PASS (daily light cycle, default isolation, weather replacement, reset, repeated toggles/disposal)");
        System.exit(0);
    }
}
