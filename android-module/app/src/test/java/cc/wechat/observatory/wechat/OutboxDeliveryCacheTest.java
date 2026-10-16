package cc.wechat.observatory.wechat;

import org.junit.Test;

import static org.junit.Assert.*;

public class OutboxDeliveryCacheTest {
    private static String scope(String installation, String session) {
        return OutboxDeliveryCache.scope("https://example.test", "test-key", "phone", "owner",
                installation, session);
    }

    @Test
    public void replayAfterLostAckReturnsSameSendResult() {
        OutboxDeliveryCache cache = new OutboxDeliveryCache(2);
        String scope = scope("installation-a", "session-a");
        SendResult result = SendResult.sent(1234L);
        assertNull(cache.get(scope, 1L, "recipient", "hello", "time-a"));
        cache.remember(scope, 1L, "recipient", "hello", "time-a", result);

        assertSame(result, cache.get(scope, 1L, "recipient", "hello", "time-a"));
        assertEquals(1234L, cache.get(scope, 1L, "recipient", "hello", "time-a").chatRecordId);
    }

    @Test
    public void taskIdReuseDoesNotReuseAnotherContentOrAccountResult() {
        OutboxDeliveryCache cache = new OutboxDeliveryCache(2);
        String scope = scope("installation-a", "session-a");
        cache.remember(scope, 1L, "recipient", "hello", "time-a", SendResult.sent(1234L));

        assertNull(cache.get(scope, 2L, "recipient", "hello", "time-a"));
        assertNull(cache.get(scope, 1L, "other", "hello", "time-a"));
        assertNull(cache.get(scope, 1L, "recipient", "changed", "time-a"));
        assertNull(cache.get(scope, 1L, "recipient", "hello", "time-b"));
        assertNull(cache.get(scope("installation-b", "session-a"), 1L, "recipient", "hello", "time-a"));
        assertNull(cache.get(scope("installation-a", "session-b"), 1L, "recipient", "hello", "time-a"));
        assertNotEquals(scope, OutboxDeliveryCache.scope("https://other.test", "test-key", "phone", "owner",
                "installation-a", "session-a"));
        assertNotEquals(scope, OutboxDeliveryCache.scope("https://example.test", "changed-key", "phone", "owner",
                "installation-a", "session-a"));
        assertNotEquals(scope, OutboxDeliveryCache.scope("https://example.test", "test-key", "phone", "other-owner",
                "installation-a", "session-a"));
    }

    @Test
    public void failedResultAlsoReplaysWithoutCreatingAnotherLocalMessage() {
        OutboxDeliveryCache cache = new OutboxDeliveryCache(2);
        SendResult result = SendResult.failed(2345L, "confirmation timed out");
        cache.remember("scope", 1L, "recipient", "hello", "time-a", result);
        assertSame(result, cache.get("scope", 1L, "recipient", "hello", "time-a"));
    }

    @Test
    public void boundedCacheEvictsLeastRecentlyUsedResults() {
        OutboxDeliveryCache cache = new OutboxDeliveryCache(2);
        cache.remember("scope", 1L, "recipient", "one", "time", SendResult.sent(1L));
        cache.remember("scope", 2L, "recipient", "two", "time", SendResult.sent(2L));
        assertNotNull(cache.get("scope", 1L, "recipient", "one", "time"));
        cache.remember("scope", 3L, "recipient", "three", "time", SendResult.sent(3L));

        assertNull(cache.get("scope", 2L, "recipient", "two", "time"));
        assertNotNull(cache.get("scope", 1L, "recipient", "one", "time"));
        assertNotNull(cache.get("scope", 3L, "recipient", "three", "time"));
    }
}
