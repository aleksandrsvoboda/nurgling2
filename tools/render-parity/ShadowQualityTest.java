package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.concurrent.*;
import static haven.render.sl.Type.*;

/** Exercises the production shadow filter on a slope, a blocker and outside the map. */
public class ShadowQualityTest {
    private static final int SIZE = 128, MAP = 64;
    private static final Uniform depth = NPostFX.u(SAMPLER2D, 0), outside = NPostFX.u(FLOAT, 1);
    private static final RawFunction sample = new RawFunction(VEC4, "hv_shadowtest", 4,
        "vec4 hv_shadowtest(vec4 col, vec2 tc, sampler2D map, float outside) {\n" +
        " vec3 p = vec3(tc, 0.3 + tc.x * 0.2);\n" +
        " vec2 grad=hv_sgradient(p);\n" +
        " if(outside == 1.0) p.x += 2.0;\n" +
        " float v = hv_sfilter(map, p, grad, vec2(440.0/2048.0,0.20/5000.0));\n" +
        " if(outside == 2.0) v=hv_sunvisibility(map,map,vec3(tc.x,0.5,0.9),vec3(0.5,0.5,0.01));\n" +
        " if(outside == 3.0) {\n" +
        "  const vec3 directions[6]=vec3[6](vec3(10,0,0),vec3(-10,0,0),vec3(0,10,0),vec3(0,-10,0),vec3(0,0,-10),vec3(0,0,10));\n" +
        "  v=hv_pshadow(map,directions[clamp(int(tc.x*6.0),0,5)],1.0,100.0,1.0/vec2(textureSize(map,0)));\n" +
        " }\n" +
        " if(outside == 4.0 || outside == 5.0) {\n" +
        "  vec2 q=vec2(tc.x*1.6-0.8,tc.y*0.7-0.85); float m=-5.0/q.y;\n" +
        "  v=hv_pshadow(map,vec3(m,-q.x*m,-5.0),1.0,100.0,1.0/vec2(textureSize(map,0)));\n" +
        " }\n" +
        " return vec4(v, v, v, 1.0);\n" +
        "}\n");
    private static final ShaderMacro shader = prog -> {
        DirectionalShadows.filter.define(prog.fctx);
        DirectionalShadows.visibility.define(prog.fctx);
        PointShadows.look.define(prog.fctx);
        NPostFX.shader(sample, depth, outside).modify(prog);
    };

    private static void require(boolean test, String why) { if(!test) throw new AssertionError(why); }

    private static final VertexArray.Layout GEOMETRY = new VertexArray.Layout(
        new VertexArray.Layout.Input(Homo3D.vertex,new VectorFormat(3,NumberFormat.FLOAT32),0,0,24),
        new VertexArray.Layout.Input(Homo3D.normal,new VectorFormat(3,NumberFormat.FLOAT32),0,12,24));
    private static class Plane implements Rendered, RenderTree.Node {
        final float[] vertices;
        final Model model;
        Plane(float x, float extent, float z) {
            float a=x-extent,b=x+extent;
            vertices=new float[]{a,-extent,z,0,0,1, b,-extent,z,0,0,1, b,extent,z,0,0,1,
                a,-extent,z,0,0,1, b,extent,z,0,0,1, a,extent,z,0,0,1};
            model=new Model(Model.Mode.TRIANGLES,new VertexArray(GEOMETRY,
                new VertexArray.Buffer(vertices.length*4,DataBuffer.Usage.STATIC,DataBuffer.Filler.of(vertices))),null);
        }
        public void draw(Pipe pipe,Render out) { out.draw(pipe,model); }
    }

