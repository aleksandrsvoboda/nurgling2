package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.util.*;
import static haven.render.sl.Type.*;

/** Rain-only world-space discharges. Fractal subdivision and constrained ribbons
 * follow NVIDIA's Lightning SDK example; generation is bounded CPU work and the
 * renderer uses core triangles/fragment shading, without geometry shaders or CUDA. */
public class Lightning extends RenderContext.PostProcessor {
    static final double LIFE=.90;
    static final float STORM_INTENSITY=1.8f;
    static final double MIN_STRIKE_DISTANCE=18*MCache.tilesz.x;
    static final double MAX_STRIKE_DISTANCE=40*MCache.tilesz.x;
    static final class Path {
        final Coord3f[] points;
        final float strength;
        Path(Coord3f[] points,float strength){this.points=points;this.strength=strength;}
    }
    static final class Bolt {
        final List<Path> paths=new ArrayList<>();
        final Coord3f impact;
        final float height;
        final double born;
        final float[] timing;
        Bolt(Coord3f impact,float height,double born,long seed) {
            this(impact,impact.add(0,0,height),born,seed);
        }
        Bolt(Coord3f impact,Coord3f top,double born,long seed) {
            this.impact=impact;this.height=top.z-impact.z;this.born=born;
            Random random=new Random(seed);
            timing=new float[]{.85f+random.nextFloat()*.25f,.20f+random.nextFloat()*.07f,.40f+random.nextFloat()*.09f,random.nextFloat()*19};
            Coord3f[] trunk=path(top,impact,7,height*.34f,random);
            paths.add(new Path(trunk,1));
            int branches=7+random.nextInt(4);
            for(int branch=0;branch<branches;branch++) {
                int index=12+random.nextInt(94);
                Coord3f root=trunk[index];
                float reach=height*(.10f+random.nextFloat()*.26f)*(1-.35f*index/128f);
                double angle=random.nextDouble()*Math.PI*2;
                Coord3f end=root.add((float)Math.cos(angle)*reach,(float)Math.sin(angle)*reach,-reach*.85f);
                end.z=Math.max(impact.z+height*.06f,end.z);
                Coord3f[] fork=path(root,end,5,reach*.45f,random);
                paths.add(new Path(fork,.38f+random.nextFloat()*.30f));
                if(branch%2==0) {
                    Coord3f sub=fork[9+random.nextInt(10)];
                    Coord3f subEnd=sub.add((float)Math.sin(angle)*reach*.5f,-(float)Math.cos(angle)*reach*.5f,-reach*.4f);
                    subEnd.z=Math.max(impact.z+height*.025f,subEnd.z);
                    paths.add(new Path(path(sub,subEnd,4,reach*.20f,random),.22f+random.nextFloat()*.10f));
                }
            }
        }
    }
    static Coord3f[] path(Coord3f a,Coord3f b,int level,float displacement,Random random) {
        Coord3f[] points=new Coord3f[(1<<level)+1];points[0]=a;points[points.length-1]=b;
        subdivide(points,0,points.length-1,displacement,random);return points;
    }
    static void subdivide(Coord3f[] points,int first,int last,float amount,Random random) {
        if(last-first<2)return;
        int middle=(first+last)/2;
        Coord3f a=points[first],b=points[last];
        points[middle]=a.add(b).mul(.5f).add((random.nextFloat()-.5f)*amount,(random.nextFloat()-.5f)*amount,0);
        subdivide(points,first,middle,amount*.66f,random);subdivide(points,middle,last,amount*.66f,random);
    }
    final PView view;
    final SceneFX.Depth depth;
    final Random random=new Random();
    Bolt bolt;
    double next=Double.NaN;
    boolean warmed,flashWarmed;
    public Lightning(PView view){this.view=view;depth=new SceneFX.Depth(view);}
    // After temporal accumulation and tonemapping: no ghost trails or exposure pumping.
    public int order(){return -87;}

