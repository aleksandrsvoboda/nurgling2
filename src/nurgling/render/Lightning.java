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
    static final double LIFE=.68;
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
        Bolt(Coord3f impact,float height,double born,long seed) {
            this.impact=impact;this.height=height;this.born=born;
            Random random=new Random(seed);
            Coord3f top=impact.add((random.nextFloat()-.5f)*height*.3f,(random.nextFloat()-.5f)*height*.3f,height);
            Coord3f[] trunk=path(top,impact,6,height*.22f,random);
            paths.add(new Path(trunk,1));
            for(int branch=0;branch<5;branch++) {
                int index=8+random.nextInt(39);
                Coord3f root=trunk[index];
                float reach=height*(.14f+random.nextFloat()*.19f);
                double angle=random.nextDouble()*Math.PI*2;
                Coord3f end=root.add((float)Math.cos(angle)*reach,(float)Math.sin(angle)*reach,-reach*.85f);
                end.z=Math.max(impact.z+height*.06f,end.z);
                Coord3f[] fork=path(root,end,4,reach*.23f,random);
                paths.add(new Path(fork,.40f+random.nextFloat()*.25f));
                if(branch<3) {
                    Coord3f sub=fork[7];
                    paths.add(new Path(path(sub,sub.add((float)Math.sin(angle)*reach*.4f,-(float)Math.cos(angle)*reach*.4f,-reach*.35f),3,reach*.10f,random),.25f));
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
    boolean warmed;
    public Lightning(PView view){this.view=view;depth=new SceneFX.Depth(view);}
    // After temporal accumulation and tonemapping: no ghost trails or exposure pumping.
    public int order(){return -87;}

    static double interval(float rain,boolean preview,Random random) {
        return preview?2+random.nextDouble():Math.max(4,13-rain*2)+random.nextDouble()*7;
    }
    static boolean canStrike(float rain,DirLight light) {
        return rain>0 && light!=null && light.dif[0]+light.dif[1]+light.dif[2]>=.001f;
    }
    public void tick(MapView mv,double now) {
        float rain=RainLighting.intensity(mv.weather());
        DirLight light=mv.amblight;
        if(!canStrike(rain,light)) {
            bolt=null;next=Double.NaN;return;
        }
        if(bolt!=null && now-bolt.born>=LIFE)bolt=null;
        if(Double.isNaN(next))next=now+1+random.nextDouble()*2;
        if(mv.sceneDebug.heavyRain() && next-now>3)next=now+2;
        if(now<next)return;
        next=now+.5; // Nonblocking retry when nearby terrain is not loaded.
        try {
            Coord3f center=mv.getcc();
            Pipe scene=view.basic.state();
            Matrix4f transform=Homo3D.prjxf(scene).mul(Homo3D.camxf(scene));
            for(int attempt=0;attempt<12;attempt++) {
                double angle=random.nextDouble()*Math.PI*2,range=35+random.nextDouble()*95;
                double x=center.x+Math.cos(angle)*range,y=center.y+Math.sin(angle)*range;
                Coord3f impact=Coord3f.of((float)x,(float)-y,(float)mv.glob.map.getcz(x,y)+.15f);
                float[] screen=transform.mul4(new float[]{impact.x,impact.y,impact.z,1});
                if(screen[3]<=.01f)continue;
                float sx=screen[0]/screen[3],sy=screen[1]/screen[3];
                if(Math.abs(sx)>.78f || sy<-.8f || sy>.30f)continue;
                bolt=new Bolt(impact,85+random.nextFloat()*40,now,random.nextLong());
                next=now+interval(rain,mv.sceneDebug.heavyRain(),random);
                break;
            }
        }catch(Loading ignored){} // No terrain/resource waits on the frame thread.
    }

    static final Attribute shape=new Attribute(VEC3,"lightningShape");
    static final AutoVarying vshape=WaterWakes.varying(shape);
    static final Uniform age=NPostFX.u(FLOAT,0),depthtex=NPostFX.u(SAMPLER2D,1),pp=NPostFX.u(VEC4,2),pr=NPostFX.u(VEC4,3);
    static final RawFunction color=new RawFunction(VEC4,"lightning_color",6,WaterSurface.source("lightning.glsl"));
    static final ShaderMacro shader=prog->{
        color.define(prog.fctx);
        FragColor.fragcol(prog.fctx).mod(in->color.call(vshape.ref(),Homo3D.frageyev.ref(),age.ref(),depthtex.ref(),pp.ref(),pr.ref()),100);
    };
    static final VertexArray.Layout layout=new VertexArray.Layout(
        new VertexArray.Layout.Input(Homo3D.vertex,new VectorFormat(3,NumberFormat.FLOAT32),0,0,24),
        new VertexArray.Layout.Input(shape,new VectorFormat(3,NumberFormat.FLOAT32),0,12,24));

    static float[] vertices(Bolt bolt,Matrix4f camera) {
        int segments=0;for(Path path:bolt.paths)segments+=path.points.length-1;
        float[] vertices=new float[segments*6*6];int at=0;
        Coord3f toward=Coord3f.of(camera.m[2],camera.m[6],camera.m[10]).norm();
        for(Path path:bolt.paths) {
            Coord3f[] p=path.points,side=new Coord3f[p.length];
            for(int i=0;i<p.length;i++) {
                Coord3f tangent=p[Math.min(i+1,p.length-1)].sub(p[Math.max(0,i-1)]).norm();
                Coord3f cross=tangent.cmul(toward);
                if(cross.abs()<.01f)cross=Coord3f.of(camera.m[0],camera.m[4],camera.m[8]);
                side[i]=cross.norm().mul(1.8f*(.5f+.5f*path.strength));
            }
            int[] corners={0,-1,1,-1,1,1,0,-1,1,1,0,1};
            for(int segment=0;segment<p.length-1;segment++)for(int k=0;k<12;k+=2) {
                int i=segment+corners[k],sign=corners[k+1];
                Coord3f point=p[i].add(side[i].mul(sign));
                vertices[at++]=point.x;vertices[at++]=point.y;vertices[at++]=point.z;
                vertices[at++]=sign;vertices[at++]=path.strength;
                vertices[at++]=Math.max(0,Math.min(1,1-(p[i].z-bolt.impact.z)/bolt.height));
            }
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
            .prep(new NPostFX.Pass(shader,elapsed,depths,params,screen)).prep(States.asynccompile);
        state.put(Homo3D.cam,scene.get(Homo3D.cam));state.put(Homo3D.prj,scene.get(Homo3D.prj));
        state.put(States.facecull,null);
        float[] points=current==null?new float[18]:vertices(current,Homo3D.camxf(scene));
        int pending=g.out instanceof haven.render.vk.VkRender?((haven.render.vk.VkRender)g.out).pendingDraws():0;
        g.out.draw(state,Model.Mode.TRIANGLES,null,layout,points.length/6,points);
        warmed=!(g.out instanceof haven.render.vk.VkRender)||((haven.render.vk.VkRender)g.out).pendingDraws()==pending;
    }
    public void run(GOut g,Texture2D.Sampler2D in) {
        g.image(new TexRaw(in,true),Coord.z,g.sz());
        Texture2D.Sampler2D ds=depth.samp();if(ds==null)return;
        draw(g,view.basic.state(),ds,depth.projparams()[0],Utils.rtime());
    }
    public void dispose(){bolt=null;next=Double.NaN;super.dispose();}
}
