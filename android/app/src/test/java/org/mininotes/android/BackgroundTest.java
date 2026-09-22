package org.mininotes.android;
import org.junit.After;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class BackgroundTest {
    private final BlockingQueue<Runnable> posted=new LinkedBlockingQueue<>();
    private final Background background=new Background(posted::add);
    private final CountDownLatch held=new CountDownLatch(1);

    @After public void releaseWorker(){held.countDown();background.close();}

    /** Stands in for the interface thread draining its message queue. */
    private void deliverOneResult() throws InterruptedException {
        Runnable result=posted.poll(5,TimeUnit.SECONDS);
        assertNotNull("No result reached the interface thread",result);
        result.run();
    }

    @Test public void storageWorkLeavesTheInterfaceThread() throws Exception {
        AtomicReference<String> worker=new AtomicReference<>();
        background.submit(()->Thread.currentThread().getName(),worker::set,e->fail("Unexpected failure: "+e));
        deliverOneResult();
        assertNotEquals(Thread.currentThread().getName(),worker.get());
    }
    @Test public void workRunsInSubmissionOrder() {
        List<Integer> order=Collections.synchronizedList(new ArrayList<>());
        List<Integer> expected=new ArrayList<>();
        for(int i=0;i<25;i++){final int n=i;expected.add(n);background.submit(()->order.add(n),done->{},e->fail("Unexpected failure: "+e));}
        assertTrue("Queued work did not drain",background.flush(5000));
        assertEquals(expected,order);
    }
    @Test public void aFailedWriteIsReportedAndTheWorkerSurvives() throws Exception {
        Background.Work<String> failing=()->{throw new IllegalStateException("Could not save note");};
        AtomicReference<Exception> failure=new AtomicReference<>();
        background.submit(failing,done->fail("A failed write must not report success"),failure::set);
        deliverOneResult();
        assertEquals("Could not save note",failure.get().getMessage());
        AtomicReference<String> later=new AtomicReference<>();
        background.submit(()->"still working",later::set,e->fail("Unexpected failure: "+e));
        deliverOneResult();
        assertEquals("still working",later.get());
    }
    @Test public void flushWaitsForQueuedWorkToReachStorage() {
        AtomicBoolean written=new AtomicBoolean();
        background.submit(()->{Thread.sleep(150);return written.getAndSet(true);},done->{},e->fail("Unexpected failure: "+e));
        assertTrue(background.flush(5000));
        assertTrue("flush returned before the write finished",written.get());
    }
    @Test public void flushGivesUpInsteadOfBlockingTheInterfaceForever() {
        background.submit(()->{held.await();return null;},done->{},e->{});
        assertFalse("A stuck write must not hold the lifecycle callback",background.flush(100));
    }
    @Test public void resultsStopReachingADestroyedInterface() throws Exception {
        background.submit(()->{held.await();return "late";},done->fail("Delivered after close"),e->fail("Delivered after close"));
        background.close();
        held.countDown();
        assertNull("Nothing may be handed to a destroyed interface",posted.poll(300,TimeUnit.MILLISECONDS));
    }
    @Test public void queuedCleanupStillRunsWhileClosing() {
        AtomicBoolean databaseClosed=new AtomicBoolean();
        background.submit(()->databaseClosed.getAndSet(true),done->{},e->{});
        background.close();
        assertTrue("Closing the database must not be dropped",databaseClosed.get());
    }
    @Test public void abandoningDoesNotWaitForARelayThatIsNotAnswering() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        background.submit(()->{started.countDown();held.await();return "late";},
            done->fail("Delivered after being abandoned"),e->fail("Delivered after being abandoned"));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        long before=System.nanoTime();
        background.abandon();
        long took=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before);
        assertTrue("A screen going away must not wait on the network, and this took "+took+"ms",took<500);
        assertNull("Nothing may be handed to a destroyed interface",posted.poll(300,TimeUnit.MILLISECONDS));
    }
    @Test public void workSubmittedAfterCloseIsIgnored() throws Exception {
        background.close();
        AtomicBoolean ran=new AtomicBoolean();
        background.submit(()->ran.getAndSet(true),done->fail("Delivered after close"),e->fail("Delivered after close"));
        assertFalse(ran.get());
        assertNull(posted.poll(200,TimeUnit.MILLISECONDS));
    }
}
