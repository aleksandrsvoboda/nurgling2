package nurgling.tools;
import haven.*;
import org.json.*;
import java.util.concurrent.*;

/** Cold catalog loading must not run on, or block, its UI callers. */
public class ItemIconsAsyncTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        int workers=Math.max(2,Runtime.getRuntime().availableProcessors()-1);
        CountDownLatch entered=new CountDownLatch(workers),release=new CountDownLatch(1);
        try {
            for(int i=0;i<workers*2;i++)Defer.later(()->{entered.countDown();release.await();return null;});
            check(entered.await(10,TimeUnit.SECONDS),"Loader gate not ready");
            ItemIcons.registerFallback("Butter","paginae/craft/test-fallback");
            ItemIcons.registerFallback("Async unique menu","paginae/craft/unique");
            ItemIcons.register("Async native override",new JSONObject().put("static","gfx/invobjs/native-test"));
            check(ItemIcons.descriptor("","Butter",false)==null,"Cold archive loaded inline or fallback published before catalog");
            check(ItemIcons.get("","Butter",false)==null,"UI waits for blocked catalog worker");
            check(ItemIcons.descriptor("","Async native override",false).getString("static").equals("gfx/invobjs/native-test"),"Pending index hides native item");
        }finally{release.countDown();}
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);JSONObject butter;
        while((butter=ItemIcons.descriptor("","Butter",false))==null&&System.nanoTime()<end)Thread.sleep(10);
        check(butter!=null&&butter.getString("static").equals("gfx/invobjs/butter"),"Menu fallback overwrote catalog sprite");
        check(ItemIcons.descriptor("","Async unique menu",false).getString("static").equals("paginae/craft/unique"),"Pending menu registration lost");
        check(ItemIcons.descriptor("","Async native override",false).getString("static").equals("gfx/invobjs/native-test"),"Index overwrote native descriptor");
        check(ItemIcons.descriptor("wiki-item:Async native override","",false).getString("static").equals("gfx/invobjs/native-test"),"Wiki alias hides native override");
        check(ItemIcons.descriptor("","Spitroast Bear",false).has("layer"),"Composite item lost");
        System.out.println("Item icons PASS: blocked loader never blocks UI; deferred catalog, native overrides, menu fallback priority and composite sprites");
        System.exit(0);
    }
}
