package haven.render.vk;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;

/** Hold compiler workers to prove optional immediate draws cannot compile inline. */
public class VulkanAsyncDrawTest {
    static void require(boolean ok, String why) { if(!ok) throw new AssertionError(why); }
    static class Gate implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        Gate(VkEnvironment env) throws Exception {
            int count = ((ThreadPoolExecutor)env.builders).getCorePoolSize();
            CountDownLatch entered = new CountDownLatch(count);
            for(int i = 0; i < count; i++) env.builders.submit(() -> {
                entered.countDown();
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            if(!entered.await(10,TimeUnit.SECONDS)) { release.countDown(); throw new AssertionError("Compiler workers did not enter gate"); }
        }
        public void close() { release.countDown(); }
    }
    static VkProgram program(VkEnvironment env, Pipe pipe) throws Exception {
        State[] st = pipe.states(); ShaderMacro[] shaders = new ShaderMacro[st.length]; int hash = 0;
        for(int i = 0; i < st.length; i++) { shaders[i] = st[i] == null ? null : st[i].shader(); hash ^= System.identityHashCode(shaders[i]); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime() < deadline) {
            VkProgram p = env.getprogasync(hash,shaders); if(p != null) return p;
            Thread.sleep(5);
        }
        throw new AssertionError("Shader did not become ready");
    }
    static VkRender.DrawCmd drawcmd(VkRender out) {
        for(VkRender.Cmd cmd : out.cmds) if(cmd instanceof VkRender.DrawCmd) return (VkRender.DrawCmd)cmd;
        return null;
    }
    static void skipped(VkRender out, Pipe pipe, Model model) {
        long start = System.nanoTime(); out.draw(pipe,model);
        require(System.nanoTime()-start < TimeUnit.SECONDS.toNanos(1),"Draw waited for compiler worker");
        require(out.pendingDraws()==1 && drawcmd(out)==null,"Unready draw reached the render thread");
    }
    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open(); Windeye window = toolkit.window(); int exit = 0;
        Model model = null;
        try {
            window.title("Nonblocking Vulkan effect preparation");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            VkEnvironment env = (VkEnvironment)window.env();
            VertexArray.Layout layout = new VertexArray.Layout(new VertexArray.Layout.Input(Ortho2D.pos,new VectorFormat(2,NumberFormat.FLOAT32),0,0,8));
            model = new Model(Model.Mode.TRIANGLES,new VertexArray(layout,
                new VertexArray.Buffer(24,DataBuffer.Usage.STATIC,DataBuffer.Filler.of(new float[]{0,0,64,0,32,64}))),null,0,3);
            List<String> threads = new CopyOnWriteArrayList<>();
            ShaderMacro observed = p -> threads.add(Thread.currentThread().getName());
            Pipe base = new BufPipe().prep(window.fbstate()).prep(new States.Viewport(Area.sized(Coord.of(64,64))))
                .prep(new Ortho2D(0,0,64,64)).prep(new BaseColor(new FColor(.2f,.8f,.4f,1)));
            Pipe optional = base.copy().prep(new RUtils.AdHoc(observed)).prep(States.asynccompile);
            try(Gate gate = new Gate(env)) {
                VkRender out = (VkRender)env.render();
                try {
                    out.clear(optional,FragColor.fragcol,FColor.BLACK);
                    require(threads.isEmpty(),"Attachment clear evaluated a material shader");
                    skipped(out,optional,model);
                    require(threads.isEmpty(),"Shader macro ran inline despite occupied workers");
                    out.draw(base,model); // A pending draw must not corrupt the next synchronous draw.
                    require(drawcmd(out)!=null,"Pending state corrupted following draw");
                } finally { out.dispose(); }
            }
            VkProgram prog = program(env,optional);
            require(!threads.isEmpty() && threads.stream().allMatch(n -> n.equals("Vulkan shader compiler")),"Shader compiled on a frame thread");
            try(Gate gate = new Gate(env)) {
                VkRender out = (VkRender)env.render();
                try { skipped(out,optional,model); } finally { out.dispose(); }
            }
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            boolean ready = false;
            while(System.nanoTime()<deadline) {
                VkRender out = (VkRender)env.render(); out.draw(optional,model);
                VkRender.DrawCmd cmd = drawcmd(out);
                if(cmd != null) {
                    require(cmd.key.pipe!=0,"Command would compile pipeline on render thread");
                    require(out.pendingDraws()==0,"Ready draw still marked pending");
                    cmd.key.failure = new IllegalStateException("synthetic pipeline failure");
                    try { prog.pipeready(cmd.key); throw new AssertionError("Async pipeline failure was swallowed"); }
                    catch(RuntimeException expected) { require(expected.getCause()==cmd.key.failure,"Wrong pipeline failure"); }
                    finally { cmd.key.failure=null; }
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    window.swapbuffers(out,false); out.fence(() -> done.complete(null)); env.submit(out);
                    done.get(10,TimeUnit.SECONDS); ready=true; break;
                }
                out.dispose(); Thread.sleep(5);
            }
            require(ready,"Pipeline never became ready");
            // A repeated draw uses the already prepared program and pipeline immediately.
            VkRender repeat = (VkRender)env.render();
            repeat.draw(optional,model); require(repeat.pendingDraws()==0 && drawcmd(repeat).key.pipe!=0,"Warm draw not ready");
            VkRender.DrawCmd template=drawcmd(repeat);
            int bytes=template.prog.ubosize;
            require(bytes>0,"Uniform copy fixture has no data");
            java.nio.ByteBuffer source=java.nio.ByteBuffer.allocate(bytes+8);
            for(int i=0;i<bytes;i++)source.put(4+i,(byte)i);
            source.position(4).limit(4+bytes);
            repeat.draw(template.prog,template.key,template.tgt,template.dyn,template.tex,source,template.geo);
            VkRender.DrawCmd first=(VkRender.DrawCmd)repeat.cmds.get(repeat.cmds.size()-1);
            require(source.position()==4&&source.limit()==4+bytes,"Uniform copy moves source cursor");
            source.put(4,(byte)99);
            repeat.draw(template.prog,template.key,template.tgt,template.dyn,template.tex,source,template.geo);
            VkRender.DrawCmd second=(VkRender.DrawCmd)repeat.cmds.get(repeat.cmds.size()-1);
            require(repeat.arena.get(first.ubo)==0&&repeat.arena.get(second.ubo)==99,"Uniform snapshots share mutable input");
            for(int i=1;i<bytes;i++)require(repeat.arena.get(first.ubo+i)==(byte)i,"Uniform source offset or length corrupted");
            repeat.dispose();
            System.out.println("Async draw PASS: blocked shader/pipeline workers never block frame; ready pipeline recorded; state retry, errors and warm reuse");
        } catch(Throwable failure) { failure.printStackTrace(); exit=1; }
        finally { if(model!=null)model.dispose(); window.dispose(); toolkit.dispose(); }
        System.exit(exit);
    }
}
