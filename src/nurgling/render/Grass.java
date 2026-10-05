package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.util.*;
import java.util.concurrent.*;
import static haven.render.sl.Type.*;

/** Short segmented blades, inspired by Shrine's procedural grass. Geometry is
 * cached in small terrain patches; wind and a decaying player trail run on GPU. */
public class Grass extends RenderContext.PostProcessor {
    // Divide the 25-tile map cut exactly, so selecting grass never requests
    // neighboring cuts outside the terrain's rendered area.
    static final int TILES=5;
    static final float SPAN=TILES*11, MAX_HEIGHT=3.4f;
    static final Attribute root=new Attribute(VEC4,"grassRoot"),shape=new Attribute(VEC4,"grassShape");
    static final MeshBuf.LayerID<MeshBuf.Vec4Layer> roots=new MeshBuf.V4LayerID(root),shapes=new MeshBuf.V4LayerID(shape);
    static final AutoVarying vshape=WaterWakes.varying(shape);
    static final Uniform trailA=NPostFX.u(MAT4,0),trailB=NPostFX.u(MAT4,1);
    static final RawFunction position=new RawFunction(VEC4,"grass_position",5,WaterSurface.source("grass.glsl"));
    static final RawFunction color=new RawFunction(VEC4,"grass_color",3,
        "vec4 grass_color(vec4 s,vec3 sun,vec3 sky) {\n"+
        " vec3 base=mix(vec3(.12,.20,.035),vec3(.32,.40,.075),s.x);\n"+
        " base*=.85+.22*fract(s.z*17.31);\n"+
        " return vec4(base*(sky*.75+sun*.35)*mix(.55,1.0,smoothstep(0.0,.4,s.x)),1); }\n");
    static final ShaderMacro shader=p->{
        position.define(p.vctx);color.define(p.fctx);
        Homo3D.get(p).mapv.mod(in->position.call(root.ref(),shape.ref(),WaterSurface.time.ref(),trailA.ref(),trailB.ref()),20);
        FragColor.fragcol(p.fctx).mod(in->color.call(vshape.ref(),Atmos.usuncol.ref(),Atmos.uskycol.ref()),2000);
    };
    interface Terrain { boolean grass(double x,double y); float height(double x,double y); }
    static boolean eligible(String name){return "gfx/tiles/grass".equals(name);}
    static long seed(int x,int y,int salt) {
        long h=x*0x9e3779b97f4a7c15L+y*0xc2b2ae3d27d4eb4fL+salt;
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return h^(h>>>31);
    }
    static FastMesh build(Coord patch,Terrain terrain) {
        return build(patch,terrain,1);
    }
    static FastMesh build(Coord patch,Terrain terrain,float amount) {
        MeshBuf buf=new MeshBuf();MeshBuf.Vec4Layer r=buf.layer(roots),s=buf.layer(shapes);
        int candidates=Math.round(32*amount),blades=0;
        for(int ty=patch.y*TILES;ty<(patch.y+1)*TILES;ty++)for(int tx=patch.x*TILES;tx<(patch.x+1)*TILES;tx++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            if(!terrain.grass(tx*11+5.5,ty*11+5.5))continue;
            Random random=new Random(seed(tx,ty,113));
            // Cover every tile with jittered strata, including at 25% density.
            // Extending this sequence adds tufts without relocating existing roots.
            for(int tuft=0;tuft<candidates;tuft++) {
                int cell=(tuft*5)&7;
                double x=tx*11+((cell%4)+random.nextDouble())*2.75;
                double y=ty*11+((cell/4)+random.nextDouble())*5.5;
                double left=Math.max(tx*11,x-.65),top=Math.max(ty*11,y-.65);
                double right=Math.min((tx+1)*11,x+.65),bottom=Math.min((ty+1)*11,y+.65);
                for(int blade=0;blade<4;blade++) {
                    double bx=left+random.nextDouble()*(right-left),by=top+random.nextDouble()*(bottom-top);
                    if(!terrain.grass(bx,by))continue;
                    if(blades++>=8000)return buf.mkmesh(); // Stay below 16-bit vertex indices.
                    float z=terrain.height(bx,by)+.015f,h=1.3f+random.nextFloat()*(MAX_HEIGHT-1.3f);
                    float angle=random.nextFloat()*(float)Math.PI*2,width=.10f+random.nextFloat()*.12f;
                    float[] rt={(float)bx,(float)-by,z,h};MeshBuf.Vertex[][] verts=new MeshBuf.Vertex[4][2];
                    for(int k=0;k<4;k++)for(int side=0;side<2;side++) {
                        float t=k/3f;MeshBuf.Vertex vertex=buf.new Vertex(Coord3f.of(rt[0],rt[1],z+h*t),Coord3f.zu);
                        r.set(vertex,rt);s.set(vertex,new float[]{t,side==0?-1:1,angle,width});verts[k][side]=vertex;
                    }
                    for(int k=0;k<3;k++) {
                        buf.new Face(verts[k][0],verts[k][1],verts[k+1][0]);
                        if(k<2)buf.new Face(verts[k][1],verts[k+1][1],verts[k+1][0]);
                    }
                }
            }
        }
        return buf.f.isEmpty()?null:buf.mkmesh();
    }
    static final class Trail {
        static final double STEP=.6;
        final ArrayDeque<float[]> points=new ArrayDeque<>();Coord3f previous;
        double remainder,previousTime;
        void sample(Coord3f p,double now) {
            if(previous!=null) {
                double distance=p.dist(previous);
                if(distance>30||now<previousTime||now-previousTime>2){points.clear();remainder=0;}
                else if(distance>1e-6) {
                    // Preserve unconsumed arc length across frames, including turns.
                    // Interpolate birth times as well as positions on long frames.
                    for(double along=STEP-remainder;along<=distance+1e-6;along+=STEP) {
                        float fraction=(float)Math.min(1,along/distance);
                        Coord3f q=previous.add(p.sub(previous).mul(fraction));
                        double born=previousTime+(now-previousTime)*fraction;
                        points.addLast(new float[]{q.x,-q.y,q.z,(float)(born-WaterSurface.epoch)});
                        while(points.size()>8)points.removeFirst();
                    }
                    remainder=(remainder+distance)%STEP;
                    if(remainder<1e-6||STEP-remainder<1e-6)remainder=0;
                }
            }
            previous=p;previousTime=now;
            points.removeIf(q->now-WaterSurface.epoch-q[3]>1.4);
        }
        Matrix4f matrix(int offset) {
            float[] values=new float[16];for(int i=0;i<4;i++)values[i*4+3]=-100000;
            int i=0;for(float[] q:points){if(i>=offset&&i<offset+4)System.arraycopy(q,0,values,(i-offset)*4,4);i++;}
            return new Matrix4f(values);
        }
    }
    static final class Patch implements Disposable {
        final Coord key;final MapMesh[] revision;final FastMesh mesh;final float amount,low,high;
        Patch(Coord key,MapMesh[] revision,FastMesh mesh){this(key,revision,mesh,1);}
        Patch(Coord key,MapMesh[] revision,FastMesh mesh,float amount){
            this.key=key;this.revision=revision;this.mesh=mesh;this.amount=amount;
            low=mesh==null?0:mesh.nbounds().z;high=mesh==null?0:mesh.pbounds().z;
        }
        public void dispose(){if(mesh!=null)mesh.dispose();}
    }
    final PView view;final Trail trail=new Trail();
    final Map<Coord,Patch> patches=new HashMap<>();
    final Map<Coord,Double> retry=new HashMap<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"grass-patches");t.setDaemon(true);return t;});
    CompletableFuture<Patch> pending;Coord pendingKey;volatile boolean closed;
    CompletableFuture<Map<Coord,Bounds>> selection;
    Map<Coord,Bounds> wanted=Collections.emptyMap();
    // Accessed only by the worker. Bounds are invalidated with terrain meshes.
    final Map<Coord,Bounds> bounds=new HashMap<>();
    Coord center;double nextScan,nextSelection;
    float amount=1;
    Texture2D.Sampler2D result;
    public Grass(PView view){this.view=view;}
    public void configure(float quantity) {
        if(amount!=quantity)nextScan=0;
        amount=quantity;
    }
    public int order(){return -220;}
    static MapMesh[] revision(MCache map,Coord p) {
        Coord lo=p.mul(TILES),hi=lo.add(TILES-1,TILES-1);
        return new MapMesh[]{map.getcut(lo.div(MCache.cutsz)),map.getcut(Coord.of(hi.x,lo.y).div(MCache.cutsz)),
            map.getcut(Coord.of(lo.x,hi.y).div(MCache.cutsz)),map.getcut(hi.div(MCache.cutsz))};
    }
    static Area patchArea(Area cuts) {
        return Area.corni(cuts.ul.mul(MCache.cutsz).div(TILES),cuts.br.mul(MCache.cutsz).sub(1,1).div(TILES));
    }
    static Matrix4f clip(Pipe state) {
        Camera camera=state.get(Homo3D.cam);Projection projection=state.get(Homo3D.prj);
        return camera==null||projection==null?null:projection.fin(Matrix4f.id).mul(camera.fin(Matrix4f.id));
    }
    static boolean visible(Matrix4f clip,Coord key,float low,float high) {
        if(clip==null)return false;
        // AABB support against six clip planes, without allocations per frame.
        // Include blade motion and a small screen margin for camera movement.
        float x=(key.x+.5f)*SPAN,y=-(key.y+.5f)*SPAN,z=(low+high+MAX_HEIGHT)*.5f;
        float xy=SPAN*.5f+MAX_HEIGHT,h=(high+MAX_HEIGHT-low)*.5f;
        float[] m=clip.m;
        for(int axis=0;axis<3;axis++)for(int sign=-1;sign<=1;sign+=2) {
            float margin=axis==2?1:1.1f;
            float a=m[3]*margin+sign*m[axis],b=m[7]*margin+sign*m[axis+4];
            float c=m[11]*margin+sign*m[axis+8],d=m[15]*margin+sign*m[axis+12];
            if(a*x+b*y+c*z+d+(Math.abs(a)+Math.abs(b))*xy+Math.abs(c)*h<0)return false;
        }
        return true;
    }
    static final class Bounds {
        final MapMesh[] revision;final float low,high;
        Bounds(MapMesh[] revision,float low,float high){this.revision=revision;this.low=low;this.high=high;}
    }
    Map<Coord,Bounds> select(MCache map,Area area,Matrix4f camera) {
        bounds.keySet().removeIf(p->!area.contains(p));
        Map<Coord,Bounds> selected=new HashMap<>();
        for(Coord key:area)try {
            if(closed||Thread.currentThread().isInterrupted())break;
            MapMesh[] revision=revision(map,key);Bounds b=bounds.get(key);
            if(b==null||!Arrays.equals(b.revision,revision)) {
                float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
                for(int y=0;y<=TILES;y++)for(int x=0;x<=TILES;x++) {
                    // Sample just inside the upper border to use this cut's surface.
                    double px=key.x*SPAN+Math.min(x*11,SPAN-.001);
                    double py=key.y*SPAN+Math.min(y*11,SPAN-.001);
                    float z=(float)map.getz(MCache.SurfaceID.trn,Coord2d.of(px,py));
                    low=Math.min(low,z);high=Math.max(high,z);
                }
                b=new Bounds(revision,low-.1f,high+.1f);bounds.put(key,b);
            }
            if(visible(camera,key,b.low,b.high))selected.put(key,b);
        }catch(Loading ignored){}
        return selected;
    }
    boolean near(Coord p){return wanted.containsKey(p);}
    public void tick(MapView mv,double now) {
        try {
            Coord3f cc=mv.getcc();center=Coord.of((int)Math.floor(cc.x/SPAN),(int)Math.floor(cc.y/SPAN));
            Gob player=mv.player();if(player!=null)trail.sample(player.getrenderc(),now);
        }catch(Loading ignored){return;}
        if(selection!=null&&selection.isDone()) {
            try {wanted=selection.join();}
            catch(CompletionException e){new Warning(e.getCause(),"Grass visibility scan failed").issue();}
            selection=null;
        }
        if(selection==null&&now>=nextSelection&&mv.terrain.area!=null) {
            Area area=patchArea(mv.terrain.area);Matrix4f camera=clip(view.basic.state());
            selection=CompletableFuture.supplyAsync(()->select(mv.glob.map,area,camera),worker);
            nextSelection=now+.2;
        }
        for(Iterator<Patch> it=patches.values().iterator();it.hasNext();) {Patch p=it.next();if(!near(p.key)){p.dispose();it.remove();}}
        retry.keySet().removeIf(p->!near(p));
        if(pending!=null&&pending.isDone()) {
            try {
                Patch p=pending.join();
                if(p==null)retry.put(pendingKey,now+1);
                else {
                    boolean current=false;
                    try {current=near(p.key)&&p.amount==amount&&Arrays.equals(p.revision,revision(mv.glob.map,p.key));}catch(Loading ignored){}
                    if(current){Patch old=patches.put(p.key,p);if(old!=null)old.dispose();}
                    else p.dispose();
                }
            }catch(CompletionException e){retry.put(pendingKey,now+1);new Warning(e.getCause(),"Grass patch generation failed").issue();}
            pending=null;
        }
        if(pending!=null||now<nextScan)return;
        nextScan=now+.05;
        List<Coord> desired=new ArrayList<>(wanted.keySet());
        desired.sort(Comparator.comparingDouble(c->c.dist(center)));
        for(Coord key:desired)try {
            if(retry.getOrDefault(key,0.0)>now)continue;
            MapMesh[] revision=wanted.get(key).revision;Patch current=patches.get(key);
            boolean sameTerrain=current!=null&&Arrays.equals(current.revision,revision);
            if(sameTerrain&&current.amount==amount)continue;
            // Stale terrain must stop drawing immediately (paving/plowing updates).
            // Keep old density visible until its background replacement is ready.
            if(current!=null&&!sameTerrain){patches.remove(key);current.dispose();}
            MCache map=mv.glob.map;
            float requestedAmount=amount;
            pendingKey=key;
            pending=CompletableFuture.supplyAsync(()->{
                try {
                    FastMesh mesh=build(key,new Terrain(){
                        public boolean grass(double x,double y){Resource tile=map.tilesetr(map.gettile(Coord2d.of(x,y).floor(MCache.tilesz)));return tile!=null&&eligible(tile.name);}
                        public float height(double x,double y){return (float)map.getz(MCache.SurfaceID.trn,Coord2d.of(x,y));}
                    },requestedAmount);
                    Patch p=new Patch(key,revision,mesh,requestedAmount);
                    synchronized(this){if(closed){p.dispose();return null;}}
                    return p;
                }catch(Loading|CancellationException ignored){return null;}
            },worker);
            break;
        }catch(Loading ignored){}
    }
    public void run(GOut g,Texture2D.Sampler2D in) {
        if(view.depth==null||patches.values().stream().noneMatch(p->p.mesh!=null)){g.image(new TexRaw(in,true),Coord.z,g.sz());return;}
        if(!NPostFX.fits(result,in.tex.sz(),in.tex.ifmt.cf)){if(result!=null)result.dispose();result=NPostFX.mktarget(in.tex.sz(),in.tex.ifmt.cf);}
        GOut target=NPostFX.target(g,result);target.image(new TexRaw(in,true),Coord.z,g.sz());
        Pipe state=view.basic.state().copy().prep(Homo3D.state).prep(new FragColor<>(result.tex.image(0)))
            .prep(new DepthBuffer<>(Utils.el(view.depth.images()))).prep(new States.Viewport(Area.sized(in.tex.sz())))
            .prep(new States.Depthtest(States.Depthtest.Test.LE)).prep(States.asynccompile)
            .prep(new NPostFX.Pass(shader,trail.matrix(0),trail.matrix(4)));
        state.put(States.maskdepth.slot,null);state.put(States.facecull,null);state.put(FragColor.blend,null);
        Matrix4f camera=clip(state);
        for(Patch p:patches.values())if(p.mesh!=null&&visible(camera,p.key,p.low,p.high))p.mesh.draw(state,g.out);
        g.image(new TexRaw(result,true),Coord.z,g.sz());
    }
    public synchronized void dispose() {
        closed=true;worker.shutdownNow();
        if(pending!=null)pending.thenAccept(p->{if(p!=null)p.dispose();});
        for(Patch p:patches.values())p.dispose();patches.clear();
        if(result!=null)result.dispose();super.dispose();
    }
}
