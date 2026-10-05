package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

public class GrassTest {
    static final Coord SIZE=Coord.of(384,384);
    static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.FLOAT32);
    static void require(boolean v,String reason){if(!v)throw new AssertionError(reason);}
    static final Grass.Terrain terrain=new Grass.Terrain(){
        public boolean grass(double x,double y){return x<33;}
        public float height(double x,double y){return (float)(x*.02+y*.01);}
    };
    static FloatBuffer attr(FastMesh mesh,haven.render.sl.Attribute name) {
        for(VertexBuf.AttribData data:mesh.vert.bufs)if(data.attr==name)return ((VertexBuf.FloatData)data).data;
        throw new AssertionError("Missing blade attribute");
    }
    static void unsignedBounds() {
        FloatBuffer vertices=FloatBuffer.allocate(65536*3);
        vertices.put(0,9999); // Unreferenced vertices must not affect bounds.
        int[] ids={32767,32768,65535};
        float[][] points={{-5,2,3},{4,-7,19},{13,17,-11}};
        for(int i=0;i<ids.length;i++)for(int c=0;c<3;c++)vertices.put(ids[i]*3+c,points[i][c]);
        FastMesh mesh=new FastMesh(new VertexBuf(new VertexBuf.VertexData(vertices)),
                new short[]{(short)32767,(short)32768,(short)65535});
        try {
            require(mesh.nbounds().equals(Coord3f.of(-5,-7,-11)),"Unsigned mesh minimum bounds");
            require(mesh.pbounds().equals(Coord3f.of(13,17,19)),"Unsigned mesh maximum bounds");
            require(vertices.position()==0&&mesh.indb.position()==0,"Bounds calculation moves input buffers");
        }finally{mesh.dispose();}
    }
    static void geometry() {
        unsignedBounds();
        require(Grass.eligible("gfx/tiles/grass")&&!Grass.eligible("gfx/tiles/dirt")&&!Grass.eligible("gfx/tiles/ballbrick"),"Terrain filtering");
        FastMesh a=Grass.build(Coord.z,terrain),b=Grass.build(Coord.z,terrain);
        require(a!=null&&a.vert.num<65536,"Empty or oversized patch");
        FloatBuffer roots=attr(a,Grass.root),other=attr(b,Grass.root),shapes=attr(a,Grass.shape);
        require(roots.equals(other),"Grass changes layout on rebuild");
        FastMesh sparse=Grass.build(Coord.z,terrain,.25f),thick=Grass.build(Coord.z,terrain,2);
        require(sparse.vert.num<a.vert.num&&thick.vert.num>a.vert.num&&thick.vert.num<65536,"Density does not change geometry safely");
        Set<String> fullRoots=new HashSet<>();
        FloatBuffer denseRoots=attr(thick,Grass.root);
        for(int i=0;i<denseRoots.capacity();i+=32)fullRoots.add(denseRoots.get(i)+":"+denseRoots.get(i+1));
        for(int i=0;i<roots.capacity();i+=32)require(fullRoots.contains(roots.get(i)+":"+roots.get(i+1)),"Density relocates existing grass");
        NGfx.Settings custom=NGfx.classic.with("grassdensity",2);
        require(custom.grassdensity==2&&((Number)custom.map().get("grassdensity")).floatValue()==2,"Grass quantity not persisted");
        require(custom.with("grassdensity",Float.NaN).grassdensity==1&&custom.with("grassdensity",99).grassdensity==2,"Unsafe grass setting bounds");
        require(custom.with("grassdensity",0).grassdensity==.25f,"Grass lower bound");
        sparse.dispose();thick.dispose();
        for(int i=0;i<roots.capacity();i+=4) {
            float x=roots.get(i),y=-roots.get(i+1),z=roots.get(i+2),h=roots.get(i+3);
            require(x<33&&h>0&&h<=Grass.MAX_HEIGHT,"Grass crosses paving or knee-height cap");
            require(Math.abs(z-terrain.height(x,y)-.015)<.0001,"Floating root on sloped terrain");
            require(shapes.get(i)>=0&&shapes.get(i)<=1,"Invalid height weights");
        }
        require(Grass.build(Coord.of(1,0),terrain)==null,"Geometry on non-grass terrain");
        Grass.Terrain meadow=new Grass.Terrain(){
            public boolean grass(double x,double y){return true;}
            public float height(double x,double y){return 0;}
        };
        // Clusters must extend over the entire selected area, including negative
        // coordinates and the last tiles, without becoming uniform carpet.
        for(float quantity:new float[]{.25f,1,2})for(Coord key:new Coord[]{Coord.z,Coord.of(-1,-1),Coord.of(2,3)}) {
            FastMesh patch=Grass.build(key,meadow,quantity);
            FloatBuffer positions=attr(patch,Grass.root);int[] counts=new int[Grass.TILES*Grass.TILES];
            for(int i=0;i<positions.capacity();i+=32) {
                int x=(int)Math.floor(positions.get(i)/11)-key.x*Grass.TILES;
                int y=(int)Math.floor(-positions.get(i+1)/11)-key.y*Grass.TILES;
                require(x>=0&&x<Grass.TILES&&y>=0&&y<Grass.TILES,"Roots spill across patch boundaries");
                counts[y*Grass.TILES+x]++;
            }
            int min=Integer.MAX_VALUE,max=0;
            for(int count:counts){min=Math.min(min,count);max=Math.max(max,count);require(count<=256,"Tile exceeds safe tuft limit");}
            require(max>min*2,"Grass loses its clustered distribution");
            // Building the last tile alone must produce the same geometry as in
            // a whole patch, even when earlier tiles have dense groups.
            int lastX=(key.x+1)*Grass.TILES-1,lastY=(key.y+1)*Grass.TILES-1;
            FastMesh last=Grass.build(key,new Grass.Terrain(){
                public boolean grass(double x,double y){return (int)Math.floor(x/11)==lastX&&(int)Math.floor(y/11)==lastY;}
                public float height(double x,double y){return 0;}
            },quantity);
            require(counts[counts.length-1]==(last==null?0:last.vert.num/8),"Grass truncates the last tile");
            if(last!=null)last.dispose();
            require(patch.vert.num<65536,"Full coverage exceeds mesh index limit");
            // Exercise the same bounds calculation used by the background job.
            // The generic unsignedBounds fixture covers dense index boundaries.
            Grass.Patch cached=new Grass.Patch(key,new MapMesh[0],patch,quantity);
            require(Math.abs(cached.low-.015f)<.00001f&&cached.high>cached.low&&
                    cached.high<=Grass.MAX_HEIGHT+.016f,"Invalid dense grass bounds");
            cached.dispose();
        }
        Grass.Trail slow=new Grass.Trail(),fast=new Grass.Trail();
        double now=WaterSurface.epoch+10;
        for(int i=0;i<=100;i++)slow.sample(Coord3f.of(i*.06f,0,0),now+i*.01);
        for(int i=0;i<=10;i++)fast.sample(Coord3f.of(i*.6f,0,0),now+i*.1);
        require(slow.points.size()==8&&fast.points.size()==8,"Trail depends on frame count");
        Iterator<float[]> highFps=slow.points.iterator(),lowFps=fast.points.iterator();
        while(highFps.hasNext()) {
            float[] high=highFps.next(),low=lowFps.next();
            for(int component=0;component<4;component++)require(Math.abs(high[component]-low[component])<.0001,"Contact position/time changes with FPS");
        }
        slow.sample(Coord3f.of(6,0,0),now+3);require(slow.points.isEmpty(),"Grass does not recover after stopping");
        fast.sample(Coord3f.of(100,0,0),now+1.1);require(fast.points.isEmpty(),"Teleport leaves a cross-map bend");
        require(NGfx.classic.with("animatedgrass",true).grass&&!NGfx.effective(NGfx.classic.with("animatedgrass",true),false).grass,"Preference/backend isolation");
        Area area=Grass.patchArea(new Area(Coord.of(-1,-1),Coord.of(2,2)));
        require(area.ul.equals(Coord.of(-5,-5))&&area.br.equals(Coord.of(10,10)),"Visible cut bounds lose edge tiles or negative coordinates");
        require(MCache.cutsz.x%Grass.TILES==0&&MCache.cutsz.y%Grass.TILES==0,"Grass patches spill into unloaded cuts");
        Matrix4f camera=Grass.clip(new BufPipe().prep(Projection.ortho(-29,29,-29,29,1,250))
            .prep(Camera.pointed(Coord3f.of(20*Grass.SPAN+22,-22,0),100,.80f,0)));
        require(Grass.visible(camera,Coord.of(20,0),0,3.4f),"Visible grass still depends on player radius");
        require(!Grass.visible(camera,Coord.z,0,3.4f),"Offscreen terrain is selected");
        require(!Grass.visible(camera,Coord.of(20,0),1000,1004),"Terrain heights ignored by camera culling");
        Matrix4f perspective=Projection.frustum(-1,1,-1,1,1,500).fin(Matrix4f.id);
        require(Grass.visible(perspective,Coord.z,-100,-96),"Perspective camera loses visible terrain");
        require(!Grass.visible(perspective,Coord.z,100,104),"Grass behind perspective camera is selected");
        require(!Grass.visible(perspective,Coord.of(20,0),-100,-96),"Perspective camera keeps offscreen grass");
        System.out.printf("Grass geometry PASS: %d blades, clustered terrain coverage, grass mask, height cap, rooted slope, distance trail and recovery%n",a.vert.num/8);
        a.dispose();b.dispose();
    }
    static void layout() throws Exception {
        BufferedImage image=new BufferedImage(768,768,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=image.createGraphics();g.setColor(new java.awt.Color(40,37,25));g.fillRect(0,0,768,768);
        g.setColor(new java.awt.Color(128,158,64));
        Grass.Terrain meadow=new Grass.Terrain(){
            public boolean grass(double x,double y){return true;}
            public float height(double x,double y){return 0;}
        };
        int edges=0,total=0;
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
            Coord key=Coord.of(x,y);Grass.Density a=new Grass.Density(key),b=new Grass.Density(key.add(1,0));
            for(int i=0;i<50;i++) {
                double px=(x+1)*Grass.SPAN,py=(y+i/50.0)*Grass.SPAN;
                require(Math.abs(a.at(px,py)-b.at(px,py))<1e-12,"Group field changes across patch borders");
            }
            FastMesh mesh=Grass.build(key,meadow);if(mesh==null)continue;
            FloatBuffer roots=attr(mesh,Grass.root);
            for(int i=0;i<roots.capacity();i+=32) {
                double px=roots.get(i),py=-roots.get(i+1),fx=px-Math.floor(px/11)*11,fy=py-Math.floor(py/11)*11;
                if(fx<.1||fx>10.9||fy<.1||fy>10.9)edges++;
                total++;
                g.fillRect((int)((px+Grass.SPAN)*768/(Grass.SPAN*3)),(int)((py+Grass.SPAN)*768/(Grass.SPAN*3)),1,1);
            }
            mesh.dispose();
        }
        require(edges>total*.005,"Tile margins form empty grid lines");
        g.dispose();new File("build/grass-preview").mkdirs();
        ImageIO.write(image,"png",new File("build/grass-preview/cluster-layout.png"));
        System.out.printf("Grass layout PASS: %d roots, %d at tile edges, continuous world-space groups%n",total,edges);
    }
    static float[] capture(Windeye window,double time,boolean contact,boolean blocked) throws Exception {
        return capture(window,time,contact,blocked,1,Coord.z);
    }
    static float[] capture(Windeye window,double time,boolean contact,boolean blocked,float quantity,Coord key) throws Exception {
        PView view=new PView(SIZE){protected void basic(){}};
        Texture2D scene=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null),output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,Texture.DEPTH,null);view.depth=depth;
        Grass grass=new Grass(view);grass.configure(quantity);
        Grass.Terrain shifted=new Grass.Terrain(){
            public boolean grass(double x,double y){return terrain.grass(x-key.x*Grass.SPAN,y-key.y*Grass.SPAN);}
            public float height(double x,double y){return terrain.height(x-key.x*Grass.SPAN,y-key.y*Grass.SPAN);}
        };
        FastMesh mesh=Grass.build(key,shifted,quantity);
        grass.patches.put(key,new Grass.Patch(key,new MapMesh[0],mesh,quantity));
        grass.center=Coord.z;
        FloatBuffer roots=attr(mesh,Grass.root);int contactIndex=(mesh.vert.num/16)*4;
        float cx=roots.get(contactIndex),cy=-roots.get(contactIndex+1),cz=roots.get(contactIndex+2);
        if(contact)for(int i=0;i<=8;i++)grass.trail.sample(Coord3f.of(cx-3.2f+i*.8f,cy,cz),WaterSurface.epoch+10-.3+i*.035);
        Pipe base=new BufPipe().prep(Homo3D.state).prep(Projection.ortho(-29,29,-29,29,1,250))
            .prep(Camera.pointed(Coord3f.of(22+key.x*Grass.SPAN,-22-key.y*Grass.SPAN,0),100,.80f,0))
            .prep(new FrameInfo(WaterSurface.epoch+time))
            .prep(new Atmos.Env(0,false,false,-1,new float[]{0,0,1},new float[]{.7f,.7f,.6f},new float[]{.65f,.75f,.8f},null));
        view.basic.ostate(p->{for(State state:base.states())if(state!=null)state.apply(p);});
        Pipe input=new BufPipe().prep(new FragColor<>(scene.image(0))).prep(new DepthBuffer<>(depth.image(0))).prep(new States.Viewport(Area.sized(SIZE)));
        Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
                Render out=window.env().render();out.clear(input,FragColor.fragcol,new FColor(.07f,.10f,.025f,1));out.clear(input,blocked?0:1);
                grass.run(new GOut(out,target,SIZE),scene.sampler());
                CompletableFuture<float[]> read=new CompletableFuture<>();
                out.pget(output.image(0),RGBA,bytes->{float[] p=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(p);read.complete(p);});
                window.swapbuffers(out,false);window.env().submit(out);
                while(!read.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
                float[] pixels=read.get(1,TimeUnit.SECONDS);
                require(System.nanoTime()<deadline,"Grass pipeline failed to compile");
                if(((haven.render.vk.VkRender)out).pendingDraws()==0)return pixels;
            }
        }finally{grass.dispose();view.dispose();scene.dispose();output.dispose();depth.dispose();}
    }
    static double difference(float[] a,float[] b){double d=0;for(int i=0;i<a.length;i++)d+=Math.abs(a[i]-b[i]);return d/a.length;}
    static void save(float[] p,String name)throws Exception {
        BufferedImage image=new BufferedImage(SIZE.x,SIZE.y,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++) {
            int at=(y*SIZE.x+x)*4,rgb=0;for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.max(0,Math.min(1,p[at+c]))*255);
            image.setRGB(x,SIZE.y-y-1,rgb);
        }
        new File("build/grass-preview").mkdirs();ImageIO.write(image,"png",new File("build/grass-preview/"+name+".png"));
    }
    public static void main(String[] args)throws Exception {
        geometry();layout();Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int status=0;
        try {
            window.title("Grass Vulkan regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] still=capture(window,10,false,false),wind=capture(window,10.8,false,false),bend=capture(window,10,true,false);
            float[] recovered=capture(window,12,true,false),noTrail=capture(window,12,false,false),blocked=capture(window,10,true,true);
            float[] distant=capture(window,10,false,false,1,Coord.of(20,0));
            float[] sparse=capture(window,10,false,false,.25f,Coord.z),thick=capture(window,10,false,false,2,Coord.z);
            require(difference(distant,blocked)>.0001,"Visible terrain far from the player has no grass");
            require(difference(sparse,blocked)<difference(thick,blocked)*.5,"Quantity slider does not visibly change coverage");
            save(sparse,"quantity-25");save(thick,"quantity-200");
            save(still,"grass");save(bend,"bent");
            System.out.printf("Grass differences: wind %.8f, contact %.8f%n",difference(still,wind),difference(still,bend));
            require(difference(still,wind)>.0001,"Wind not animated");
            require(difference(still,bend)>.00001,"Player does not bend grass");
            require(difference(recovered,noTrail)<.000001,"Grass fails to recover");
            for(int i=0;i<blocked.length;i+=4)require(Math.abs(blocked[i]-.07)<.00001&&Math.abs(blocked[i+1]-.10)<.00001&&Math.abs(blocked[i+3]-1)<.00001,"Grass renders through foreground");
            save(still,"grass");save(bend,"bent");
            if(Arrays.asList(args).contains("--preview"))for(int i=0;i<32;i++)save(capture(window,10+i/16.0,true,false),String.format("frame-%02d",i));
            System.out.printf("Grass Vulkan PASS: wind %.6f, bending %.6f, recovery, depth occlusion, distant visible terrain and quantity slider%n",difference(still,wind),difference(still,bend));
        }catch(Throwable failure){failure.printStackTrace();status=1;}finally{window.dispose();toolkit.dispose();}
        System.exit(status);
    }
}
