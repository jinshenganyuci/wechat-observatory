package cc.wechat.observatory.wechat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountSessionAllocatorTest {
    private static final String INSTANCE = "5ab46c33-0f96-4c49-9be7-549953a709f9";

    @Test public void accountRoundTripRetainsInstallationButChangesSessionAndGeneration() {
        AccountSessionAllocator allocator = new AccountSessionAllocator(INSTANCE, 101, 200);
        AccountSession firstA = allocator.allocate();
        AccountSession b = allocator.allocate();
        AccountSession nextA = allocator.allocate();
        assertEquals(INSTANCE, firstA.instanceId);
        assertEquals(INSTANCE, b.instanceId);
        assertEquals(INSTANCE, nextA.instanceId);
        assertEquals(101, firstA.generation);
        assertEquals(102, b.generation);
        assertEquals(103, nextA.generation);
        assertNotEquals(firstA.id, nextA.id);
        assertFalse(nextA.acceptsEcho(firstA.id, firstA.generation));
    }

    @Test public void restartedProcessAlwaysOutranksEveryGenerationReservedToOldProcess() {
        AccountSessionAllocator.Range oldRange = AccountSessionAllocator.reserveAfter(0, 1000000);
        // Only the module app persists this high watermark, before handing out the range.
        long persistedHighWatermark = oldRange.lastInclusive;
        AccountSessionAllocator.Range newRange = AccountSessionAllocator.reserveAfter(persistedHighWatermark, 1000000);
        AccountSessionAllocator restarted = new AccountSessionAllocator(INSTANCE, newRange.firstInclusive, newRange.lastInclusive);
        AccountSessionAllocator delayedOldProcess = new AccountSessionAllocator(INSTANCE, oldRange.lastInclusive, oldRange.lastInclusive);
        AccountSession current = restarted.allocate();
        AccountSession delayed = delayedOldProcess.allocate();
        assertEquals(1, oldRange.firstInclusive);
        assertEquals(1000000, oldRange.lastInclusive);
        assertEquals(1000001, current.generation);
        assertTrue(current.generation > delayed.generation);
        assertEquals(delayed.instanceId, current.instanceId);
    }

    @Test public void unusedRangesMayBeSkippedAfterProcessDiesBeforeReceipt() {
        AccountSessionAllocator.Range lostReply = AccountSessionAllocator.reserveAfter(4000, 1000);
        AccountSessionAllocator.Range retriedLoad = AccountSessionAllocator.reserveAfter(lostReply.lastInclusive, 1000);
        assertEquals(5001, retriedLoad.firstInclusive);
        assertEquals(6000, retriedLoad.lastInclusive);
    }

    @Test public void lastLongGenerationIsAllocatedOnceAndNeverWraps() {
        AccountSessionAllocator.Range range = AccountSessionAllocator.reserveAfter(Long.MAX_VALUE - 1, 1);
        AccountSessionAllocator allocator = new AccountSessionAllocator(INSTANCE, range.firstInclusive, range.lastInclusive);
        assertEquals(Long.MAX_VALUE, allocator.allocate().generation);
        assertThrows(IllegalStateException.class, allocator::allocate);
        assertThrows(IllegalStateException.class, allocator::allocate);
        assertThrows(IllegalStateException.class, () -> AccountSessionAllocator.reserveAfter(range.lastInclusive, 1));
    }

    @Test public void exhaustedRangeRequiresFreshReservation() {
        AccountSessionAllocator allocator = new AccountSessionAllocator(INSTANCE, 9, 10);
        assertEquals(9, allocator.allocate().generation);
        assertEquals(10, allocator.allocate().generation);
        assertThrows(IllegalStateException.class, allocator::allocate);
    }

    @Test public void invalidOrOverflowingReservationsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> AccountSessionAllocator.reserveAfter(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> AccountSessionAllocator.reserveAfter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> AccountSessionAllocator.reserveAfter(0, -1));
        assertThrows(IllegalStateException.class, () -> AccountSessionAllocator.reserveAfter(Long.MAX_VALUE - 5, 6));
        assertThrows(IllegalArgumentException.class, () -> new AccountSessionAllocator(INSTANCE, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AccountSessionAllocator(INSTANCE, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new AccountSessionAllocator(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AccountSessionAllocator("invalid instance id", 1, 1));
    }

    @Test public void concurrentAccountRefreshesNeverReuseGenerations() throws Exception {
        AccountSessionAllocator allocator = new AccountSessionAllocator(INSTANCE, 1, 400);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<AccountSession>> requests = new ArrayList<>();
            for (int i = 0; i < 400; i++) requests.add(allocator::allocate);
            List<Future<AccountSession>> results = workers.invokeAll(requests);
            Set<Long> generations = new HashSet<>();
            Set<String> sessions = new HashSet<>();
            for (Future<AccountSession> result : results) {
                AccountSession session = result.get();
                assertEquals(INSTANCE, session.instanceId);
                assertTrue(generations.add(session.generation));
                assertTrue(sessions.add(session.id));
            }
            assertEquals(400, generations.size());
            assertTrue(generations.contains(1L));
            assertTrue(generations.contains(400L));
            assertThrows(IllegalStateException.class, allocator::allocate);
        } finally {
            workers.shutdownNow();
        }
    }
}
