// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One serialized worker thread for storage and document work, with results handed back to the
 * interface thread. Serialized rather than pooled so a read always observes the writes submitted
 * before it. Holds no Android types, so ordering, failure routing, flush and shutdown are unit tested.
 */
final class Background implements AutoCloseable {
    /** Hands a finished result to the interface thread; {@code Handler::post} in the app. */
    interface Main { void post(Runnable result); }
    interface Work<T> { T run() throws Exception; }

    private static final long SHUTDOWN_WAIT=2000;
    private final Main main;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"mininotes-storage"));
    private volatile boolean closed;

    Background(Main main){this.main=main;}

    /** Runs work off the interface thread. Work runs in submission order; no callback sees the worker thread. */
    <T> void submit(Work<T> work,Consumer<T> done,Consumer<Exception> failed) {
        try {
            worker.execute(()->{
                T value=null;Exception failure=null;
                try{value=work.run();}catch(Exception e){failure=e;}
                final T result=value;final Exception error=failure;
                if(closed)return;
                main.post(()->{if(closed)return;if(error!=null)failed.accept(error);else done.accept(result);});
            });
        } catch(RejectedExecutionException e) { /* Shutting down: the interface that asked is already gone. */ }
    }

    /** Waits for queued work to reach disk, for lifecycle callbacks that can precede process death. */
    boolean flush(long millis) {
        CountDownLatch drained=new CountDownLatch(1);
        try{worker.execute(drained::countDown);}catch(RejectedExecutionException e){return false;}
        try{return drained.await(millis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }

    /**
     * Stops delivering results, without waiting for what is under way and without cutting it short.
     *
     * <p>For a worker whose work is the network. A screen that is going away cannot sit for thirty seconds
     * on a relay that is not answering — but neither should it interrupt one: the thing under way may be
     * the node starting, which belongs to the process and not to the screen, and a node interrupted half
     * way to a relay is a node that never starts again. So what is running runs out, what is queued behind
     * it still runs, nobody is told, and the thread ends by itself.
     */
    void abandon(){closed=true;worker.shutdown();}

    /** Stops delivering results, lets queued work such as closing the database finish, then stops the thread. */
    @Override public void close() {
        closed=true;worker.shutdown();
        try{if(!worker.awaitTermination(SHUTDOWN_WAIT,TimeUnit.MILLISECONDS))worker.shutdownNow();}
        catch(InterruptedException e){worker.shutdownNow();Thread.currentThread().interrupt();}
    }
}
