package nurgling.diagnostics;

import haven.*;
import java.awt.Canvas;
import java.awt.event.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Retention, immutable snapshots, server isolation, delayed export and its actual ZIP format. */
public class MovementTraceTest {
    static void require(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); }
    static Field field(String name)throws Exception { Field f=MovementTrace.class.getDeclaredField(name);f.setAccessible(true);return f; }
    static String read(ZipFile zip,String name)throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=zip.getInputStream(zip.getEntry(name))) {
            byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    static Path awaitArchive(Path dir)throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while(System.nanoTime()<deadline) {
            try(java.util.stream.Stream<Path> files=Files.list(dir)) {
                Optional<Path> found=files.filter(p->p.toString().endsWith(".zip")).findFirst();
                if(found.isPresent()) {
                    try(ZipFile zip=new ZipFile(found.get().toFile())) { if(zip.getEntry("README.txt")!=null)return found.get(); }
                    catch(java.io.IOException notFinished) {}
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Asynchronous archive never completed");
    }
    public static void main(String[] args)throws Exception {
        require(MovementTrace.HEADER.split(",").length==MovementTrace.FIELDS,"CSV header width");
        List<MovementTrace.Row> samples=new ArrayList<>();
        for(int i=1;i<=1000;i++) {
            double[] r=MovementTrace.row(i);r[19]=i;
            samples.add(new MovementTrace.Row("frame",r,null));
        }
        FrameStats stats=new FrameStats(samples);
        require(stats.tailCount(.01)==10&&stats.tailCount(.001)==1,"Low sample counts");
        require(Math.abs(stats.low(.01)-1000/995.5)<1e-10&&stats.low(.001)==1,"FPS low must use reciprocal of mean duration");
        require(stats.percentile(.5)==500&&stats.percentile(.99)==990&&stats.percentile(.999)==999,"Nearest rank percentiles");
        require(stats.worst.size()==32&&stats.worst.get(0).values[19]==1000&&samples.get(0).values[19]==1,"Worst frame sorting mutated trace");
        require(stats.over(50)==950,"Long frame threshold must be strict");
        List<MovementTrace.Row> invalid=new ArrayList<>();
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            double[] r=MovementTrace.row(0);r[19]=value;invalid.add(new MovementTrace.Row("frame",r,null));
        }
        FrameStats empty=new FrameStats(invalid);
        require(empty.invalid==4&&empty.worst.isEmpty()&&Double.isNaN(empty.low(.01))&&empty.report().contains("average_fps=unavailable"),"Missing/invalid frame reporting");
        require(new FrameStats(samples.subList(0,3)).tailCount(.001)==1,"Short capture low must retain one sample");
        Canvas source=new Canvas();
        require(MovementTrace.capture.key().match(new KeyEvent(source,KeyEvent.KEY_PRESSED,0,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_F9,KeyEvent.CHAR_UNDEFINED)),"Ctrl+F9 not bound");
        require(!MovementTrace.capture.key().match(new KeyEvent(source,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_F9,KeyEvent.CHAR_UNDEFINED)),"Plain F9 intercepted");
        MovementTrace.Ring small=new MovementTrace.Ring(3);
        for(int i=0;i<5;i++)small.add("frame",MovementTrace.row(i),null);
        List<MovementTrace.Row> snapshot=small.snapshot(0,9);
        require(snapshot.size()==3&&snapshot.get(0).values[0]==2&&small.overwritten==2,"Capacity overflow not bounded/reported");
        small.add("frame",MovementTrace.row(5),null);
        require(snapshot.get(0).values[0]==2,"Export changes when ring wraps");
        small.add("frame",MovementTrace.row(30),null);
        require(small.snapshot(0,40).size()==1,"Old history not expired");
        small.add("server",MovementTrace.row(29.9),null);
        require(small.snapshot(29,31).get(0).values[0]==29.9,"Concurrent frame/server ordering");

        nurgling.NConfig.getGlobalInstance();
        Glob own=new Glob(null),other=new Glob(null);
        MovementTrace trace=new MovementTrace(own);
        field("player").setLong(trace,101);field("subject").setLong(trace,202);field("active").set(null,trace);
        MovementTrace.Ring ring=(MovementTrace.Ring)field("ring").get(trace);
        for(Gob gob:new Gob[]{new Gob(own,Coord2d.z,101),new Gob(own,Coord2d.z,202),new Gob(own,Coord2d.z,303),new Gob(other,Coord2d.z,101)})
            MovementTrace.server(gob,"move",Coord2d.of(4,5),null,Double.NaN,Double.NaN,Double.NaN);
        List<MovementTrace.Row> events=ring.snapshot(0,Double.MAX_VALUE);
        require(events.size()==2,"Events leaked between sessions or missed rider/mount");
        require(events.get(0).values[3]==101&&events.get(1).values[3]==202,"Actor IDs not retained");

        // All callback/loader clones must preserve the original packet and queue timestamps.
        double receipt=Utils.rtime()-.040;
        OCache.ObjDelta delta=new OCache.ObjDelta(0,202,987);
        delta.receivedAt=receipt;delta.packet=123;
        OCache.AttrDelta attr=new OCache.AttrDelta(delta,OCache.OD_MOVE,new byte[]{1,2,3});
        attr.queuedAt=receipt+.004;delta.attrs.add(attr);
        OCache.ObjDelta copy=delta.clone();
        OCache.AttrDelta applied=copy.attrs.get(0).clone();
        require(copy.receivedAt==receipt&&copy.packet==123&&applied.receivedAt==receipt&&
            applied.queuedAt==attr.queuedAt&&applied.frame==987&&applied.packet==123,"Clone lost network timing identity");
        MovementTrace.received(own,copy);
        MovementTrace.received(other,copy);
        double attempt=receipt+.020,locked=receipt+.025;
        MovementTrace.applied(own,202,applied,attempt,locked,7,false);
        MovementTrace.applied(own,202,applied,attempt,locked,7,true);
        MovementTrace.applied(other,202,applied,attempt,locked,7,true);
        events=ring.snapshot(0,Double.MAX_VALUE);
        require(events.size()==5,"Network isolation or retry events broken");
        MovementTrace.Row net=events.stream().filter(r->r.type.equals("net-applied")).findFirst().get();
        require(net.values[50]==receipt&&net.values[51]==attr.queuedAt&&net.values[52]==987&&
            net.values[53]==123&&net.values[54]==OCache.OD_MOVE&&Math.abs(net.values[55]-5)<.01&&
            net.values[56]>0&&net.values[57]==7&&net.values[58]==attempt,"Queue/lock/apply timings incorrect");
        // Nesting must restore the watchdog's parent, including exceptional exits.
        UI stageUI=new UI(null,new Audio.Root(haven.iosys.audio.DummyAudio.instance),Coord.z,null);
        stageUI.movementTrace=trace;
        field("thread").set(trace,Thread.currentThread());
        trace.phase("parent");double parentStart=field("phaseStart").getDouble(trace);
        try(MovementTrace.Stage outer=MovementTrace.stage(stageUI,"outer")) {
            try(MovementTrace.Stage inner=MovementTrace.stage(stageUI,"inner")) {
                Thread.sleep(3);throw new IllegalStateException("stage-test");
            } catch(IllegalStateException expected) {}
            require(field("phase").get(trace).equals("outer"),"Nested scope lost outer phase");
            java.util.concurrent.atomic.AtomicBoolean ignoredThread=new java.util.concurrent.atomic.AtomicBoolean();
            Thread wrongThread=new Thread(()->ignoredThread.set(MovementTrace.stage(stageUI,"wrong-thread")==null));
            wrongThread.start();wrongThread.join();
            require(ignoredThread.get(),"Background thread captured");
        }
        require(field("phase").get(trace).equals("parent")&&field("phaseStart").getDouble(trace)==parentStart,"Scope did not restore parent clock");

        Path dir=Files.createTempDirectory(Paths.get("build"),"movement-trace-test-");
        double now=Utils.rtime();
        ring.add("too-old",MovementTrace.row(now-11),null);
        ring.add("before",MovementTrace.row(now-9),"quoted \"text\", русская строка\nnext line");
        double[] slow=MovementTrace.row(now-.1);slow[19]=85.7683;slow[24]=69.0981;
        ring.add("frame",slow,null);
        require(trace.request(dir),"Capture rejected");
        require(!trace.request(dir),"Key repeat created a duplicate capture");
        Thread.sleep(100);
        ring.add("after",MovementTrace.row(Utils.rtime()),null);
        Path archive=awaitArchive(dir);
        try(ZipFile zip=new ZipFile(archive.toFile())) {
            String csv=read(zip,"trace.csv"),meta=read(zip,"README.txt");
            require(csv.contains("\nbefore,")&&csv.contains("\nafter,")&&csv.contains("\nmark,"),"Missing pre/post trigger records");
            require(!csv.contains("too-old"),"Capture includes older than 10 seconds");
            require(csv.contains("quoted \"\"text\"\", русская строка\nnext line"),"CSV escaping/UTF-8 broken");
            require(meta.contains("mark_time_s=")&&meta.contains("ring_overwrites="),"Capture context missing");
            require(read(zip,"frame-summary.txt").contains("worst_frame_ms=85.7683"),"Summary missing the slow frame");
            String slowCsv=read(zip,"slow-frames.csv");
            require(slowCsv.startsWith("event,"+MovementTrace.HEADER+",detail\n")&&slowCsv.contains("85.7683")&&slowCsv.contains("69.0981"),"Slow-frame export lost timing breakdown");
            String[] header=csv.substring(0,csv.indexOf('\n')).split(",");
            String network=Arrays.stream(csv.split("\n")).filter(l->l.startsWith("net-applied,")).findFirst().get();
            String[] columns=network.split(",",-1);
            double epoch=field("epoch").getDouble(trace);
            for(String clock:new String[]{"received_s","queued_s","stage_start_s"}) {
                int col=Arrays.asList(header).indexOf(clock);
                double expected=clock.equals("received_s")?receipt:clock.equals("queued_s")?attr.queuedAt:attempt;
                require(Math.abs(Double.parseDouble(columns[col])-(expected-epoch))<1e-8,"Export clock origin mismatch: "+clock);
            }
        }
        trace.dispose();
        require(!trace.request(dir),"Disposed session still records");
        require(field("active").get(null)==null,"Session retained after disposal");
        MovementTrace failure=new MovementTrace(own);
        Path notDirectory=dir.resolve("not-a-directory");Files.write(notDirectory,new byte[]{1});
        require(failure.request(notDirectory),"Failure-path capture rejected");
        java.util.concurrent.atomic.AtomicReference<?> notice=(java.util.concurrent.atomic.AtomicReference<?>)field("notice").get(failure);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while(notice.get()==null&&System.nanoTime()<deadline)Thread.sleep(20);
        require(notice.get()!=null,"Write failure was silent");
        synchronized(failure) { /* Wait for pending flag to be released by the writer. */ }
        Path retry=Files.createDirectory(dir.resolve("retry"));
        require(failure.request(retry),"Write failure permanently blocked subsequent captures");
        awaitArchive(retry);failure.dispose();
        System.out.println("Movement trace PASS: Ctrl+F9, ring retention/wrap, session and mount filtering, pre/post trigger ZIP, repeat guard, UTF-8 and disposal");
        System.out.println("Archive: "+archive);
        System.exit(0);
    }
}
