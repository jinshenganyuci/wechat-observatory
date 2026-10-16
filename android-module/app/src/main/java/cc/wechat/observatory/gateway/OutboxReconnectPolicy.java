package cc.wechat.observatory.gateway;

/** Single-worker reconnect pacing; callers supply a monotonic clock. */
public final class OutboxReconnectPolicy {
    private static final long MAX_BACKOFF_MS = 30000L;
    private static final long STABLE_CONNECTION_MS = 60000L;
    private static final long HTTP_FALLBACK_INTERVAL_MS = 5000L;
    private String scope;
    private int failures;
    private long nextConnectAt;
    private long nextHttpAt;
    private long connectedAt = -1L;

    public void selectScope(String scope) {
        if (!String.valueOf(this.scope).equals(String.valueOf(scope))) {
            this.scope = scope;
            reset();
        }
    }

    public void reset() {
        failures = 0;
        nextConnectAt = 0L;
        nextHttpAt = 0L;
        connectedAt = -1L;
    }

    public boolean shouldConnect(long now) { return now >= nextConnectAt; }
    public void attemptingConnection() { connectedAt = -1L; }
    public void connected(long now) { connectedAt = now; }

    public void failed(long now) {
        if (connectedAt >= 0L && now - connectedAt >= STABLE_CONNECTION_MS) failures = 0;
        failures = Math.min(failures + 1, 5);
        nextConnectAt = now + Math.min(MAX_BACKOFF_MS, 2000L << (failures - 1));
        connectedAt = -1L;
    }

    public boolean shouldPollHttp(long now) { return now >= nextHttpAt; }
    public void polledHttp(long now) { nextHttpAt = now + HTTP_FALLBACK_INTERVAL_MS; }
}
