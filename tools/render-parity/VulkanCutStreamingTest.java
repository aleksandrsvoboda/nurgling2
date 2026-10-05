package haven.render.vk;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;

/** Warm cut publication must not turn all resource preparation into one UI stall. */
public class VulkanCutStreamingTest {
    static void check(boolean ok, String why) {if(!ok)throw new AssertionError(why);}
    static class Part implements Rendered, RenderTree.Node {
        final Model model;
        int prepared;
        float red;
        Part(Model model) {this.model=model;}
        public void draw(Pipe pipe, Render out) {
            prepared++;
            red=pipe.get(BaseColor.slot).color.r;
            // Deterministic stand-in for a costly buffer/uniform preparation.
            try {Thread.sleep(3);} catch(InterruptedException e) {throw new RuntimeException(e);}
            out.draw(pipe,model);
        }
    }
    static void frame(Windeye window, DrawList list, Pipe pipe)throws Exception {
        Render out=window.env().render();
        out.clear(pipe,FragColor.fragcol,FColor.BLACK);
        list.draw(out);
        CompletableFuture<Void> done=new CompletableFuture<>();
        window.swapbuffers(out,false);out.fence(()->done.complete(null));window.env().submit(out);
        done.get(10,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        Model model=null;RenderTree tree=new RenderTree();DrawList list=null;
        try {
            window.title("Vulkan cut streaming budget");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            VkEnvironment env=(VkEnvironment)window.env();
            model=new Model(Model.Mode.TRIANGLES,new VertexArray(new VertexArray.Layout(
                new VertexArray.Layout.Input(Ortho2D.pos,new VectorFormat(2,NumberFormat.FLOAT32),0,0,8)),
                new VertexArray.Buffer(24,DataBuffer.Usage.STATIC,DataBuffer.Filler.of(new float[]{0,0,64,0,32,64}))),null,0,3);
            Pipe.Op state=Pipe.Op.compose(window.fbstate(),new States.Viewport(Area.sized(Coord.of(64,64))),
                new Ortho2D(0,0,64,64),new BaseColor(new FColor(.2f,.8f,.4f,1)));
            Pipe pipe=new BufPipe().prep(state);
            VulkanAsyncDrawTest.program(env,pipe); // Deliberately warm shader, as on a repeated crossing.
            list=env.drawlist();list.syncadd(tree,Rendered.class);
            List<Part> parts=new ArrayList<>();
            long start=System.nanoTime();
            for(int i=0;i<16;i++){Part part=new Part(model);parts.add(part);tree.add(part,state);}
            double attachMs=(System.nanoTime()-start)/1e6;
            check(parts.stream().allMatch(p->p.prepared==0),"Cut attachment prepared warm slots inline");
            Part removed=new Part(model);tree.add(removed,state).remove();
            Part changed=new Part(model);parts.add(changed);
            RenderTree.Slot changing=tree.add(changed,state);
            changing.ostate(Pipe.Op.compose(state,new BaseColor(new FColor(.7f,.2f,.1f,1))));
            int frames=0;
            while(parts.stream().anyMatch(p->p.prepared==0)&&frames++<100) {
                int before=parts.stream().mapToInt(p->p.prepared).sum();
                frame(window,list,pipe);
                int after=parts.stream().mapToInt(p->p.prepared).sum();
                check(after-before<=1,"Several expensive cut parts prepared in one draw: "+(after-before));
            }
            check(parts.stream().allMatch(p->p.prepared==1),"Pending cut parts starved or rebuilt unnecessarily");
            check(removed.prepared==0,"Removed cut was prepared later");
            check(Math.abs(changed.red-.7f)<.001,"Pending slot used stale state");
            // Updates of existing warm nodes use the same budget, keeping old geometry until ready.
            changing.ostate(Pipe.Op.compose(state,new BaseColor(new FColor(.9f,.2f,.1f,1))));
            list.update(changing.cast(Rendered.class)); // Explicit geometry/slot update; uniform-only changes need no rebuild.
            check(changed.prepared==1,"State update bypassed preparation budget");
            frame(window,list,pipe);check(changed.prepared==2&&Math.abs(changed.red-.9f)<.001,"State update never reached prepared slot");
            Part cold=new Part(model);
            Pipe.Op coldState=Pipe.Op.compose(state,new RUtils.AdHoc(p -> {}));
            try(VulkanAsyncDrawTest.Gate gate=new VulkanAsyncDrawTest.Gate(env)) {
                tree.add(cold,coldState);
                frame(window,list,pipe);
                check(cold.prepared==0,"Cold slot waited for blocked shader compiler");
            }
            VulkanAsyncDrawTest.program(env,new BufPipe().prep(coldState));
            frame(window,list,pipe);
            check(cold.prepared==1,"Cold slot did not recover when shader became ready");
            list.async(false);
            Part picking=new Part(model);tree.add(picking,state);
            check(picking.prepared==1,"Synchronous one-shot list was delayed");
            System.out.printf("PASS: warm cut attachment %.3f ms; 17 costly parts spread over %d draws; cold shader worker gate, removal, latest state, updates and synchronous picking preserved%n",attachMs,frames);
        } catch(Throwable e){e.printStackTrace();exit=1;}
        finally {if(list!=null){tree.remove(list);list.dispose();}tree.dispose();if(model!=null)model.dispose();window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
