package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.resutil.WaterTile;
import haven.iosys.tk.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/** Preserve the original bank geometry while excluding only its cast shadow. */
public class WaterShoreTest {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final RawFunction fringe=new RawFunction(VEC4,"shore_test_fringe",2,
        "vec4 shore_test_fringe(vec4 c,vec3 p) { if(p.x>4.8+1.1*sin(p.y*1.6)) discard; return c; }");
    static final Pipe.Op grass=Pipe.Op.compose(new BaseColor(new FColor(.22f,.42f,.06f,1)),new MapMesh.MLOrder(50));
    static final Pipe.Op edge=Pipe.Op.compose(grass,new RUtils.AdHoc(p->{fringe.define(p.fctx);FragColor.fragcol(p.fctx).mod(in->fringe.call(in,Homo3D.fragmapv.ref()),0);}));
    static void faces(MapMesh m,Tiler.MPart d,Pipe.Op material) {
        MeshBuf buf=MapMesh.Model.get(m,d.mcomb(material));
        Surface.MeshVertex[] vertices=new Surface.MeshVertex[d.v.length];
        for(int i=0;i<vertices.length;i++)vertices[i]=new Surface.MeshVertex(buf,d.v[i]);
        for(int i=0;i<d.f.length;i+=3)buf.new Face(vertices[d.f[i]],vertices[d.f[i+1]],vertices[d.f[i+2]]);
    }
    static MapMesh map(boolean corner,boolean legacy) {
        int[] checked={0};
        WaterTile water=new WaterTile(1,"gfx/tiles/water",(m,d)->faces(m,d,Pipe.Op.compose(new BaseColor(new FColor(.32f,.27f,.17f,1)),new MapMesh.MLOrder(0))),12);
        Tiler land=new Tiler(0) {
            public void lay(MapMesh m,Random rnd,Coord lc,Coord gc){lay(m,lc,gc,(mesh,d)->faces(mesh,d,grass),false);}
            public void trans(MapMesh m,Random rnd,Tiler gt,Coord lc,Coord gc,int z,int bm,int cm) {
                if(!(gt instanceof WaterTile))return;
                List<MPart> parts=new ArrayList<>();
                gt.laytrans(m,lc,gc,(mesh,d)->parts.add(d));
                require(parts.size()==1,"Shore must not have a second submerged mesh");
                MPart dry=parts.get(0);
                MapMesh.MapSurface surface=m.data(MapMesh.gnd);
                MPart original=MPart.splitquad(lc,gc,surface.fortilea(lc),surface.split[surface.bs.o(lc)]);
                require(Arrays.equals(dry.f,original.f),"Original bank triangulation changed");
                require(Arrays.equals(dry.tcx,original.tcx)&&Arrays.equals(dry.tcy,original.tcy),"Shore texture moved");
                for(int i=0;i<4;i++) {
                    require(dry.v[i]==original.v[i],"Original bank vertex was replaced or moved underwater");
                }
                require(new BufPipe().prep(dry.mat).get(ShadowMap.maskshadow.slot)!=null,"Bank still casts a shadow");
                gt.lay(m,lc,gc,(mesh,d)->{
                    for(int i=0;i<4;i++)require(d.v[i]==dry.v[i],"Ground overlay was moved underwater");
                    require(new BufPipe().prep(d.mat).get(ShadowMap.maskshadow.slot)==null,"Bank shadow mask leaked to ground overlays");
                },false);
                if(legacy){dry.mat=null;faces(m,dry,edge);}else for(MPart d:parts)faces(m,d,edge);
                checked[0]++;
            }
        };
        MCache cache=new MCache(null) {
            public int gettile(Coord c){return c.x<0||(corner&&c.y<0)?0:1;}
            public double getfz(Coord c){return 0;}
            public Tiler tiler(int id){return id==0?land:water;}
        };
        MapMesh mesh=MapMesh.build(cache,new Random(7),Coord.of(-1,-1),Coord.of(4,4));
        require(checked[0]>0,"No shoreline exercised");
        return mesh;
    }
    static float[] capture(Windeye window,MapMesh mesh,boolean water) throws Exception {
        Atmos.water=water;
        Coord size=WaterSurfaceTest.SIZE;
        PView view=new PView(size){protected void basic(){}};
        Texture2D scene=new Texture2D(size,DataBuffer.Usage.STATIC,WaterSurfaceTest.RGBA,null);
        Texture2D result=new Texture2D(size,DataBuffer.Usage.STATIC,WaterSurfaceTest.RGBA,null);
        Texture2D depth=new Texture2D(size,DataBuffer.Usage.STATIC,Texture.DEPTH,null);view.depth=depth;
        Pipe base=new BufPipe().prep(new FragColor<>(scene.image(0))).prep(new DepthBuffer<>(depth.image(0)))
            .prep(new States.Viewport(Area.sized(size))).prep(Homo3D.state).prep(Projection.ortho(-20,20,-20,20,1,200))
            .prep(Camera.pointed(Coord3f.of(11,-11,0),60,.9f,.15f)).prep(new FrameInfo(WaterSurface.epoch+10))
            .prep(new States.Depthtest(States.Depthtest.Test.LE))
            .prep(new Atmos.Env(0,false,true,-1,new float[]{.4f,0,.9165f},new float[]{.5f,.5f,.5f},new float[]{.7f,.8f,1},null));
        view.basic.ostate(p->{for(State s:base.states())if(s!=null)s.apply(p);});
        view.basic.add(mesh,Location.xlate(Coord3f.of(-11,11,0)));
        WaterSurface renderer=new WaterSurface(view);
        try {
            long preparationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
            Render out=window.env().render();out.clear(base,FragColor.fragcol,new FColor(.06f,.08f,.1f,1));out.clear(base,1.0);
            List<RenderTree.Slot> slots=new ArrayList<>();
            for(RenderTree.Slot slot:view.tree.slots())if(slot.obj() instanceof FastMesh)slots.add(slot);
            slots.sort(Comparator.comparingInt(s->{Rendered.Order o=s.state().get(Rendered.order);return o instanceof MapMesh.MLOrder?((MapMesh.MLOrder)o).z:Integer.MAX_VALUE;}));
            for(RenderTree.Slot slot:slots) {
                // No legacy cubemap in the comparison: isolate bank geometry and depth.
                if(slot.state().get(WaterSurface.Marker.slot)!=null)continue;
                ((FastMesh)slot.obj()).draw(slot.state(),out);
            }
            Pipe target=new BufPipe().prep(new FragColor<>(result.image(0))).prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(Area.sized(size)));
            if(water)renderer.run(new GOut(out,target,size),scene.sampler());
            else new GOut(out,target,size).image(new TexRaw(scene.sampler(),true),Coord.z,size);
            CompletableFuture<float[]> read=new CompletableFuture<>();
            out.pget(result.image(0),WaterSurfaceTest.RGBA,b->{float[] data=new float[size.x*size.y*4];b.order(ByteOrder.nativeOrder()).asFloatBuffer().get(data);read.complete(data);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!read.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            float[] pixels=read.get(1,TimeUnit.SECONDS);
            if(!(out instanceof haven.render.vk.VkRender) || ((haven.render.vk.VkRender)out).pendingDraws()==0) return pixels;
            require(System.nanoTime()<preparationDeadline,"Bank water shaders/pipelines never became ready");
            Thread.sleep(10);
            }
        } finally {renderer.dispose();view.dispose();scene.dispose();result.dispose();depth.dispose();}
    }
    public static void main(String[] args)throws Exception {
        nurgling.NConfig.getGlobalInstance();
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        try {
            window.title("Original shoreline without cast shadows");window.sizing(new Windeye.Sizing().fixsize(WaterSurfaceTest.SIZE)).show(true);
            MapMesh mesh=map(false,false),legacy=map(false,true),corner=map(true,false);
            try {
                float[] before=capture(window,legacy,true),after=capture(window,mesh,true);
                float[] classic=capture(window,mesh,false),original=capture(window,legacy,false);
                require(WaterSurfaceTest.difference(classic,original)<.000001,"Classic shoreline changed");
                require(WaterSurfaceTest.difference(before,after)<.000001,"Transparent water changed the original bank geometry");
                require(WaterSurfaceTest.difference(after,capture(window,mesh,true))<.000001,"Water toggle does not restore bank");
                WaterSurfaceTest.save(before,"shore-original");WaterSurfaceTest.save(after,"shore-no-cast-shadow");
                WaterSurfaceTest.save(capture(window,corner,true),"shore-corner");
                System.out.printf("Shoreline PASS: original vertices/UVs/overlays, one bank mesh, cast-shadow mask; Classic delta %.8f; transparent delta %.8f%n",WaterSurfaceTest.difference(classic,original),WaterSurfaceTest.difference(before,after));
            } finally {mesh.dispose();legacy.dispose();corner.dispose();}
        }catch(Throwable t){t.printStackTrace();exit=1;}finally{window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
