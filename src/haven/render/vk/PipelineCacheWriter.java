package haven.render.vk;

import haven.Warning;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One bounded background save per environment. No cache extraction or file I/O on request(). */
final class PipelineCacheWriter implements AutoCloseable {
    static final class Snapshot implements AutoCloseable {
        final ByteBuffer data;
        private final Runnable release;
        Snapshot(ByteBuffer data, Runnable release) {this.data=data;this.release=release;}
        public void close() {release.run();}
    }

    private final Path file;
    private final Supplier<Snapshot> snapshot;
    private final AtomicLong revision=new AtomicLong();
    private volatile long savedRevision;
    private boolean pending, closed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"Vulkan pipeline cache writer");
        thread.setDaemon(true);
        return thread;
    });

    PipelineCacheWriter(Path file, Supplier<Snapshot> snapshot) {this.file=file;this.snapshot=snapshot;}
    void changed() {revision.incrementAndGet();}

    synchronized void request() {
        if(file==null || closed || pending || revision.get()==savedRevision) return;
        pending=true;
        worker.execute(() -> {
            try {save();}
            finally {synchronized(this) {pending=false;}}
        });
    }

    private void save() {
        long version=revision.get();
        if(file==null || version==savedRevision) return;
        Path temporary=null;
        try(Snapshot copy=snapshot.get()) {
            // A growing native cache can return VK_INCOMPLETE; retry on the next request.
            if(copy==null) return;
            temporary=Files.createTempFile(file.toAbsolutePath().getParent(),"pipelines-",".tmp");
            try(FileChannel out=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                while(copy.data.hasRemaining()) out.write(copy.data);
            }
            // Never truncate the previous valid cache if saving is interrupted or fails.
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            temporary=null;
            savedRevision=version; // Changes during extraction/writing remain dirty.
        } catch(IOException | RuntimeException e) {
            new Warning(e,"could not save Vulkan pipeline cache").issue();
        } finally {
            if(temporary!=null) try {Files.deleteIfExists(temporary);} catch(IOException ignored) {}
        }
    }

    /** Caller first stops pipeline builders. Wait only during shutdown, before destroying Vulkan handles. */
    public void close() {
        synchronized(this) {
            if(!closed) {
                closed=true;
                worker.execute(this::save);
                worker.shutdown();
            }
        }
        await(worker);
    }

    static void await(ExecutorService executor) {
        boolean interrupted=false;
        while(!executor.isTerminated()) {
            try {executor.awaitTermination(1,TimeUnit.SECONDS);}
            catch(InterruptedException e) {interrupted=true;}
        }
        if(interrupted) Thread.currentThread().interrupt();
    }
}