    static double interval(float rain,boolean preview,Random random) {
        return preview?2+random.nextDouble():Math.max(4,13-rain*2)+random.nextDouble()*7;
    }
    static boolean canStrike(float rain,DirLight light) {
        return rain>=STORM_INTENSITY && light!=null && light.dif[0]+light.dif[1]+light.dif[2]>=.001f;
    }
    // Fit a world-space cloud endpoint to the current projection. A fixed
    // 85-125 unit bolt starts halfway down the screen when the player zooms out.
    // NDC +Y is the top; the extra margin hides the birth of the leader.
    static Coord3f cloudTop(Coord3f impact,Matrix4f camera,Matrix4f projection) {
        Matrix4f transform=projection.mul(camera);
        float[] ground=transform.mul4(new float[]{impact.x,impact.y,impact.z,1});
        if(ground[3]<=.01f)return null;
        float edge=1.25f;
        float denominator=transform.m[9]-edge*transform.m[11];
        float height=(edge*ground[3]-ground[1])/denominator;
        if(Float.isFinite(height)&&height>1) {
            Coord3f top=impact.add(0,0,height);
            float[] clip=transform.mul4(new float[]{top.x,top.y,top.z,1});
            if(clip[3]>.1f&&Math.abs(clip[0]/clip[3])<.95f&&Math.abs(clip[2])<clip[3])return top;
        }
        // Looking almost straight down cannot project a vertical column to
        // the upper edge. Place the cloud further up-screen, still above the
        // world impact, with the same depth-tested 3D channel between them.
        float[] eye=camera.mul4(new float[]{impact.x,impact.y,impact.z,1});
        float lift=Math.min(85,Math.max(1,-eye[2]*.35f));
        Coord3f top=impact.add(0,0,lift);
        float[] clip=transform.mul4(new float[]{top.x,top.y,top.z,1});
        if(clip[3]<=.1f)return null;
        Coord3f right=Coord3f.of(camera.m[0],camera.m[4],camera.m[8]);
        Coord3f up=Coord3f.of(camera.m[1],camera.m[5],camera.m[9]);
        top=top.add(right.mul((ground[0]/ground[3]*clip[3]-clip[0])/projection.m[0]));
        top=top.add(up.mul((edge*clip[3]-clip[1])/projection.m[5]));
        return top;
    }
    static Coord2d strikePosition(Coord2d player,Random random) {
        double angle=random.nextDouble()*Math.PI*2;
        double near=MIN_STRIKE_DISTANCE,far=MAX_STRIKE_DISTANCE;
        double distance=Math.sqrt(near*near+random.nextDouble()*(far*far-near*near));
        return player.add(Math.cos(angle)*distance,Math.sin(angle)*distance);
    }
    public void tick(MapView mv,double now) {
        float rain=RainLighting.intensity(mv.weather());
        DirLight light=mv.amblight;
        if(!mv.outdoorLighting() || !canStrike(rain,light)) {
            bolt=null;next=Double.NaN;return;
        }
        if(bolt!=null && now-bolt.born>=LIFE)bolt=null;
        if(Double.isNaN(next))next=now+1+random.nextDouble()*2;
        if(mv.sceneDebug.heavyRain() && next-now>3)next=now+2;
        if(now<next)return;
        next=now+.5; // Nonblocking retry when nearby terrain is not loaded.
        try {
            Gob player=mv.player();
            if(player==null)return;
            Coord3f center=player.getrenderc();
            Pipe scene=view.basic.state();
            Matrix4f transform=Homo3D.prjxf(scene).mul(Homo3D.camxf(scene));
            for(int attempt=0;attempt<12;attempt++) {
                Coord2d position=strikePosition(Coord2d.of(center.x,center.y),random);
                double x=position.x,y=position.y;
                Coord3f impact;
                try {impact=Coord3f.of((float)x,(float)-y,(float)mv.glob.map.getcz(x,y)+.15f);}
                catch(Loading ignored){continue;}
                float[] screen=transform.mul4(new float[]{impact.x,impact.y,impact.z,1});
                if(screen[3]<=.01f)continue;
                float sx=screen[0]/screen[3],sy=screen[1]/screen[3];
                if(Math.abs(sx)>.78f || sy<-.8f || sy>.30f)continue;
                Coord3f top=cloudTop(impact,Homo3D.camxf(scene),Homo3D.prjxf(scene));
                if(top==null)continue;
                bolt=new Bolt(impact,top,now,random.nextLong());
                next=now+interval(rain,mv.sceneDebug.heavyRain(),random);
                break;
            }
        }catch(Loading ignored){} // No terrain/resource waits on the frame thread.
    }

