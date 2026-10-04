package nurgling.render;

import haven.*;
import haven.res.gfx.fx.rain.Rain;
import java.util.*;

public class RainLightingTest {
    static void require(boolean condition,String message) {if(!condition) throw new AssertionError(message);}
    static float luma(float[] c) {return c[0]*.2126f+c[1]*.7152f+c[2]*.0722f;}
    static float chroma(float[] c) {return Math.max(c[0],Math.max(c[1],c[2]))-Math.min(c[0],Math.min(c[1],c[2]));}
    public static void main(String[] args) {
        SceneDebug debug=new SceneDebug();
        for(boolean palette:new boolean[]{false,true}) {
            float night=0,day=0;
            for(int minute:new int[]{0,360,720,1080}) {
                debug.time(minute);DirLight original=debug.light();
                if(palette)original=WorldLighting.apply(original,minute/1440.0,1);
                float[] unchanged=original.dif.clone();
                require(RainLighting.apply(original,0)==original,"Dry weather changed light");
                DirLight gray=RainLighting.apply(original,1);
                require(Arrays.equals(original.dif,unchanged),"Server light was mutated");
                require(Arrays.equals(original.dir,gray.dir)&&original.prio==gray.prio,"Clouds move the sun");
                require(chroma(gray.dif)<chroma(original.dif)*.3,"Rain light is still saturated");
                require(Math.abs(luma(gray.dif)/luma(original.dif)-.52)<.0001,"Rain overrides time-of-day brightness");
                if(minute==0)night=luma(gray.amb)+luma(gray.dif);
                if(minute==720)day=luma(gray.amb)+luma(gray.dif);
            }
            require(day>night*1.25,"Rain erased the day/night difference");
        }
        DirLight dark=new DirLight(new FColor(0,0,0),new FColor(0,0,0),new FColor(0,0,0),Coord3f.zu);
        require(luma(RainLighting.apply(dark,1).amb)==0&&luma(RainLighting.apply(dark,1).dif)==0,"Rain illuminates underground");
        Rain rain=new Rain(12000);
        require(RainLighting.intensity(Arrays.asList(rain))==1,"Normal rain intensity");
        rain.rate=36000;require(RainLighting.intensity(Arrays.asList(rain))==3,"Heavy rain intensity");
        rain.rate=0;require(RainLighting.intensity(Arrays.asList(rain))==0,"Stopped rain intensity");
        float cover=0;
        for(int i=0;i<600;i++) cover=RainLighting.approach(cover,1,1.0/60);
        require(cover>.84&&cover<.851,"Rain transition failed");
        float previous=cover;cover=RainLighting.approach(cover,0,1.0/60);
        require(cover<previous&&previous-cover<.003,"Clouds pop on stopping rain");
        NGfx.Settings settings=NGfx.classic.with("enabled",true).with("water",true).with("rainripples",true);
        require(settings.with("waterreflections",false).rainripples,"Reflections switch disables rain rings");
        require(Boolean.TRUE.equals(settings.map().get("rainripples")),"Ripple preference lost in serialization");
        require(!NGfx.effective(settings,false).rainripples&&!NGfx.Preset.CLASSIC.settings(settings).rainripples,"Classic/GL enables ripples");
        require(NGfx.Preset.ENHANCED.settings(NGfx.classic).rainripples,"Enhanced preset missing rain rings");
        rain.dispose();debug.dispose();
        System.out.println("Rain lighting: PASS (day/night, gray palette, dark interiors, transitions, rain intensity, independent saved setting)");
        System.exit(0);
    }
}
