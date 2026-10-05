package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.resutil.WaterTile;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/** World-space moving-source impulses, rasterized into a local slope/foam field.
 * GPU Gems 2, chapter 18.2.5: analytical local disturbances combined in a texture.
 * No frame-history feedback, simulation grid, vendor API or server state changes. */
public class WaterWakes implements Disposable {
    static final double LIFE=4.8, DISTANCE_STEP=1.5;
    static final int MAX_IMPULSES=1536, MAX_SOURCES=12;
    static final class Track {
        double x,y,time,remainder;
        Track(double x,double y,double time){this.x=x;this.y=y;this.time=time;}
    }
    static final class Impulse {
        final float x,y,z,dx,dy,width,energy,spread;
        final double born;
        Impulse(float x,float y,float z,float dx,float dy,float width,float energy,float spread,double born) {
            this.x=x;this.y=y;this.z=z;this.dx=dx;this.dy=dy;this.width=width;
            this.energy=energy;this.spread=spread;this.born=born;
        }
    }
    final Map<Long,Track> tracks=new HashMap<>();
    final ArrayDeque<Impulse> impulses=new ArrayDeque<>();
    private double lastScan=Double.NEGATIVE_INFINITY;
    private Texture2D.Sampler2D field;

    synchronized void prune(double now) {
        // The render frame timestamp may precede the current simulation tick.
        // Keep newly sampled impulses for the next frame instead of erasing them.
        impulses.removeIf(p->now-p.born>=LIFE);
        tracks.values().removeIf(t->now-t.time>1);
    }
    /** Accept measured movement, not animation velocity: no wakes while blocked. */
    void sample(long id,double x,double y,float z,float width,float front,double now) {
        sample(id,x,y,z,width,front,now,null,0);
    }
    /** Distance to the forward intersection with the rotated local bounding box.
     * Movement is render-world XY; hit boxes use map XY and gob.a. */
    static float frontDistance(nurgling.NHitBox box,double angle,float dx,float dy) {
        double c=Math.cos(angle),s=Math.sin(angle);
        double[] dir={c*dx-s*dy,-s*dx-c*dy};
        double[] lo={Math.min(box.begin.x,box.end.x),Math.min(box.begin.y,box.end.y)};
        double[] hi={Math.max(box.begin.x,box.end.x),Math.max(box.begin.y,box.end.y)};
        double near=Double.NEGATIVE_INFINITY,far=Double.POSITIVE_INFINITY;
        for(int i=0;i<2;i++) {
            if(Math.abs(dir[i])<1e-8) {if(lo[i]>0 || hi[i]<0)return 0;continue;}
            double a=lo[i]/dir[i],b=hi[i]/dir[i];
            near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));
        }
        return Double.isFinite(far) && far>=Math.max(0,near)?(float)far:0;
    }
    synchronized void sample(long id,double x,double y,float z,float width,float front,double now,nurgling.NHitBox box,double angle) {
        Track previous=tracks.get(id);
        if(previous==null) {
            if(tracks.size()<MAX_SOURCES)tracks.put(id,new Track(x,y,now));
            return;
        }
        double dt=now-previous.time,dx=x-previous.x,dy=y-previous.y,distance=Math.hypot(dx,dy);
        if(dt<=0)return;
        double speed=distance/dt;
        if(dt>.6 || distance>Math.max(12,dt*80) || !Double.isFinite(speed)) {
            tracks.put(id,new Track(x,y,now));return; // Camera/session jump or teleport.
        }
        if(distance>1e-8) {
            float ux=(float)(dx/distance),uy=(float)(dy/distance);
            if(box!=null)front=frontDistance(box,angle,ux,uy);
            float energy=(float)Math.min(1.25,speed/9)*Math.min(1.2f,.65f+width*.10f);
            // Resample the travelled segment at fixed spatial intervals. Carry
            // unused distance across updates; never emit just because time passed.
            for(double along=DISTANCE_STEP-previous.remainder;along<=distance+1e-7;along+=DISTANCE_STEP) {
                double fraction=Math.min(1,along/distance);
                impulses.addLast(new Impulse((float)(previous.x+dx*fraction)+ux*front,
                    (float)(previous.y+dy*fraction)+uy*front,z,ux,uy,width,
                    energy,(float)Math.min(16,speed*.50),previous.time+dt*fraction));
            }
            previous.remainder=(previous.remainder+distance)%DISTANCE_STEP;
            if(previous.remainder<1e-7 || DISTANCE_STEP-previous.remainder<1e-7)previous.remainder=0;
            while(impulses.size()>MAX_IMPULSES)impulses.removeFirst();
        }
        previous.x=x;previous.y=y;previous.time=now;
    }
    public void tick(MapView view,double now) {
        if(now-lastScan<.08)return;
        lastScan=now;prune(now);
        List<Gob> objects=new ArrayList<>();
        synchronized(view.glob.oc){for(Gob gob:view.glob.oc)objects.add(gob);}
        Coord3f center;
        try{center=view.getcc();}catch(Loading l){return;}
        objects.sort(Comparator.comparingDouble(g->g.rc.dist(Coord2d.of(center.x,center.y))));
        int count=0;
        for(Gob gob:objects) {
            if(gob.removed || gob.rc.dist(Coord2d.of(center.x,center.y))>220)continue;
            Moving moving=gob.getattr(Moving.class);
            if(moving==null || moving instanceof Following)continue; // Riders/passengers share the carrier's wake.
            try {
                Coord3f p=gob.getc();
                Coord tile=Coord2d.of(p.x,p.y).floor(MCache.tilesz);
                if(!(view.glob.map.tiler(view.glob.map.gettile(tile)) instanceof WaterTile)) {
                    synchronized(this){tracks.remove(gob.id);}continue;
                }
                float z=(float)view.glob.map.getcz(p.x,p.y);
                if(p.z>z+3 || p.z<z-8)continue; // Flying or deeply submerged objects.
                float width=1.15f;
                nurgling.NHitBox box=gob.ngob==null?null:gob.ngob.hitBox;
                if(box!=null) {
                    double sx=Math.abs(box.end.x-box.begin.x),sy=Math.abs(box.end.y-box.begin.y);
                    width=(float)Math.max(1.15,Math.min(8,Math.min(sx,sy)*.42));
                }
                // Haven map Y is opposite render-world Y.
                sample(gob.id,p.x,-p.y,z,width,1.4f,now,box,gob.a);
                if(++count>=MAX_SOURCES)break;
            }catch(Loading ignored){} // Never block rendering for a map/resource load.
        }
    }

    static final Attribute local=new Attribute(VEC2,"wakeLocal"),params=new Attribute(VEC4,"wakeParams"),direction=new Attribute(VEC2,"wakeDirection");
    static AutoVarying varying(Attribute attribute) {
        return new AutoVarying(attribute.type){protected Expression root(VertexContext ctx){return attribute.ref();}};
    }
    static final AutoVarying vlocal=varying(local),vparams=varying(params),vdirection=varying(direction);
    static final RawFunction splat=new RawFunction(VEC4,"wake_splat",4,WaterSurface.source("water-wakes.glsl"));
    static final RUtils.AdHoc shader=new RUtils.AdHoc(prog->{
        splat.define(prog.fctx);
        FragColor.fragcol(prog.fctx).mod(in->splat.call(vlocal.ref(),vparams.ref(),vdirection.ref(),Homo3D.fragmapv.ref()),100);
    });
    static final VertexArray.Layout layout=new VertexArray.Layout(
        new VertexArray.Layout.Input(Homo3D.vertex,new VectorFormat(3,NumberFormat.FLOAT32),0,0,44),
        new VertexArray.Layout.Input(local,new VectorFormat(2,NumberFormat.FLOAT32),0,12,44),
        new VertexArray.Layout.Input(params,new VectorFormat(4,NumberFormat.FLOAT32),0,20,44),
        new VertexArray.Layout.Input(direction,new VectorFormat(2,NumberFormat.FLOAT32),0,36,44));

    synchronized float[] vertices(double now) {
        prune(now);
        float[] vertices=new float[impulses.size()*12*11];int at=0;
        final int[] corners={-1,-1,1,-1,1,1,-1,-1,1,1,-1,1};
        for(Impulse p:impulses) {
            float age=(float)Math.max(0,now-p.born);
            float band=Math.max(.55f,p.width*.25f)+age*.22f;
            float length=Math.max(p.width*.8f,(float)DISTANCE_STEP*2)+age*.45f;
            for(int side:new int[]{-1,1})for(int i=0;i<12;i+=2) {
                // Free wave packets, not an offset polyline: turns cannot fold
                // a connected ribbon into spikes. Both fronts meet at age zero
                // and keep propagating independently when the source stops.
                float nx=p.dx*.5f-p.dy*side*.8660254f,ny=p.dy*.5f+p.dx*side*.8660254f;
                float cross=corners[i]*band*3.5f,along=corners[i+1]*length*3;
                float travel=age*p.spread;
                vertices[at++]=p.x+nx*(travel+cross)-ny*along;
                vertices[at++]=p.y+ny*(travel+cross)+nx*along;vertices[at++]=p.z;
                vertices[at++]=cross;vertices[at++]=along;
                vertices[at++]=age;vertices[at++]=p.width*side;vertices[at++]=p.energy;vertices[at++]=p.spread;
                vertices[at++]=nx;vertices[at++]=ny;
            }
        }
        return vertices;
    }
    Texture2D.Sampler2D render(GOut g,Pipe scene,Coord size,double now) {
        Coord half=Coord.of(Math.max(1,(size.x+1)/2),Math.max(1,(size.y+1)/2));
        if(!NPostFX.fits(field,half,NumberFormat.FLOAT16)) {
            if(field!=null)field.dispose();field=NPostFX.mktarget(half,NumberFormat.FLOAT16);
        }
        // Only camera/projection are inherited: no object materials, depth or lights.
        Pipe state=new BufPipe().prep(Homo3D.state).prep(new FragColor<>(field.tex.image(0)))
            .prep(new States.Viewport(Area.sized(half))).prep(shader).prep(States.asynccompile)
            .prep(FragColor.blend(new BlendMode(BlendMode.Factor.ONE,BlendMode.Factor.ONE)));
        state.put(Homo3D.cam,scene.get(Homo3D.cam));state.put(Homo3D.prj,scene.get(Homo3D.prj));
        g.out.clear(state,FragColor.fragcol,new FColor(0,0,0,0));
        float[] vertices=vertices(now);
        if(vertices.length>0)g.out.draw(state,Model.Mode.TRIANGLES,null,layout,vertices.length/11,vertices);
        return field;
    }
    public synchronized void dispose(){tracks.clear();impulses.clear();if(field!=null){field.dispose();field=null;}}
}
