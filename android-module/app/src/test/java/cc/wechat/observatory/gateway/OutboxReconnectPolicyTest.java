package cc.wechat.observatory.gateway;

import org.junit.Test;

import static org.junit.Assert.*;

public class OutboxReconnectPolicyTest {
    @Test
    public void repeatedFailuresBackOffToThirtySeconds() {
        OutboxReconnectPolicy policy = new OutboxReconnectPolicy();
        policy.selectScope("session-a");
        long now = 100L;
        assertTrue(policy.shouldConnect(now));
        for (long delay : new long[]{2000L, 4000L, 8000L, 16000L, 30000L, 30000L}) {
            policy.attemptingConnection();
            policy.failed(now);
            assertFalse(policy.shouldConnect(now + delay - 1));
            assertTrue(policy.shouldConnect(now + delay));
            now += delay;
        }
    }

    @Test
    public void briefSuccessfulHandshakeDoesNotResetFailureBackoff() {
        OutboxReconnectPolicy policy = new OutboxReconnectPolicy();
        policy.failed(1000L);
        policy.attemptingConnection();
        policy.connected(3000L);
        policy.failed(3100L);

        assertFalse(policy.shouldConnect(7099L));
        assertTrue(policy.shouldConnect(7100L));
    }

    @Test
    public void stableConnectionRestartsAtSmallBackoff() {
        OutboxReconnectPolicy policy = new OutboxReconnectPolicy();
        policy.failed(1000L);
        policy.failed(3000L);
        policy.connected(10000L);
        policy.failed(70000L);

        assertFalse(policy.shouldConnect(71999L));
        assertTrue(policy.shouldConnect(72000L));
    }

    @Test
    public void configOrAccountChangeResetsTransportPacing() {
        OutboxReconnectPolicy policy = new OutboxReconnectPolicy();
        policy.selectScope("session-a");
        policy.failed(10000L);
        policy.polledHttp(10000L);
        policy.selectScope("session-a");
        assertFalse(policy.shouldConnect(10001L));
        assertFalse(policy.shouldPollHttp(10001L));

        policy.selectScope("session-b");
        assertTrue(policy.shouldConnect(10001L));
        assertTrue(policy.shouldPollHttp(10001L));
    }

    @Test
    public void httpFallbackDoesNotPollAtEveryWorkerTick() {
        OutboxReconnectPolicy policy = new OutboxReconnectPolicy();
        assertTrue(policy.shouldPollHttp(1000L));
        policy.polledHttp(1000L);
        assertFalse(policy.shouldPollHttp(2000L));
        assertFalse(policy.shouldPollHttp(5999L));
        assertTrue(policy.shouldPollHttp(6000L));
    }
}
