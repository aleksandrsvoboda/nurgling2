package haven.render.vk;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Slow extraction, bounded queuing, revisions, atomic failures and shutdown without a GPU. */
public class PipelineCacheWriterTest {
    static void require(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
    static void waitFor(CountDownLatch latch) {
        try {require(latch.await(10,TimeUnit.SECONDS),"Gate timed out");}
        catch(InterruptedException e) {throw new AssertionError(e);}
    }
    static PipelineCacheWriter.Snapshot copy(int value,AtomicInteger released) {
        ByteBuffer data=ByteBuffer.allocateDirect(4);data.putInt(value).flip();
        return new PipelineCacheWriter.Snapshot(data,released::incrementAndGet);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("build"),"cache-writer-test-");
        Path file=dir.resolve("pipelines.bin");
        AtomicInteger extracted=new AtomicInteger(),released=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread caller=Thread.currentThread();
        PipelineCacheWriter writer=new PipelineCacheWriter(file,() -> {
            require(Thread.currentThread()!=caller,"Extraction ran on caller");
            int n=extracted.incrementAndGet();
            if(n==1) {entered.countDown();waitFor(release);}
            return copy(n,released);
        });
        ExecutorService callers=Executors.newSingleThreadExecutor();
        try {
            writer.request();require(extracted.get()==0,"Unchanged cache extracted");
            writer.changed();writer.request();waitFor(entered);
            callers.submit(() -> {for(int i=0;i<10000;i++)writer.request();}).get(1,TimeUnit.SECONDS);
            require(extracted.get()==1,"Unbounded duplicate saves");
            writer.changed(); // Must survive the first snapshot and be saved on close.
            Future<?> closing=callers.submit(writer::close);
            Thread.sleep(50);require(!closing.isDone(),"Shutdown destroyed a cache with a pending extraction");
            release.countDown();closing.get(10,TimeUnit.SECONDS);
            require(extracted.get()==2&&released.get()==2,"Latest revision or direct-buffer release lost");
            require(ByteBuffer.wrap(Files.readAllBytes(file)).getInt()==2,"Stale snapshot overwrote latest revision");
            writer.request();writer.close();require(extracted.get()==2,"Closed writer restarted");
        } finally {release.countDown();writer.close();callers.shutdownNow();}

        AtomicInteger cleanSaves=new AtomicInteger();
        try(PipelineCacheWriter clean=new PipelineCacheWriter(file,() -> copy(cleanSaves.incrementAndGet(),new AtomicInteger()))) {
            clean.request();
        }
        require(cleanSaves.get()==0,"Clean shutdown rewrites unchanged cache");

        AtomicInteger attempts=new AtomicInteger(),frees=new AtomicInteger();
        try(PipelineCacheWriter retry=new PipelineCacheWriter(file,() -> {
            if(attempts.incrementAndGet()==1)return null; // Native VK_INCOMPLETE.
            return copy(42,frees);
        })) {
            retry.changed();retry.request();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(attempts.get()==0&&System.nanoTime()<deadline)Thread.sleep(1);
        }
        require(attempts.get()==2&&frees.get()==1,"Incomplete snapshot not retried on shutdown");
        require(ByteBuffer.wrap(Files.readAllBytes(file)).getInt()==42,"Retry failed");

        // Replacement of a nonempty directory must fail without deleting the prior contents.
        Path blocked=Files.createDirectory(dir.resolve("blocked"));
        Files.writeString(blocked.resolve("keep"),"intact");
        AtomicInteger failureFree=new AtomicInteger();
        try(PipelineCacheWriter failure=new PipelineCacheWriter(blocked,() -> copy(7,failureFree))) {failure.changed();}
        require(failureFree.get()==1&&Files.readString(blocked.resolve("keep")).equals("intact"),"Failure lost prior data/buffer");
        try(java.util.stream.Stream<Path> files=Files.list(dir)) {
            require(files.noneMatch(p -> p.toString().endsWith(".tmp")),"Abandoned temporary cache file");
        }
        System.out.println("PASS: nonblocking requests, bounded queue, concurrent revisions, shutdown join, unchanged-cache skip, incomplete retry and atomic failure cleanup");
    }
}
