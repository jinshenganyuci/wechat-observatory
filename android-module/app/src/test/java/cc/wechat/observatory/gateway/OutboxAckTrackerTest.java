package cc.wechat.observatory.gateway;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class OutboxAckTrackerTest {
    @Test public void closeDuringResultObservationPreventsLateAckAndNewTracking() throws Exception {
        CountDownLatch observing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger(); AtomicReference<Throwable> error = new AtomicReference<>();
        OutboxAckTracker tracker = new OutboxAckTracker(a -> writes.incrementAndGet(), e -> error.set(e), () -> 0L, false);
        tracker.track(1, "task", () -> { observing.countDown(); release.await(); return ack(); });
        Thread observer = new Thread(tracker::tick); observer.setDaemon(true); observer.start();
        try {
            assertTrue(observing.await(2, TimeUnit.SECONDS)); tracker.close();
            tracker.track(2, "late", () -> { throw new AssertionError("Closed tracker accepted a task"); });
            release.countDown(); observer.join(2000); tracker.tick();
            assertFalse(observer.isAlive()); assertEquals(0, writes.get()); assertNull(error.get());
        } finally { release.countDown(); tracker.close(); }
    }
    @Test public void idleConnectionHasNoTimerAndFinalReceiptStopsFutureWakeups() throws Exception {
        AtomicInteger clockReads = new AtomicInteger(), writes = new AtomicInteger();
        CountDownLatch sent = new CountDownLatch(1);
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> { writes.incrementAndGet(); sent.countDown(); }, e -> fail(),
                () -> { clockReads.incrementAndGet(); return 0L; }, true)) {
            Thread.sleep(150L); assertEquals(0, clockReads.get());
            tracker.track(1, "task", OutboxAckTrackerTest::ack);
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            assertTrue(tracker.acknowledge(1, "failed", 42, "delivery unconfirmed"));
            int afterReceipt = clockReads.get();
            Thread.sleep(150L); assertEquals(afterReceipt, clockReads.get()); assertEquals(1, writes.get());
        }
    }

    @Test public void newTaskWakesImmediatelyInsteadOfWaitingForAnOldReceiptsRetry() throws Exception {
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> {
            if (a.id == 1) first.countDown(); else second.countDown();
        }, e -> fail(), () -> 0L, true)) {
            tracker.track(1, "one", OutboxAckTrackerTest::ack);
            assertTrue(first.await(2, TimeUnit.SECONDS));
            tracker.track(2, "two", () -> new OutboxAckTracker.Ack(2, "sent", 43, "", "second"));
            assertTrue("A new result must not wait for the old ACK retry timer", second.await(500, TimeUnit.MILLISECONDS));
        }
    }

    private static OutboxAckTracker.Ack ack() {
        return new OutboxAckTracker.Ack(1, "failed", 42, "delivery unconfirmed", "exact original ACK");
    }

    @Test public void retriesOnlyTheSameResultAtOneTwoFourThenEightSeconds() {
        AtomicLong now = new AtomicLong(); List<String> writes = new ArrayList<>();
        AtomicInteger observations = new AtomicInteger();
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> writes.add(a.payload), e -> fail(), now::get, false)) {
            tracker.track(1, "task", () -> { observations.incrementAndGet(); return ack(); });
            long[] times = {0, 999, 1000, 2999, 3000, 6999, 7000, 14999, 15000, 22999, 23000};
            int[] counts = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6};
            for (int i = 0; i < times.length; i++) {
                now.set(times[i]); tracker.tick(); assertEquals(counts[i], writes.size());
            }
            assertEquals(1, observations.get());
            for (String write : writes) assertEquals("exact original ACK", write);
            assertTrue(tracker.acknowledge(1, "failed", 42, "delivery unconfirmed"));
            now.set(99999); tracker.tick(); assertEquals(6, writes.size());
        }
    }

    @Test public void wrongReceiptCannotClearPendingResult() {
        AtomicLong now = new AtomicLong(); AtomicInteger writes = new AtomicInteger();
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> writes.incrementAndGet(), e -> fail(), now::get, false)) {
            tracker.track(1, "task", OutboxAckTrackerTest::ack); tracker.tick();
            assertFalse(tracker.acknowledge(2, "failed", 42, "delivery unconfirmed"));
            assertFalse(tracker.acknowledge(1, "sent", 42, "delivery unconfirmed"));
            assertFalse(tracker.acknowledge(1, "failed", 43, "delivery unconfirmed"));
            assertFalse(tracker.acknowledge(1, "failed", 42, "different"));
            now.set(1000); tracker.tick(); assertEquals(2, writes.get());
        }
    }

    @Test public void pendingNativeResultNeverBlocksTickAndClosedConnectionStopsObserving() {
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        OutboxAckTracker tracker = new OutboxAckTracker(a -> writes.incrementAndGet(), e -> fail(), () -> 0, false);
        tracker.track(1, "task", () -> { reads.incrementAndGet(); return null; });
        tracker.tick(); assertEquals(1, reads.get()); assertEquals(0, writes.get());
        tracker.close(); tracker.tick(); assertEquals(1, reads.get());
    }

    @Test public void receiptDuringSocketWriteCannotRescheduleAlreadyAcknowledgedResult() {
        AtomicInteger writes = new AtomicInteger(); AtomicLong now = new AtomicLong();
        OutboxAckTracker[] ref = new OutboxAckTracker[1];
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> {
            writes.incrementAndGet(); assertTrue(ref[0].acknowledge(1, "failed", 42, "delivery unconfirmed"));
        }, e -> fail(), now::get, false)) {
            ref[0] = tracker; tracker.track(1, "task", OutboxAckTrackerTest::ack); tracker.tick();
            now.set(99999); tracker.tick(); assertEquals(1, writes.get());
        }
    }

    @Test public void failedWriteStopsThisTrackerAndDoesNotResolveTheNativeResultAgain() {
        AtomicInteger failures = new AtomicInteger(), reads = new AtomicInteger(), writes = new AtomicInteger();
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> { writes.incrementAndGet(); throw new IOException("closed"); },
                e -> failures.incrementAndGet(), () -> 0, false)) {
            tracker.track(1, "task", () -> { reads.incrementAndGet(); return ack(); });
            tracker.tick(); tracker.tick();
            assertEquals(1, failures.get()); assertEquals(1, reads.get()); assertEquals(1, writes.get());
        }
    }

    @Test public void duplicateDeliverySharesReceiptAndChangedPayloadIsRejected() {
        try (OutboxAckTracker tracker = new OutboxAckTracker(a -> { }, e -> fail(), () -> 0, false)) {
            tracker.track(1, "task", OutboxAckTrackerTest::ack);
            tracker.track(1, "task", () -> { throw new AssertionError("Duplicate must keep the first observation"); });
            tracker.tick();
            try { tracker.track(1, "changed", OutboxAckTrackerTest::ack); fail(); }
            catch (IllegalStateException expected) { }
        }
    }
}
