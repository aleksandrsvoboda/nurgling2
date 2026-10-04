package nurgling.render;

import haven.*;
import java.util.Arrays;

public class DryWeatherLightingTest {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void same(DirLight a,DirLight b,String message) {
        require(Arrays.equals(a.amb,b.amb)&&Arrays.equals(a.dif,b.dif)&&Arrays.equals(a.spc,b.spc),message);
    }
    public static void main(String[] args) {
        SceneDebug debug=new SceneDebug();
        try {
            for(int minute=0;minute<1440;minute++) {
                debug.time(minute);DirLight original=debug.light();
                double fraction=minute/1440.0;
                DirLight baseline=WorldLighting.apply(original,fraction,1);
                DirLight dry=WorldLighting.apply(original,fraction,1,1,false);
                same(baseline,WorldLighting.apply(original,fraction,1,0,false),"Rain palette changed");
                same(baseline,WorldLighting.apply(original,fraction,1,1,true),"Auto-exposure palette changed");
                require(WorldLighting.apply(original,fraction,0,1,false)==original,"Zero strength changed");
                for(int c=0;c<3;c++) {
                    require(dry.amb[c]>baseline.amb[c]&&dry.dif[c]>baseline.dif[c],"Clear weather not brighter");
                    require(dry.amb[c]<=baseline.amb[c]*1.401f&&dry.dif[c]<=baseline.dif[c]*1.251f,"Unbounded brightness");
                }
                require(Arrays.equals(dry.spc,baseline.spc),"Specular glare increased");
                require(Arrays.equals(dry.dir,baseline.dir)&&dry.prio==baseline.prio,"Sun direction/priority changed");
            }
            for(float rain:new float[]{.01f,.25f,1,3}) {
                float lift=1;
                for(int frame=0;frame<1800;frame++) {
                    float next=WorldLighting.approachClearWeather(lift,rain,1.0/60);
                    require(next<=lift&&next>=0&&lift-next<.009,"Rain transition jumps");lift=next;
                }
                require(lift<.00001,"Rain retains clear-weather lift");
                for(int frame=0;frame<3600;frame++) {
                    float next=WorldLighting.approachClearWeather(lift,0,1.0/60);
                    require(next>=lift&&next<=1&&next-lift<.003,"Dry transition jumps");lift=next;
                }
                require(lift>.9999,"Clear brightness not restored");
            }
            DirLight dark=new DirLight(FColor.BLACK,FColor.BLACK,FColor.BLACK,Coord3f.zu);
            require(WorldLighting.apply(dark,.5,1,1,false)==dark,"Interior daylight manufactured");
            System.out.println("PASS: brighter dry lighting through 24 hours; rain/auto-exposure/specular unchanged; smooth weather transitions");
        }finally{debug.dispose();}
        System.exit(0);
    }
}