    /** Real caster passes and the production Phong integration, including specular. */
    private static void scene(Windeye window) throws Exception {
        RenderTree tree=new RenderTree();
        Light.LightList lights=new Light.LightList();
        DirLight light=new DirLight(new FColor(.2f,.2f,.2f),new FColor(.6f,.6f,.6f),new FColor(.2f,.2f,.2f),Coord3f.zu);
        tree.add(light,lights);
        Light.PhongLight material=new Light.PhongLight(true,FColor.WHITE,FColor.WHITE,FColor.WHITE,FColor.BLACK,8);
        Plane[] planes={new Plane(0,20,-20),new Plane(300,20,-20),new Plane(0,60,-30),new Plane(300,60,-30)};
        tree.add(planes[0],material);
        tree.add(planes[1],material);
        DirectionalShadows shadows=new DirectionalShadows(tree);
        VectorFormat rgba=new VectorFormat(4,NumberFormat.UNORM8);
        Texture2D output=new Texture2D(SIZE,SIZE,DataBuffer.Usage.STATIC,rgba,null);
        try {
            for(int zone=0;zone<2;zone++) {
                float x=zone*300;
                DirectionalShadows.Sun state=shadows.update(light,new Coord3f(0,0,-30));
                Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0)))
                    .prep(new States.Viewport(Area.sized(Coord.of(SIZE,SIZE))))
                    .prep(Projection.ortho(x-60,x+60,-60,60,.1f,100))
                    .prep(new Camera(Matrix4f.id)).prep(material).prep(lights)
                    .prep(new Lighting.SimpleLights(new Object[][]{{light.amb,light.dif,light.spc,new float[]{0,0,1,0},0f,0f,0f,0f}}))
                    .prep(Light.celshade).prep(WorldLighting.smooth).prep(state);
                byte[] pixels=null;
                int center=255, lit=0;
                long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                do {
                Render out=window.env().render();
                shadows.draw(out);
                planes[2+zone].draw(pipe,out);
                CompletableFuture<byte[]> result=new CompletableFuture<>();
                out.pget(output.image(0),rgba,bytes->{byte[] data=new byte[SIZE*SIZE*4];bytes.get(data);result.complete(data);});
                window.swapbuffers(out,false);window.env().submit(out);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!result.isDone() && System.nanoTime()<deadline) {
                    Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
                }
                pixels=result.get(1,TimeUnit.SECONDS);
                center=pixels[(SIZE/2*SIZE+SIZE/2)*4]&255; lit=pixels[(SIZE/2*SIZE+SIZE/8)*4]&255;
                // Draw-list pipelines compile asynchronously; keep drawing while they warm up.
                } while(center>150 && System.nanoTime()<readyDeadline);
                require(Math.abs(center-51)<=2,"Shadow lost ambient or leaked direct/specular light in zone "+zone+": "+center);
                require(lit>200,"Unoccluded direct light missing in zone "+zone+": "+lit);
                System.out.printf("Production shadow zone %d: shadow %d / lit %d%n",zone,center,lit);
            }
        } finally { shadows.dispose(); tree.dispose(); output.dispose(); for(Plane plane:planes) plane.model.dispose(); }
    }

    private static byte[] capture(Windeye window, float separation, int mode) throws Exception {
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        int width = mode >= 3 ? MAP * 6 : MAP;
        Texture2D map = new Texture2D(width, MAP, DataBuffer.Usage.STATIC,
            new VectorFormat(1, NumberFormat.FLOAT32), (image, env) -> {
                if(image.level != 0) return null;
                FillBuffer fill = env.fillbuf(image);
                java.nio.ByteBuffer buf = fill.push();
                for(int y = 0; y < MAP; y++) for(int x = 0; x < width; x++) {
                    float z = .3f + (x + .5f) / MAP * .2f;
                    if(x >= MAP/4 && x < MAP*3/4 && y >= MAP/4 && y < MAP*3/4) z -= separation;
                    if(mode == 3) z = x >= MAP*5 ? .5f : 1f;
                    if(mode >= 4) {
                        // A floor 5 units below the lamp, projected into the +X face.
                        z=100f/99f + 100f/(99f*5f)*((y+.5f)/MAP*2-1);
                        if(mode==5 && x>=MAP/4 && x<MAP*3/4 && y>=MAP/4 && y<MAP*3/4) z-=separation;
                    }
                    buf.putFloat(z);
                }
                return fill;
            });
        Texture2D color = new Texture2D(SIZE, SIZE, DataBuffer.Usage.STATIC, rgba, null);
        try {
            Render out = window.env().render();
            Pipe pipe = new BufPipe().prep(new FragColor<>(color.image(0)))
                .prep(new States.Viewport(Area.sized(Coord.of(SIZE,SIZE))))
                .prep(new Ortho2D(Area.sized(Coord.of(SIZE,SIZE))));
            GOut g = new GOut(out, pipe, Coord.of(SIZE,SIZE));
            NPostFX.blit(g, Temporal.one(), new NPostFX.Pass(shader, map.sampler(), (float)mode));
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            out.pget(color.image(0), rgba, bytes -> { byte[] data = new byte[SIZE*SIZE*4]; bytes.get(data); result.complete(data); });
            window.swapbuffers(out, false); window.env().submit(out);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime() < deadline) {
                Render pump = window.env().render(); window.swapbuffers(pump, false); window.env().submit(pump);
                Thread.sleep(10);
            }
            return result.get(1, TimeUnit.SECONDS);
        } finally { map.dispose(); color.dispose(); }
    }

    public static void main(String[] args) throws Exception {
        require(!NGfx.classic.bettershadows, "Classic enables improved shadows");
        NGfx.Settings option = NGfx.classic.with("enabled",true).with("bettershadows",true);
        require(!NGfx.effective(option,false).bettershadows, "OpenGL enables optional shadows");
        ShadowMap map = new ShadowMap(Coord.of(4096,4096),750,5000,.25f,3);
        try {
            Coord3f dir = new Coord3f(0,-.6f,-.8f);
            Camera eye = new Camera(Matrix4f.id);
            Matrix4f first = map.setpos(Coord3f.o,dir).eyetotex(eye);
            Matrix4f small = map.setpos(new Coord3f(.01f,0,0),dir).eyetotex(eye);
            require(first.m[12] == small.m[12] && first.m[13] == small.m[13], "Subtexel shadow drift");
        } finally { map.dispose(); }
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        int exit = 0;
        try {
            window.title("Shadow filtering regression");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(SIZE,SIZE))).show(true);
            byte[] plane = capture(window,0,0), blocked = capture(window,.02f,0), outside = capture(window,.02f,1);
            byte[] contact = capture(window,.001f,0), blend = capture(window,0,2), upward = capture(window,0,3);
            byte[] floor = capture(window,0,4), floorBlocked = capture(window,.02f,5);
            int floorShadow=0;
            for(int i=0;i<floor.length;i+=4) {
                require((floor[i]&255)>=254,"Point-light self-shadow stripes on floor: "+(floor[i]&255));
                if((floorBlocked[i]&255)<10)floorShadow++;
            }
            require(floorShadow>100,"Point-light correction erased real blockers");
            System.out.println("Point-light floor: no self-shadow stripes; blocker pixels="+floorShadow);
            int shadow = 0, edge = 0, contactEdge = 0;
            for(int i=0; i<plane.length; i+=4) {
                require((plane[i]&255) >= 254, "Self-shadow stripe on slope");
                require((outside[i]&255) >= 254, "Clamped shadow outside map");
                int face=(int)(((i/4%SIZE)+.5)*6/SIZE), value=upward[i]&255;
                require(face==5 ? value<=1 : value>=254,"Incorrect point shadow cube face "+face);
                int v=blocked[i]&255;
                if(v < 2) shadow++;
                if(v > 2 && v < 253) edge++;
                int c=contact[i]&255;
                if(c > 2 && c < 253) contactEdge++;
            }
            require(shadow > 100 && edge > 100, "Missing blocker or filtered penumbra");
            require(edge > contactEdge*2, "Penumbra does not widen away from caster");
            for(int x=SIZE/2+1; x<SIZE; x++) {
                int previous=blend[((SIZE/2)*SIZE+x-1)*4]&255, next=blend[((SIZE/2)*SIZE+x)*4]&255;
                require(next >= previous && next-previous < 30,"Discontinuous near/far transition");
            }
            require((blend[(SIZE/2*SIZE+SIZE/2)*4]&255)<2 && (blend[(SIZE/2*SIZE+SIZE-1)*4]&255)>253,"Missing near/far coverage");
            scene(window);
            System.out.printf("Shadows: PASS (baseline, texel stability, slope, outside map, cascade blend, upward point shadow; %d dark / %d soft / %d contact-edge pixels)%n",shadow,edge,contactEdge);
        } catch(Throwable failure) { failure.printStackTrace(); exit=1; }
        finally { window.dispose(); toolkit.dispose(); }
        System.exit(exit);
    }
}