    static final Attribute shape=new Attribute(VEC4,"lightningShape");
    static final AutoVarying vshape=WaterWakes.varying(shape);
    static final Uniform age=NPostFX.u(FLOAT,0),depthtex=NPostFX.u(SAMPLER2D,1),pp=NPostFX.u(VEC4,2),pr=NPostFX.u(VEC4,3);
    static final Uniform timing=NPostFX.u(VEC4,4);
    static final String pulseSource=WaterSurface.source("lightning-pulse.glsl");
    static final RawFunction color=new RawFunction(VEC4,"lightning_color",7,pulseSource+WaterSurface.source("lightning.glsl"));
    static final Uniform flashTiming=NPostFX.u(VEC4,2);
    static final RawFunction flashColor=new RawFunction(VEC4,"lightning_flash",5,pulseSource+WaterSurface.source("lightning-flash.glsl"));
    static final ShaderMacro flashShader=NPostFX.shader(flashColor,age,depthtex,flashTiming);
    static final ShaderMacro shader=prog->{
        color.define(prog.fctx);
        FragColor.fragcol(prog.fctx).mod(in->color.call(vshape.ref(),Homo3D.frageyev.ref(),age.ref(),depthtex.ref(),pp.ref(),pr.ref(),timing.ref()),100);
    };
    static final VertexArray.Layout layout=new VertexArray.Layout(
        new VertexArray.Layout.Input(Homo3D.vertex,new VectorFormat(3,NumberFormat.FLOAT32),0,0,28),
        new VertexArray.Layout.Input(shape,new VectorFormat(4,NumberFormat.FLOAT32),0,12,28));

