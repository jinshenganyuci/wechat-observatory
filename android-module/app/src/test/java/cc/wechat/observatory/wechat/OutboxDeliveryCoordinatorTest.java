package cc.wechat.observatory.wechat;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OutboxDeliveryCoordinatorTest {
    @Test public void fallbackObservationTimeoutDoesNotCancelOrDuplicateTheNativeTransaction() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(2, 4)) {
            OutboxDeliveryCoordinator.Delivery first = sender.submit("a", 1, "r", "t", "c", () -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { interrupted.set(true); throw e; }
                return SendResult.sent(42);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(sender.hasPending());
            try { first.await(10L); fail(); } catch (TimeoutException expected) { }
            assertTrue(sender.hasPending()); assertFalse(first.isDone()); assertFalse(interrupted.get());
            assertSame(first, sender.submit("a", 1, "r", "t", "c", () -> failSend()));
            release.countDown(); assertEquals(42, first.await().chatRecordId); assertFalse(sender.hasPending());
        } finally { release.countDown(); }
    }
    @Test public void websocketReconnectAndHttpJoinOneNativeTransaction() throws Exception {
        try (OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(4, 8)) {
            AtomicInteger nativeCalls = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            OutboxDeliveryCoordinator.Delivery first = sender.submit("account", 1, "recipient", "text", "created", () -> {
                nativeCalls.incrementAndGet(); entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS)); return SendResult.sent(42);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            OutboxDeliveryCoordinator.Delivery reconnect = sender.submit("account", 1, "recipient", "text", "created", () -> failSend());
            OutboxDeliveryCoordinator.Delivery http = sender.submit("account", 1, "recipient", "text", "created", () -> failSend());
            assertSame(first, reconnect); assertSame(first, http);
            assertFalse(first.isDone()); release.countDown();
            assertEquals(42, first.await().chatRecordId);
            assertEquals(42, sender.submit("account", 1, "recipient", "text", "created", () -> failSend()).await().chatRecordId);
            assertEquals(1, nativeCalls.get());
        }
    }

    @Test public void queueIsBoundedButDuplicateStillJoins() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(1, 4)) {
            OutboxDeliveryCoordinator.Delivery first = sender.submit("a", 1, "r", "t", "c", () -> {
                release.await(); return SendResult.sent(1);
            });
            assertSame(first, sender.submit("a", 1, "r", "t", "c", () -> failSend()));
            try { sender.submit("a", 2, "r", "t", "c", () -> failSend()); fail(); }
            catch (RejectedExecutionException expected) { }
            release.countDown(); first.await();
        } finally { release.countDown(); }
    }

    @Test public void queuedOldAccountIsRejectedBeforeAnyNativeSideEffect() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger nativeCalls = new AtomicInteger();
        try (OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(3, 4)) {
            OutboxDeliveryCoordinator.Delivery running = sender.submit("a", 1, "r", "one", "c", () -> {
                entered.countDown(); release.await(); return SendResult.sent(1);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            OutboxDeliveryCoordinator.Delivery stale = sender.submit("a", 2, "r", "two", "c", () -> {
                if (!current.get()) throw new IllegalStateException("account changed");
                nativeCalls.incrementAndGet(); return SendResult.sent(2);
            });
            current.set(false); release.countDown(); running.await();
            try { stale.await(); fail(); } catch (IllegalStateException expected) { }
            assertEquals(0, nativeCalls.get());
        } finally { release.countDown(); }
    }

    @Test public void nativeJobsNeverOverlapAndShutdownDoesNotInterruptAnAcceptedSend() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean();
        OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(3, 4);
        try {
            OutboxDeliveryCoordinator.Delivery first = sender.submit("a", 1, "r", "one", "c", () -> {
                assertTrue(running.compareAndSet(false, true)); entered.countDown();
                release.await(); running.set(false); return SendResult.sent(1);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            OutboxDeliveryCoordinator.Delivery second = sender.submit("a", 2, "r", "two", "c", () -> {
                assertFalse(running.get()); return SendResult.sent(2);
            });
            sender.close(); release.countDown();
            assertTrue(first.await().ok); assertTrue(second.await().ok);
        } finally { release.countDown(); sender.close(); }
    }

    @Test public void unconfirmedResultIsCachedAndContentOrScopeNeverReusesIt() throws Exception {
        try (OutboxDeliveryCoordinator sender = new OutboxDeliveryCoordinator(3, 4)) {
            SendResult unknown = SendResult.failed(99, "queued but delivery unconfirmed; message was not resubmitted");
            assertSame(unknown, sender.submit("a", 1, "r", "one", "c", () -> unknown).await());
            assertSame(unknown, sender.submit("a", 1, "r", "one", "c", () -> failSend()).await());
            assertEquals(100, sender.submit("b", 1, "r", "one", "c", () -> SendResult.sent(100)).await().chatRecordId);
            assertEquals(101, sender.submit("a", 1, "r", "two", "c", () -> SendResult.sent(101)).await().chatRecordId);
        }
    }

    private static SendResult failSend() { throw new AssertionError("Native send was duplicated"); }
}
