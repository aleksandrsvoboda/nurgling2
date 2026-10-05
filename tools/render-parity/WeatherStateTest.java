package haven;

import haven.render.*;
import haven.res.gfx.fx.wet.Wet;
import java.util.*;
import haven.iosys.tk.*;
import haven.render.sl.*;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/** Reproduce the weather definition change on a populated render tree. */
public class WeatherStateTest {
    static void require(boolean value,String message) {if(!value) throw new AssertionError(message);}
    static class Leaf implements RenderTree.Node {}
    static class Observer implements RenderList<Leaf> {
        int slots,groups;
        public void add(RenderList.Slot<? extends Leaf> slot) {slot.state();}
        public void remove(RenderList.Slot<? extends Leaf> slot) {}
        public void update(RenderList.Slot<? extends Leaf> slot) {slots++;slot.state();}
        public void update(Pipe group,int[] mask) {if(mask.length>0) groups++;}
        void reset() {slots=groups=0;}
    }
    static class View extends PView {
        View() {super(Coord.of(32,32));}
        protected void basic() {}
    }
    static Pipe.Op compose(boolean stable,Pipe.Op... ops) {
        return stable?WeatherState.compose(ops):Pipe.Op.compose(ops);
    }
    static void populated(boolean stable) {
        View view=new View();Observer observer=new Observer();int[] evaluations={0};
        try {
            view.basic(Glob.Weather.class,compose(stable));
            observer.syncadd(view.tree,Leaf.class);
            Pipe.Op local=p->{evaluations[0]++;new BaseColor(new FColor(.3f,.4f,.5f)).apply(p);};
            List<RenderTree.Slot> leaves=new ArrayList<>();
            for(int i=0;i<10000;i++) leaves.add(view.basic.add(new Leaf(),local));
            int[] dependent={0};
            RenderTree.Slot dependency=view.basic.add(new RenderTree.Node(){},p->{
                dependent[0]++;
                Wet wet=p.get(Wet.slot);
                new BaseColor(wet==null?new FColor(0,0,0):wet.col).apply(p);
            });
            dependency.state();
            Wet wet=new Wet(new FColor(.2f,.3f,.4f),12);
            evaluations[0]=0;observer.reset();dependent[0]=0;
            long start=System.nanoTime();
            view.basic(Glob.Weather.class,compose(stable,wet));
            double elapsed=(System.nanoTime()-start)/1e6;
            if(stable) {
                require(evaluations[0]==0&&observer.slots==0,"Weather traversed all unchanged descendants");
                require(observer.groups>0&&dependent[0]>0,"Weather skipped group/dependency updates");
            } else require(evaluations[0]==10000&&observer.slots==10000,"Test did not reproduce the old full-tree rebuild");
            for(RenderTree.Slot leaf:leaves)require(leaf.state().get(Wet.slot)==wet,"Wet state did not reach descendants");
            System.out.printf("Weather %s: %.3f ms, descendant evaluations %d, slot updates %d, group updates %d%n",
                    stable?"stable":"old",elapsed,evaluations[0],observer.slots,observer.groups);
            if(!stable) return;
            GroupPipe inheritance=leaves.get(0).state();
            CloudShadow cloud=new CloudShadow(null,new DirLight(new FColor(1,1,1),Coord3f.zu),Coord3f.o,1);
            boolean[] enabled={true};
            Pipe.Op dynamic=p->{if(enabled[0])cloud.apply(p);};
            for(int i=0;i<40;i++) {
                evaluations[0]=0;observer.reset();
                enabled[0]=(i%2==0);
                // The resource returns fresh operations with changing captured values.
                Pipe.Op weather=p->{dynamic.apply(p);if(enabled[0])wet.apply(p);};
                view.basic(Glob.Weather.class,WeatherState.compose(weather));
                require(evaluations[0]==0&&observer.slots==0,"Weather toggle rebuilt scene");
                require(leaves.get(0).state()==inheritance,"Weather invalidated inheritance groups");
                require(inheritance.get(Wet.slot)==(enabled[0]?wet:null),"Wet removal left stale state");
                require(inheritance.get(CloudShadow.slot)==(enabled[0]?cloud:null),"Cloud toggle left stale state");
            }
            view.basic(Glob.Weather.class,WeatherState.compose(wet));
            try {
                view.basic(Glob.Weather.class,WeatherState.compose(p->{p.put(Wet.slot,null);throw new Loading();}));
                throw new AssertionError("Loading not propagated");
            } catch(Loading expected) {}
            require(inheritance.get(Wet.slot)==wet,"Failed weather update corrupted previous state");
            // An absent resource must reveal the parent's value, not freeze a null override.
            view.basic(Glob.Weather.class,WeatherState.compose());
            view.conf.ostate(wet);
            require(leaves.get(0).state().get(Wet.slot)==wet,"Inherited weather was masked");
            Wet changed=new Wet(new FColor(.7f,.6f,.5f),30);
            view.conf.ostate(changed);
            require(leaves.get(0).state().get(Wet.slot)==changed,"Parent weather dependency lost");
            view.basic(Glob.Weather.class,WeatherState.compose(p->p.put(Wet.slot,null)));
            require(leaves.get(0).state().get(Wet.slot)==null,"Explicit null weather override ignored");
        } finally {view.tree.remove(observer);view.dispose();}
    }
    static void gpu() throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();
        View view=new View();DrawList list=window.env().drawlist().async(false);
        VectorFormat format=new VectorFormat(4,NumberFormat.FLOAT32);
        Texture2D image=new Texture2D(Coord.of(32,32),DataBuffer.Usage.STATIC,format,null);
        try {
            window.title("Weather group update regression");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            Uniform value=new Uniform(Type.VEC4,p->{
                Wet wet=p.get(Wet.slot);CloudShadow cloud=p.get(CloudShadow.slot);
                return new float[]{wet==null?.03f:wet.col.r,cloud==null?.04f:cloud.rmin,wet==null?.05f:wet.shine/100,1};
            },Wet.slot,CloudShadow.slot);
            ShaderMacro shader=p->FragColor.fragcol(p.fctx).mod(in->value.ref(),100);
            Pipe.Op target=Pipe.Op.compose(new FragColor<>(image.image(0)),new States.Viewport(Area.sized(Coord.of(32,32))),new RUtils.AdHoc(shader));
            view.basic("target",target);
            view.basic(Glob.Weather.class,WeatherState.compose());
            view.basic.add(new Rendered.ScreenQuad(false));
            list.syncadd(view.tree,Rendered.class);
            for(int i=0;i<12;i++) {
                Wet wet=(i%3==0)?null:new Wet(new FColor(.1f+i*.04f,.3f,.4f),10+i);
                CloudShadow cloud=null;
                if(i%2==0) {
                    cloud=new CloudShadow(null,new DirLight(new FColor(1,1,1),Coord3f.zu),Coord3f.o,1);
                    cloud.rmin=.2f+i*.03f;
                }
                view.basic(Glob.Weather.class,WeatherState.compose(wet,cloud));
                Render out=window.env().render();
                out.clear(new BufPipe().prep(target),FragColor.fragcol,new FColor(0,0,0,1));
                list.draw(out);
                CompletableFuture<float[]> result=new CompletableFuture<>();
                out.pget(image.image(0),format,bytes->{float[] rgba=new float[4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(rgba);result.complete(rgba);});
                window.swapbuffers(out,false);window.env().submit(out);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!result.isDone()&&System.nanoTime()<deadline) {
                    Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
                }
                float[] rgba=result.get(1,TimeUnit.SECONDS);
                float[] expected={wet==null?.03f:wet.col.r,cloud==null?.04f:cloud.rmin,wet==null?.05f:wet.shine/100,1};
                for(int c=0;c<4;c++)require(Math.abs(rgba[c]-expected[c])<.0001,"Stale GPU weather on transition "+i+", channel "+c+": "+rgba[c]+" vs "+expected[c]);
            }
            System.out.println("Weather Vulkan PASS: 12 wet/cloud add/change/remove transitions read back without rebuilding the draw list");
            // The original test above used a null-safe synthetic uniform, which
            // missed the crash in the real Wet.param when its shader disappears.
            ShaderMacro dry=p->FragColor.fragcol(p.fctx).mod(in->haven.render.sl.Cons.vec4(
                haven.render.sl.Cons.l(.03),haven.render.sl.Cons.l(.04),
                haven.render.sl.Cons.l(.05),haven.render.sl.Cons.l(1)),100);
            ShaderMacro wetShader=p->FragColor.fragcol(p.fctx).mod(in->Wet.param.ref(),200);
            view.basic("target",Pipe.Op.compose(new FragColor<>(image.image(0)),
                new States.Viewport(Area.sized(Coord.of(32,32))),new RUtils.AdHoc(dry)));
            view.basic(Glob.Weather.class,WeatherState.compose(new Wet(new FColor(.15f,.3f,.4f),10) {
                public ShaderMacro shader(){return wetShader;}
            }));
            ((haven.render.vk.VkDrawList)list).refresh(); // Only seed the initial wet scene.
            for(boolean asynchronous:new boolean[]{false,true}) {
                list.async(asynchronous);
                if(asynchronous) {
                    // Fresh macro identity: neither program variant is warm in
                    // the program cache for this asynchronous transition set.
                    ShaderMacro coldDry=p->dry.modify(p);
                    view.basic("target",Pipe.Op.compose(new FragColor<>(image.image(0)),
                        new States.Viewport(Area.sized(Coord.of(32,32))),new RUtils.AdHoc(coldDry)));
                }
                for(int i=0;i<12;i++) {
                    Wet wet=(i%3==2)?null:new Wet(new FColor(.15f+i*.025f,.3f,.4f),10+i) {
                        public ShaderMacro shader(){return wetShader;}
                    };
                    view.basic(Glob.Weather.class,WeatherState.compose(wet));
                    float[] expected=wet==null?new float[]{.03f,.04f,.05f,1}:
                        new float[]{wet.col.r,wet.col.g,wet.col.b,wet.shine};
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    while(true) {
                        Render out=window.env().render();
                        out.clear(new BufPipe().prep(target),FragColor.fragcol,new FColor(0,0,0,1));
                        list.draw(out);
                        CompletableFuture<float[]> result=new CompletableFuture<>();
                        out.pget(image.image(0),format,bytes->{float[] rgba=new float[4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(rgba);result.complete(rgba);});
                        window.swapbuffers(out,false);window.env().submit(out);
                        while(!result.isDone()&&System.nanoTime()<deadline) {
                            Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(5);
                        }
                        float[] rgba=result.get(1,TimeUnit.SECONDS);boolean matches=true;
                        for(int c=0;c<4;c++)matches&=Math.abs(rgba[c]-expected[c])<.0001;
                        if(matches)break;
                        require(asynchronous&&System.nanoTime()<deadline,"Wet shader was not replaced on transition "+i);
                        Thread.sleep(5);
                    }
                }
            }
            System.out.println("Real Wet.param PASS: wet/change/dry shader transitions, synchronous and asynchronous draw lists");
        } finally {view.tree.remove(list);list.dispose();view.dispose();image.dispose();window.dispose();toolkit.dispose();}
    }
    public static void main(String[] args) throws Exception {
        populated(false);populated(true);
        System.out.println("Weather state PASS: stable groups, live values/toggles, dependencies, inheritance and Loading rollback");
        if(Arrays.asList(args).contains("--gpu")) gpu();
        System.exit(0);
    }
}
