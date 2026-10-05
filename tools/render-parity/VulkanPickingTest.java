package haven.render;

import haven.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import haven.render.sl.*;
import java.util.concurrent.*;

/** The first ID-buffer read must work without drawing a second picking pass. */
public class VulkanPickingTest {
    private static final Coord SIZE = Coord.of(64,64);
    private static final VectorFormat IDS = new VectorFormat(1,NumberFormat.SINT32);

    private static class ColdShader implements ShaderMacro {
        final CountDownLatch gate;
        ColdShader(boolean blocked) { gate=new CountDownLatch(blocked ? 1 : 0); }
        public void modify(ProgramContext prog) {
            try {
                if(!gate.await(10,TimeUnit.SECONDS)) throw new AssertionError("Compiler gate timed out");
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
    }

    private static int[] pick(Windeye window, DrawList list, Pipe pipe, Texture2D ids,
                              ColdShader compiler, RenderTree initial) throws Exception {
        Render out=window.env().render();
        out.clear(pipe,FragID.fragid,FColor.BLACK);
        if(initial!=null) list.asyncadd(initial,Rendered.class);
        list.draw(out);
        // In the reproduction, release compilation only after the one-shot draw
        // has been recorded. Submitting again must not be needed to honor a click.
        compiler.gate.countDown();
        CompletableFuture<int[]> result=new CompletableFuture<>();
        out.pget(ids.image(0),IDS,bytes -> result.complete(new int[]{
            bytes.getInt((32*64+16)*4),bytes.getInt((32*64+48)*4)}));
        window.swapbuffers(out,false); window.env().submit(out);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!result.isDone() && System.nanoTime()<deadline) {
            Render pump=window.env().render(); window.swapbuffers(pump,false); window.env().submit(pump);
            Thread.sleep(10);
        }
        return result.get(1,TimeUnit.SECONDS);
    }

    private static void check(Windeye window, boolean legacy, int round) throws Exception {
        Texture2D ids=new Texture2D(SIZE,DataBuffer.Usage.STATIC,IDS,null);
        Model model=new Model(Model.Mode.TRIANGLES,new VertexArray(new VertexArray.Layout(
            new VertexArray.Layout.Input(Ortho2D.pos,new VectorFormat(2,NumberFormat.FLOAT32),0,0,8)),
            new VertexArray.Buffer(48,DataBuffer.Usage.STATIC,DataBuffer.Filler.of(new float[]{
                0,0, 32,0, 32,64, 0,0, 32,64, 0,64}))),null);
        RenderTree tree=new RenderTree();
        DrawList list=window.env().drawlist().async(legacy);
        ColdShader shader=new ColdShader(legacy);
        try {
            Pipe.Op target=Pipe.Op.compose(new FragID<>(ids.image(0)),new States.Viewport(Area.sized(SIZE)),
                new Ortho2D(Area.sized(SIZE)));
            RenderTree.Slot slot=tree.add(model,Pipe.Op.compose(target,new FragID.ID(37),new RUtils.AdHoc(shader)));
            int[] first=pick(window,list,new BufPipe().prep(target),ids,shader,tree);
            if(first[0] != (legacy ? 0 : 37) || first[1] != 0)
                throw new AssertionError("First click: hit="+first[0]+", empty="+first[1]+", legacy="+legacy);
            if(!legacy) {
                // Changing the selection shader must not retain a stale object's ID.
                ColdShader changed=new ColdShader(false);
                slot.ostate(Pipe.Op.compose(target,new FragID.ID(83),new RUtils.AdHoc(changed)));
                int[] second=pick(window,list,new BufPipe().prep(target),ids,changed,null);
                if(second[0]!=83 || second[1]!=0) throw new AssertionError("Picking state change lost or stale");
            }
            System.out.printf("%s picking %d: first hit=%d, empty=%d%n",legacy ? "Reproduced old" : "Fixed",round,first[0],first[1]);
        } finally {
            shader.gate.countDown(); tree.remove(list); list.dispose(); tree.dispose(); model.dispose(); ids.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();
        Windeye window=toolkit.window();
        int exit=0;
        try {
            window.title("Vulkan first-click regression");
            window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            check(window,true,0);
            for(int i=0;i<3;i++) check(window,false,i);
            System.out.println("Vulkan picking: PASS (cold first click, empty space, shader changes, recreated lists)");
        } catch(Throwable failure) { failure.printStackTrace(); exit=1; }
        finally { window.dispose(); toolkit.dispose(); }
        System.exit(exit);
    }
}
