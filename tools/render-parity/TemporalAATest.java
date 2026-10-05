package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.concurrent.*;

/** GPU checks for motion weighting, history rejection and stable presentation. */
public class TemporalAATest {
    private static final Coord SIZE=Coord.of(64,64);
    private static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.UNORM8);
    private static final VectorFormat DEPTH=new VectorFormat(1,NumberFormat.FLOAT32);
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }

    private static Texture2D colors(boolean checker) {
        return new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,(image,env)->{
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image);
            java.nio.ByteBuffer data=fill.push();
            for(int y=0;y<SIZE.y;y++) for(int x=0;x<SIZE.x;x++) {
                byte c=(byte)(checker ? ((x&1)==0 ? 153 : 102) : 128);
                data.put(c).put(c).put(c).put((byte)255);
            }
            return fill;
        });
    }
    private static Texture2D depths(float value) {
        return new Texture2D(SIZE,DataBuffer.Usage.STATIC,DEPTH,(image,env)->{
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image);
            java.nio.ByteBuffer data=fill.push();
            for(int i=0;i<SIZE.x*SIZE.y;i++) data.putFloat(value);
            return fill;
        });
    }

    private static int capture(Windeye window,float motion,float jitter,float oldDepth,boolean reset) throws Exception {
        Texture2D current=colors(true), history=colors(false), depth=depths(.5f), old=depths(oldDepth);
        Texture2D savedDepth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,DEPTH,null);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        try {
            Matrix4f rep=Transform.makexlate(new Matrix4f(),new Coord3f(motion*2/SIZE.x,0,0));
            if(reset) rep.m[15]=-1;
            Render out=window.env().render();
            Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            GOut g=new GOut(out,pipe,SIZE);
            NPostFX.blit(NPostFX.target(g,savedDepth.sampler()),current.sampler(),
                new NPostFX.Pass(Temporal.ta_depth_sh,old.sampler()));
            NPostFX.blit(g,current.sampler(),new NPostFX.Pass(Temporal.ta_sh,current.sampler(),history.sampler(),
                depth.sampler(),rep,savedDepth.sampler(),new float[]{0,0,2*jitter/SIZE.x,0},new float[]{-.02f,-1.002f,1,0}));
            CompletableFuture<Integer> result=new CompletableFuture<>();
            out.pget(output.image(0),RGBA,bytes->result.complete(bytes.get((32*SIZE.x+16)*4)&255));
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime()<deadline) {
                Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
            }
            return result.get(1,TimeUnit.SECONDS);
        } finally {
            current.dispose();history.dispose();depth.dispose();old.dispose();savedDepth.dispose();output.dispose();
        }
    }

    /* A stationary soft marker rasterized at each of the real Halton offsets.
     * Its displayed centroid must stay fixed, even with no usable history.
     * Check both axes and a scaled output (the presentation pass precedes scaling). */
    private static double[] centroid(Windeye window, Temporal.Jitter jitter, boolean correct, int scale) throws Exception {
        Texture2D source=new Texture2D(SIZE,DataBuffer.Usage.STATIC,new VectorFormat(4,NumberFormat.FLOAT32),(image,env)->{
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image);
            java.nio.ByteBuffer bytes=fill.push();
            for(int y=0;y<SIZE.y;y++) for(int x=0;x<SIZE.x;x++) {
                double dx=x-29.3-jitter.dx*SIZE.x*.5, dy=y-31.6-jitter.dy*SIZE.y*.5;
                float c=(float)Math.exp(-(dx*dx+dy*dy)/32);
                bytes.putFloat(c).putFloat(c).putFloat(c).putFloat(1);
            }
            return fill;
        });
        Coord size=SIZE.mul(scale);
        Texture2D output=new Texture2D(size,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D.Sampler2D stable=NPostFX.mktarget(SIZE,NumberFormat.FLOAT32);
        try {
            Render out=window.env().render();
            Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(Area.sized(size)));
            GOut g=new GOut(out,pipe,size);
            NPostFX.blit(NPostFX.target(g,stable),source.sampler(),new NPostFX.Pass(Temporal.present_sh,source.sampler(),
                correct ? new float[]{jitter.dx,jitter.dy} : new float[]{0,0}));
            g.image(new TexRaw(stable,true),Coord.z,size);
            CompletableFuture<double[]> result=new CompletableFuture<>();
            out.pget(output.image(0),RGBA,bytes->{
                double sum=0,sx=0,sy=0;
                for(int y=0;y<size.y;y++) for(int x=0;x<size.x;x++) {
                    int c=bytes.get((y*size.x+x)*4)&255;
                    sum+=c;sx+=c*x;sy+=c*y;
                }
                result.complete(new double[]{sx/sum,sy/sum});
            });
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime()<deadline) {
                Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
            }
            return result.get(1,TimeUnit.SECONDS);
        } finally {source.dispose();output.dispose();stable.dispose();}
    }

    private static void stablePresentation(Windeye window) throws Exception {
        for(int scale=1;scale<=2;scale++) {
            double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY}, hi={-1,-1};
            double[] rawlo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY}, rawhi={-1,-1};
            for(int i=0;i<8;i++) {
                Temporal.Jitter jitter=(Temporal.Jitter)Temporal.jitter(null,SIZE);
                double[] pos=centroid(window,jitter,true,scale), raw=centroid(window,jitter,false,scale);
                for(int axis=0;axis<2;axis++) {
                    lo[axis]=Math.min(lo[axis],pos[axis]);hi[axis]=Math.max(hi[axis],pos[axis]);
                    rawlo[axis]=Math.min(rawlo[axis],raw[axis]);rawhi[axis]=Math.max(rawhi[axis],raw[axis]);
                }
            }
            for(int axis=0;axis<2;axis++) {
                require(rawhi[axis]-rawlo[axis]>.6*scale,"Fixture no longer reproduces visible jitter");
                require(hi[axis]-lo[axis]<.03*scale,"Stationary scene shakes on axis "+axis+" at scale "+scale);
            }
            System.out.printf("TAA presentation %dx: drift=(%.4f, %.4f) px, uncorrected=(%.4f, %.4f) px%n",
                scale,hi[0]-lo[0],hi[1]-lo[1],rawhi[0]-rawlo[0],rawhi[1]-rawlo[1]);
        }
    }

    /* Unlike a soft centroid marker, thin static lines expose changes in coverage
     * over the jitter cycle. Exercise real projection, history and presentation. */
    private static void staticDetail(Windeye window, int scale, boolean cameraPolicy) throws Exception {
        Coord size=SIZE.mul(scale);
        PView view=new PView(SIZE){protected void basic(){}};
        Texture2D depth=depths(.5f);
        view.depth=depth;
        Temporal.TAA taa=new Temporal.TAA(view);
        Texture2D.Sampler2D input=NPostFX.mktarget(SIZE,NumberFormat.UNORM8);
        Texture2D output=new Texture2D(size,DataBuffer.Usage.STATIC,RGBA,null);
        RawFunction pattern=new RawFunction(haven.render.sl.Type.VEC4,"static_detail",3,
            "vec4 static_detail(vec4 col, vec2 tc, vec2 j) {\n"+
            " vec2 p=(tc-j*0.5)*64.0;\n"+
            " float stripe=step(0.6,fract((p.x+p.y*0.37)/3.1));\n"+
            " return vec4(vec3(0.1+stripe*0.8),1.0);\n}\n");
        haven.render.sl.ShaderMacro patternShader=NPostFX.shader(pattern,NPostFX.u(haven.render.sl.Type.VEC2,0));
        double[] sum=new double[size.x*size.y], squares=new double[sum.length];
        int samples=0;
        try {
            for(int frame=0;frame<96;frame++) {
                Pipe.Op camera=Pipe.Op.compose(Projection.ortho(-1,1,-1,1,.1f,10),
                    new Camera(Transform.makexlate(new Matrix4f(),new Coord3f(cameraPolicy?Math.min(frame,15)*.001f:0,0,0))));
                Temporal.Jitter j;
                if(cameraPolicy) {
                    view.basic(Camera.class,Temporal.cameraFrame(view,SIZE,camera,true));
                    float[] offset=Temporal.jitterof(view);
                    j=new Temporal.Jitter(offset[0],offset[1]);
                } else {
                    j=(Temporal.Jitter)Temporal.jitter(view,SIZE);
                    view.basic(Camera.class,Pipe.Op.compose(camera,j));
                }
                Render out=window.env().render();
                Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0)))
                    .prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(Area.sized(size)));
                GOut g=new GOut(out,pipe,size);
                NPostFX.blit(NPostFX.target(g,input),Temporal.one(),new NPostFX.Pass(patternShader,new float[]{j.dx,j.dy}));
                taa.run(g,input);
                CompletableFuture<byte[]> result=new CompletableFuture<>();
                out.pget(output.image(0),RGBA,bytes->{byte[] data=new byte[size.x*size.y*4];bytes.get(data);result.complete(data);});
                window.swapbuffers(out,false);window.env().submit(out);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!result.isDone()&&System.nanoTime()<deadline) {
                    Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(5);
                }
                byte[] pixels=result.get(1,TimeUnit.SECONDS);
                if(frame>=64) {
                    samples++;
                    for(int i=0;i<sum.length;i++){int c=pixels[i*4]&255;sum[i]+=c;squares[i]+=c*c;}
                }
            }
            double variance=0,mean=0,meanSquare=0;int count=0;
            for(int y=8*scale;y<56*scale;y++)for(int x=8*scale;x<56*scale;x++) {
                int i=y*size.x+x;
                variance+=Math.max(0,squares[i]/samples-Math.pow(sum[i]/samples,2));count++;
                mean+=sum[i]/samples;meanSquare+=Math.pow(sum[i]/samples,2);
            }
            double shimmer=Math.sqrt(variance/count);
            double contrast=Math.sqrt(meanSquare/count-Math.pow(mean/count,2));
            System.out.printf("Static fine-detail %dx (%s): temporal RMS %.3f / 255, spatial contrast %.3f%n",
                scale,cameraPolicy?"production camera":"forced jitter",shimmer,contrast);
            require(shimmer<(cameraPolicy?.1:4),"Stationary fine detail still shimmers: "+shimmer);
            require(contrast>25,"Fine detail blurred away to hide shimmer: "+contrast);
        } finally {taa.dispose();view.depth=null;view.dispose();depth.dispose();input.dispose();output.dispose();}
    }

    private static void cameraScheduling() {
        PView a=new PView(SIZE){protected void basic(){}},b=new PView(SIZE){protected void basic(){}};
        Matrix4f position=Matrix4f.identity();
        Pipe.Op camera=Pipe.Op.compose(new Projection(Matrix4f.identity()),new Camera(position));
        try {
            Temporal.cameraFrame(a,SIZE,camera,true);
            Temporal.cameraFrame(b,SIZE,camera,true);
            for(int frame=0;frame<32;frame++) {
                Temporal.cameraFrame(a,SIZE,camera,true);
                require(java.util.Arrays.equals(Temporal.jitterof(a),new float[2]),"Idle camera starts jittering");
            }
            position.m[12]=.1f;
            Pipe.Op snapshot=Temporal.cameraFrame(a,SIZE,camera,true);
            float[] held=Temporal.jitterof(a);
            require(held[0]!=0||held[1]!=0,"Moving camera lost temporal samples");
            for(int frame=0;frame<32;frame++) {
                Temporal.cameraFrame(a,SIZE,camera,true);
                require(java.util.Arrays.equals(held,Temporal.jitterof(a)),"Stopped camera keeps changing coverage");
            }
            position.m[12]=.2f;
            Pipe frozen=new BufPipe().prep(snapshot);
            require(Math.abs(frozen.get(Homo3D.cam).fin(Matrix4f.id).m[12]-.1f)<.00001,"Frame camera is mutable");
            require(java.util.Arrays.equals(Temporal.jitterof(b),new float[2]),"One session changes another's jitter");
            Pipe disabled=new BufPipe().prep(Temporal.cameraFrame(a,SIZE,camera,false));
            require(java.util.Arrays.equals(disabled.get(Homo3D.prj).fin(Matrix4f.id).m,Matrix4f.id.m),"Disabled TAA leaves projection shifted");
            require(java.util.Arrays.equals(Temporal.jitterof(a),new float[2]),"Disabled TAA leaves stale correction");
            Temporal.cameraFrame(a,SIZE,camera,true);
            position.m[12]=.3f;Temporal.cameraFrame(a,SIZE,camera,true);
            Temporal.cameraFrame(a,SIZE.mul(2),camera,true);
            require(java.util.Arrays.equals(Temporal.jitterof(a),new float[2]),"Resize reuses old pixel scale");
            System.out.println("Camera scheduling: PASS (idle, move/stop, immutable frame, sessions, toggle, resize)");
        } finally {Temporal.cameraFrame(a,SIZE,camera,false);Temporal.cameraFrame(b,SIZE,camera,false);a.dispose();b.dispose();}
    }

    public static void main(String[] args) throws Exception {
        cameraScheduling();
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();
        Windeye window=toolkit.window();
        int exit=0;
        try {
            window.title("Temporal AA motion regression");
            window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            int still=capture(window,0,0,.5f,false), moving=capture(window,8,0,.5f,false);
            int jitter=capture(window,1,1,.5f,false), exposed=capture(window,0,0,.2f,false);
            int reset=capture(window,0,0,.5f,true), outside=capture(window,64,0,.5f,false);
            require(still>128 && still<140,"Stationary TAA no longer accumulates history");
            require(moving>=145 && moving>still+10,"Motion still dominated by stale history");
            require(Math.abs(jitter-still)<=1,"Subpixel jitter mistaken for camera movement");
            require(exposed==153 && reset==153 && outside==153,"Invalid history contaminates current frame");
            stablePresentation(window);
            staticDetail(window,1,false);
            staticDetail(window,2,false);
            staticDetail(window,1,true);
            staticDetail(window,2,true);
            System.out.printf("TAA: PASS (static=%d, motion=%d, jitter=%d, newly exposed=%d, reset=%d, out of view=%d; current=153)%n",
                still,moving,jitter,exposed,reset,outside);
        } catch(Throwable failure) { failure.printStackTrace();exit=1; }
        finally { window.dispose();toolkit.dispose(); }
        System.exit(exit);
    }
}
