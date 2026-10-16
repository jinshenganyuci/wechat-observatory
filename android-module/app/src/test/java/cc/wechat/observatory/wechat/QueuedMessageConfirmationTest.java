package cc.wechat.observatory.wechat;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class QueuedMessageConfirmationTest {
    @Test
    public void acceptedQueueFollowedByFailureDoesNotAcknowledgeSent() throws Exception {
        SendResult result = submitAndConfirm(1, 5);
        assertFalse(result.ok);
        assertEquals(42L, result.chatRecordId);
        assertTrue(result.error.contains("marked failed"));
    }

    @Test
    public void acceptedQueueStillSendingIsUnconfirmedAndRetainsItsLocalId() throws Exception {
        SendResult result = submitAndConfirm(1, 1, 1);
        assertFalse(result.ok);
        assertEquals(42L, result.chatRecordId);
        assertTrue(result.error.contains("queued but delivery unconfirmed"));
        assertTrue(result.error.contains("message was not resubmitted"));
    }

    @Test
    public void acceptedQueueIsSentOnlyAfterLocalStatusConfirmsIt() throws Exception {
        SendResult result = submitAndConfirm(1, 2);
        assertTrue(result.ok);
        assertEquals(42L, result.chatRecordId);
        assertEquals("", result.error);
    }

    @Test
    public void unavailableDatabaseAfterQueueingKeepsTheLocalIdWithoutResending() {
        SendResult result = QueuedMessageConfirmation.await(42L,
                () -> { throw new IOException("account database closed"); }, delay -> { }, 3, 0L);
        assertFalse(result.ok);
        assertEquals(42L, result.chatRecordId);
        assertTrue(result.error.contains("queued but delivery unconfirmed"));
    }

    private static SendResult submitAndConfirm(int... statuses) throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        boolean accepted = QueueSubmissionRetrier.awaitAccepted(() -> {
            submissions.incrementAndGet();
            return true;
        }, delay -> { }, 3, 0L);
        assertTrue(accepted);
        AtomicInteger reads = new AtomicInteger();
        SendResult result = QueuedMessageConfirmation.await(42L,
                () -> statuses[reads.getAndIncrement()], delay -> { }, statuses.length, 0L);
        assertEquals(statuses.length, reads.get());
        assertEquals("Confirmation must not submit the request again", 1, submissions.get());
        return result;
    }
}
