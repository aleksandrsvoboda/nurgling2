package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.iosys.tk.*;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import static haven.render.sl.Type.*;

/** GPU regression for rain on matte vegetation, porous soil and paving at several view angles. */
public class WetSurfaceTest {
    static final Coord SIZE = Coord.of(64, 64);
    static void require(boolean ok, String message) {if(!ok) throw new AssertionError(message);}
    static final RawFunction sample = new RawFunction(VEC4, "wet_test", 6,
        "vec4 wet_test(vec4 col, vec2 tc, float wet, vec3 surface, float eye, float bump) {\n" +
        " vec3 ep=vec3((tc-.5)*2.0+vec2(eye,0.0),-10.0);\n" +
        " vec2 slope=vec2(sin(tc.x*28.0),cos(tc.y*28.0))*.45*bump;\n" +
        " if(bump>1.5) slope=(mod(floor(tc*64.0),2.0)*2.0-1.0)*2.0;\n" +
        " vec3 n=normalize(vec3(slope,1.0));\n" +
        " return hv_wet(vec4(.4,.3,.2,.7),ep,n,vec3(0,0,1),vec3(.6,0,.8),vec3(.7,.8,1),vec3(.3,.4,.5),wet,surface);\n" +
        "}\n");
    static final Uniform wet=NPostFX.u(FLOAT,0), surface=NPostFX.u(VEC3,1), eye=NPostFX.u(FLOAT,2), bump=NPostFX.u(FLOAT,3);
    static final ShaderMacro shader = prog -> {
        Atmos.wetfn.define(prog.fctx);
        NPostFX.shader(sample,wet,surface,eye,bump).modify(prog);
    };
    static float[] capture(Windeye window, Atmos.WetSurface material, float amount, float angle, float relief) throws Exception {
        VectorFormat rgba=new VectorFormat(4,NumberFormat.FLOAT32);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,rgba,null);
        try {
            Render out=window.env().render();
            Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            NPostFX.blit(new GOut(out,pipe,SIZE),Temporal.one(),new NPostFX.Pass(shader,amount,material.response,angle,relief));
            CompletableFuture<float[]> future=new CompletableFuture<>();
            out.pget(output.image(0),rgba,bytes->{float[] pixels=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);future.complete(pixels);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!future.isDone() && System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            return future.get(1,TimeUnit.SECONDS);
        } finally {output.dispose();}
    }
    public static void main(String[] args) throws Exception {
        GroundRelief.set(true,.9f,true,true);
        State natural=GroundRelief.state(Atmos.WetSurface.SOIL),paving=GroundRelief.state(Atmos.WetSurface.PAVING);
        require(natural.shader()==null&&GroundRelief.state(Atmos.WetSurface.VEGETATION).shader()==null,"Paving-only relief leaks onto natural terrain");
        require(paving.shader()!=null,"Paving-only relief also disables paving");
        require(GroundRelief.set(true,.9f,true,false),"Scope change did not request shader refresh");
        require(natural.shader()!=null&&natural.shader()==paving.shader(),"Existing materials do not restore full-terrain relief");
        GroundRelief.set(false,.9f,true,true);
        require(paving.shader()==null&&natural.shader()==null,"Paving-only setting overrides master relief switch");
        GroundRelief.set(true,.9f,true,true);
        if(args.length>0 && args[0].equals("--cached-resources")) {
            Resource.setcache(ResCache.global);
            for(String name:new String[]{"ballbrick","graywacke","soapstone"}) {
                Tileset set=Resource.remote().loadwait("gfx/tiles/paving/"+name).flayer(Tileset.class);
                Tiler tile;
                while(true) {try {tile=set.tfac().create(1,set);break;} catch(Loading loading){loading.waitfor();}}
                require(tile instanceof haven.resutil.TerrainTile,"Unexpected paving tiler: "+name);
                haven.resutil.TerrainTile terrain=(haven.resutil.TerrainTile)tile;
                Pipe state=new BufPipe();terrain.draw.apply(state);
                require(state.get(Atmos.WetSurface.slot)==Atmos.WetSurface.PAVING,"Actual paving base has wrong wet profile: "+name);
                require(state.get(GroundRelief.slot).shader()!=null,"Actual brick/stone paving lost relief in paving-only mode: "+name);
                for(haven.resutil.TerrainTile.Var variant:terrain.var) {
                    state=new BufPipe();variant.draw.apply(state);
                    require(state.get(Atmos.WetSurface.slot)==Atmos.WetSurface.PAVING,"Paving variant has wrong wet profile: "+name);
                    require(state.get(GroundRelief.slot).shader()!=null,"Paving variant lost relief in paving-only mode: "+name);
                }
            }
            System.out.println("Cached brick/stone paving base and variant profiles: PASS");
        }
        require(Atmos.WetSurface.ground("gfx/tiles/paving/brick")==Atmos.WetSurface.PAVING,"Paving profile lost");
        require(Atmos.WetSurface.terrain("gfx/tiles/grass")==Atmos.WetSurface.VEGETATION,"Grass gets a reflective film");
        require(Atmos.WetSurface.ground("gfx/tiles/dirt")==Atmos.WetSurface.SOIL,"Soil profile lost");
        GroundRelief.set(false,1,false,false);
        System.out.println("Relief scope: PASS (paving, natural terrain, existing materials, parallax and master switch)");
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        try {
            window.title("Wet surface regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] base={.4f,.3f,.2f};
            for(Atmos.WetSurface material:new Atmos.WetSurface[]{Atmos.WetSurface.VEGETATION,Atmos.WetSurface.SOIL,Atmos.WetSurface.PAVING}) {
                double maximum=0,reliefResponse=0;
                for(float angle:new float[]{-20,-10,-5,0,5,10,20}) {
                    double minFilm=Double.POSITIVE_INFINITY;
                    float[] dry=capture(window,material,0,angle,0), flat=capture(window,material,1,angle,0), rough=capture(window,material,1,angle,2);
                    float[] stone=capture(window,material,1,angle,1);
                    for(int i=0;i<flat.length;i++) {
                        int channel=i%4;
                        require(Float.isFinite(flat[i]),"Invalid wet color");
                        require(Math.abs(dry[i]-(channel==3?.7f:base[channel]))<.00001,"Dry color/alpha changed");
                        require(Math.abs(rough[i]-flat[i])<.00001,"Relief normals create sparkling highlights");
                        reliefResponse=Math.max(reliefResponse,Math.abs(stone[i]-flat[i]));
                        require(Float.isFinite(stone[i]) && stone[i]>=0 && stone[i]<1.15,"Invalid or overbright stone sheen");
                        if(channel==3) {require(Math.abs(flat[i]-.7f)<.00001,"Wet alpha changed");continue;}
                        double sheen=flat[i]-base[channel]*(1-material.response[0]);
                        maximum=Math.max(maximum,sheen);
                        require(sheen>=-.00001 && sheen<.9,"Overbright wet film");
                        if(channel==2) minFilm=Math.min(minFilm,sheen);
                        if(material==Atmos.WetSurface.VEGETATION) require(Math.abs(sheen)<.00001,"Grass shines at a different angle");
                    }
                    if(material==Atmos.WetSurface.PAVING) {
                        require(minFilm>.025,"Paving sheen disappears away from the light");
                    }
                }
                if(material==Atmos.WetSurface.SOIL) require(maximum>.025 && maximum<.1,"Soil sheen absent or as glossy as paving");
                if(material==Atmos.WetSurface.PAVING) require(maximum>.5,"Wet paving sheen too weak");
                if(material==Atmos.WetSurface.PAVING) require(reliefResponse>.06,"Wet sheen ignores stone relief");
                else require(reliefResponse<.00001,"Relief glitter leaked onto porous terrain");
                System.out.printf("Wet profile %.3f: max sheen %.4f, resolved relief %.4f, subpixel filtering PASS%n",material.response[1],maximum,reliefResponse);
            }
        } catch(Throwable t){t.printStackTrace();exit=1;} finally {window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
