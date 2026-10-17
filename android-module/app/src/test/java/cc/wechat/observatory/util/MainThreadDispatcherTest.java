package cc.wechat.observatory.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainThreadDispatcherTest {
    private ExecutorService caller;
    private MainThreadDispatcher dispatcher;
    private HandlerThread queryThread;
    private final CountDownLatch releaseQuery = new CountDownLatch(1);

    @Before public void prepare() {
        caller = Executors.newSingleThreadExecutor();
        dispatcher = new MainThreadDispatcher(new Handler(Looper.getMainLooper()));
    }

    @After public void close() throws Exception {
        releaseQuery.countDown();
        caller.shutdownNow();
        assertTrue("query caller did not stop", caller.awaitTermination(5, TimeUnit.SECONDS));
        if (queryThread != null) {
            queryThread.quit();
            queryThread.join(5000);
            assertFalse("query looper did not stop", queryThread.isAlive());
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void pausedMainLooperTimesOutAndCannotExecuteTheQueryLater() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Future<Integer> result = caller.submit(() -> dispatcher.readOnly(queries::incrementAndGet, 30));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertEquals(0, queries.get());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the expired query must never run after the UI resumes", 0, queries.get());
    }

    @Test public void alreadyRunningReadCanFinishButItsLateResultCannotBePublished() throws Exception {
        MainThreadDispatcher target = runningDispatcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interruptedQuery = new AtomicBoolean();
        AtomicReference<String> published = new AtomicReference<>();
        Future<String> result = caller.submit(() -> {
            String value = target.readOnly(() -> {
                entered.countDown();
                try {
                    if (!releaseQuery.await(5, TimeUnit.SECONDS)) throw new AssertionError("read not released");
                    return "late account snapshot";
                } catch (InterruptedException interrupted) {
                    interruptedQuery.set(true);
                    throw interrupted;
                } finally { finished.countDown(); }
            }, 1000);
            published.set(value);
            return value;
        });
        assertTrue("read never started", entered.await(5, TimeUnit.SECONDS));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertEquals("running read was prematurely interrupted", 1L, finished.getCount());
        releaseQuery.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertFalse("cancellation must not interrupt WeChat's main thread", interruptedQuery.get());
        assertNull("only a successful call may publish state", published.get());
        assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
    }

    @Test public void rejectedHandlerPostFailsImmediatelyWithoutRunningTheQuery() throws Exception {
        MainThreadDispatcher stopped = runningDispatcher();
        queryThread.quit();
        queryThread.join(5000);
        assertFalse(queryThread.isAlive());
        AtomicInteger queries = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> stopped.readOnly(queries::incrementAndGet));
        assertEquals(0, queries.get());
    }

    @Test public void checkedExceptionIsPropagatedWithoutAnExecutionExceptionWrapper() throws Exception {
        MainThreadDispatcher target = runningDispatcher();
        IOException original = new IOException("read failed");
        Future<Object> result = caller.submit(() -> target.readOnly(() -> { throw original; }));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(5, TimeUnit.SECONDS));
        // This is the executor's wrapper, not a second FutureTask wrapper.
        assertSame(original, failure.getCause());
    }

    @Test public void callerInterruptionCancelsQueuedReadAndPreservesInterruptFlag() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        AtomicInteger queries = new AtomicInteger();
        Future<Boolean> interrupted = caller.submit(() -> {
            waitingThread.set(Thread.currentThread());
            ready.countDown();
            try {
                dispatcher.readOnly(queries::incrementAndGet);
                return false;
            } catch (InterruptedException expected) {
                return Thread.currentThread().isInterrupted();
            }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        waitingThread.get().interrupt();
        assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, queries.get());
    }

    @Test public void callFromMainThreadExecutesDirectlyOnceWithoutQueueing() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        assertSame(Looper.getMainLooper(), Looper.myLooper());
        assertEquals(Integer.valueOf(1), dispatcher.readOnly(() -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            return queries.incrementAndGet();
        }));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, queries.get());
    }

    @Test public void successfulQueryRunsOnSelectedLooperAndReturnsItsSnapshot() throws Exception {
        MainThreadDispatcher target = runningDispatcher();
        Future<Looper> result = caller.submit(() -> target.readOnly(Looper::myLooper));
        assertSame(queryThread.getLooper(), result.get(5, TimeUnit.SECONDS));
    }

    @Test public void invalidTimeoutNeverSchedulesAQuery() {
        AtomicInteger queries = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> dispatcher.readOnly(queries::incrementAndGet, 0));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.readOnly(queries::incrementAndGet, -1));
        assertEquals(0, queries.get());
    }

    private MainThreadDispatcher runningDispatcher() {
        queryThread = new HandlerThread("read-only-query-test");
        queryThread.start();
        return new MainThreadDispatcher(new Handler(queryThread.getLooper()));
    }
}