    static float[] vertices(Bolt bolt,Matrix4f camera) {
        int segments=0;for(Path path:bolt.paths)segments+=path.points.length-1;
        float[] vertices=new float[(segments*6+6)*7];int at=0,pathIndex=0;
        Coord3f toward=Coord3f.of(camera.m[2],camera.m[6],camera.m[10]).norm();
        for(Path path:bolt.paths) {
            Coord3f[] p=path.points,side=new Coord3f[p.length];
            for(int i=0;i<p.length;i++) {
                Coord3f tangent=p[Math.min(i+1,p.length-1)].sub(p[Math.max(0,i-1)]).norm();
                Coord3f cross=tangent.cmul(toward);
                if(cross.abs()<.01f)cross=Coord3f.of(camera.m[0],camera.m[4],camera.m[8]);
                float taper=pathIndex==0?1:1-.70f*i/(p.length-1);
                side[i]=cross.norm().mul(4.0f*(.35f+.65f*path.strength)*taper);
            }
            int[] corners={0,-1,1,-1,1,1,0,-1,1,1,0,1};
            for(int segment=0;segment<p.length-1;segment++)for(int k=0;k<12;k+=2) {
                int i=segment+corners[k],sign=corners[k+1];
                Coord3f point=p[i].add(side[i].mul(sign));
                vertices[at++]=point.x;vertices[at++]=point.y;vertices[at++]=point.z;
                vertices[at++]=sign;vertices[at++]=path.strength*(pathIndex==0?1:1-.55f*i/(p.length-1));
                vertices[at++]=Math.max(0,Math.min(1,1-(p[i].z-bolt.impact.z)/bolt.height));
                vertices[at++]=pathIndex;
            }
            pathIndex++;
        }
        // A small corona at the contact point, occluded by scene depth.
        Coord3f right=Coord3f.of(camera.m[0],camera.m[4],camera.m[8]);
        Coord3f up=Coord3f.of(camera.m[1],camera.m[5],camera.m[9]);
        int[] quad={-1,-1,1,-1,1,1,-1,-1,1,1,-1,1};
        for(int i=0;i<12;i+=2) {
            Coord3f p=bolt.impact.add(0,0,.3f).add(right.mul(quad[i]*5.5f)).add(up.mul(quad[i+1]*5.5f));
            vertices[at++]=p.x;vertices[at++]=p.y;vertices[at++]=p.z;
            vertices[at++]=quad[i];vertices[at++]=quad[i+1];vertices[at++]=-1;vertices[at++]=0;
        }
        return vertices;
    }
    void draw(GOut g,Pipe scene,Texture2D.Sampler2D depths,float[] params,double now) {
        Bolt current=bolt;
        float elapsed=current==null?1:(float)Math.max(0,now-current.born);
        if(elapsed>=LIFE && warmed)return;
        Matrix4f projection=Homo3D.prjxf(scene);
        boolean ortho=params[2]>.5f;
        float[] screen={projection.m[0],projection.m[5],ortho?projection.m[12]:-projection.m[8],ortho?projection.m[13]:-projection.m[9]};
        Pipe state=g.basicstate().copy().prep(Homo3D.state).prep(States.Depthtest.none).prep(States.maskdepth)
            .prep(FragColor.blend(new BlendMode(BlendMode.Factor.ONE,BlendMode.Factor.ONE,BlendMode.Factor.ZERO,BlendMode.Factor.ONE)))
            .prep(new NPostFX.Pass(shader,elapsed,depths,params,screen,current==null?new float[]{1,.23f,.44f,0}:current.timing)).prep(States.asynccompile);
        state.put(Homo3D.cam,scene.get(Homo3D.cam));state.put(Homo3D.prj,scene.get(Homo3D.prj));
        state.put(States.facecull,null);
        float[] points=current==null?new float[21]:vertices(current,Homo3D.camxf(scene));
        int pending=g.out instanceof haven.render.vk.VkRender?((haven.render.vk.VkRender)g.out).pendingDraws():0;
        g.out.draw(state,Model.Mode.TRIANGLES,null,layout,points.length/7,points);
        warmed=!(g.out instanceof haven.render.vk.VkRender)||((haven.render.vk.VkRender)g.out).pendingDraws()==pending;
    }
    void drawFlash(GOut g,Texture2D.Sampler2D in,Texture2D.Sampler2D depths,double now) {
        Bolt current=bolt;
        float elapsed=current==null?1:(float)(now-current.born);
        if(elapsed>=LIFE && flashWarmed)return;
        int pending=g.out instanceof haven.render.vk.VkRender?((haven.render.vk.VkRender)g.out).pendingDraws():0;
        g.usestate(States.asynccompile);
        NPostFX.blit(g,in,new NPostFX.Pass(flashShader,elapsed,depths,
            current==null?new float[]{1,.23f,.44f,0}:current.timing));
        flashWarmed=!(g.out instanceof haven.render.vk.VkRender)||((haven.render.vk.VkRender)g.out).pendingDraws()==pending;
    }
    public void run(GOut g,Texture2D.Sampler2D in) {
        g.image(new TexRaw(in,true),Coord.z,g.sz());
        Texture2D.Sampler2D ds=depth.samp();if(ds==null)return;
        double now=Utils.rtime();
        drawFlash(g,in,ds,now);
        draw(g,view.basic.state(),ds,depth.projparams()[0],now);
    }
    public void dispose(){bolt=null;next=Double.NaN;super.dispose();}
}
