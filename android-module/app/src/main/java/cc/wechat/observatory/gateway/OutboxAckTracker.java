package cc.wechat.observatory.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Per-connection receipt retry. It observes results and never invokes a native sender. */
public final class OutboxAckTracker implements AutoCloseable {
    public interface PendingAck { Ack ready() throws Exception; }
    public interface Sender { void send(Ack ack) throws Exception; }
    public interface Failure { void failed(Exception error); }
    interface Clock { long now(); }
    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private final Sender sender;
    private final Failure failure;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor worker;
    private final boolean automatic;
    private ScheduledFuture<?> scheduled;
    private long scheduleGeneration;
    private boolean ticking;
    private boolean closed;

    public OutboxAckTracker(Sender sender, Failure failure) {
        this(sender, failure, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), true);
    }

    OutboxAckTracker(Sender sender, Failure failure, Clock clock, boolean startWorker) {
        this.sender = sender;
        this.failure = failure;
        this.clock = clock;
        automatic = startWorker;
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "wechat-observatory-ack");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public synchronized void track(long id, String key, PendingAck result) {
        if (closed) return;
        Entry previous = pending.get(id);
        if (previous != null) {
            if (!previous.key.equals(key)) throw new IllegalStateException("Outbox task changed within one connection");
            return;
        }
        if (pending.size() >= 32) throw new IllegalStateException("Too many unacknowledged outbox results");
        pending.put(id, new Entry(id, key, result));
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
            scheduleGeneration++;
        }
        schedule(0L);
    }

    public synchronized boolean acknowledge(long id, String status, long localId, String error) {
        Entry entry = pending.get(id);
        if (closed || entry == null || entry.ack == null) return false;
        Ack ack = entry.ack;
        if (!ack.status.equals(status) || ack.localId != localId || !ack.error.equals(error == null ? "" : error)) return false;
        pending.remove(id);
        if (pending.isEmpty() && scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
            scheduleGeneration++;
        }
        return true;
    }

    void tick() { tick(-1L); }

    private void tick(long generation) {
        ArrayList<Entry> entries;
        synchronized (this) {
            if (closed || ticking || (generation >= 0 && generation != scheduleGeneration)) return;
            scheduled = null;
            ticking = true;
            entries = new ArrayList<>(pending.values());
        }
        try {
            for (Entry entry : entries) {
                try {
                    Ack ack;
                    synchronized (this) {
                        if (closed || pending.get(entry.id) != entry) continue;
                        ack = entry.ack;
                    }
                    if (ack == null) ack = entry.result.ready(); // Must return immediately while native work is pending.
                    if (ack == null) continue;
                    synchronized (this) {
                        if (closed || pending.get(entry.id) != entry) continue;
                        if (ack.id != entry.id) throw new IllegalStateException("Outbox result id mismatch");
                        entry.ack = ack;
                        if (clock.now() < entry.nextAttempt) continue;
                    }
                    sender.send(ack);
                    synchronized (this) {
                        if (closed || pending.get(entry.id) != entry) continue;
                        entry.nextAttempt = clock.now() + Math.min(8000L, 1000L << Math.min(entry.attempts, 3));
                        entry.attempts++;
                    }
                } catch (Exception error) {
                    close();
                    failure.failed(error);
                    return;
                }
            }
        } finally {
            synchronized (this) {
                ticking = false;
                if (!closed && !pending.isEmpty()) {
                    long now = clock.now();
                    long delay = Long.MAX_VALUE;
                    for (Entry entry : pending.values()) {
                        // Observe native completion only while a task exists. Once
                        // complete, wake only when that receipt is due for retry.
                        delay = Math.min(delay, entry.ack == null ? 100L : Math.max(1L, entry.nextAttempt - now));
                    }
                    schedule(delay);
                }
            }
        }
    }

    private void schedule(long delay) {
        if (automatic && !closed && !ticking && scheduled == null && !pending.isEmpty()) {
            long generation = ++scheduleGeneration;
            scheduled = worker.schedule(() -> tick(generation), delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.clear();
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
        // Do not interrupt an active socket write; its deadline owns that socket.
        worker.shutdown();
    }

    public static final class Ack {
        public final long id, localId;
        public final String status, error, payload;
        public Ack(long id, String status, long localId, String error, String payload) {
            this.id = id;
            this.status = status;
            this.localId = localId;
            this.error = error == null ? "" : error;
            this.payload = payload;
        }
    }

    private static final class Entry {
        final long id;
        final String key;
        final PendingAck result;
        Ack ack;
        int attempts;
        long nextAttempt;
        Entry(long id, String key, PendingAck result) { this.id = id; this.key = key; this.result = result; }
    }
}
