package cc.wechat.observatory.wechat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One process-wide native sender. Connections may stop observing, never cancel a send. */
public final class OutboxDeliveryCoordinator implements AutoCloseable {
    private final Map<String, Delivery> pending = new HashMap<>();
    private final OutboxDeliveryCache completed;
    private final ThreadPoolExecutor worker;
    private final int capacity;

    public OutboxDeliveryCoordinator(int capacity, int cacheCapacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        completed = new OutboxDeliveryCache(cacheCapacity);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "wechat-observatory-delivery");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public synchronized Delivery submit(String scope, long id, String recipient, String text,
                                        String createdAt, Callable<SendResult> send) {
        String key = OutboxDeliveryCache.taskKey(scope, id, recipient, text, createdAt);
        Delivery existing = pending.get(key);
        if (existing != null) return existing;
        SendResult cached = completed.get(scope, id, recipient, text, createdAt);
        if (cached != null) {
            Delivery delivery = new Delivery(key, new FutureTask<>(() -> cached));
            delivery.task.run();
            return delivery;
        }
        if (pending.size() >= capacity) throw new RejectedExecutionException("Outbox delivery queue is full");
        FutureTask<SendResult> task = new FutureTask<>(() -> {
            try {
                SendResult result = send.call();
                if (result == null) throw new IllegalStateException("Native sender returned no result");
                // Publish before removing the in-flight key and before anyone can ACK.
                synchronized (OutboxDeliveryCoordinator.this) {
                    completed.remember(scope, id, recipient, text, createdAt, result);
                }
                return result;
            } finally {
                synchronized (OutboxDeliveryCoordinator.this) { pending.remove(key); }
            }
        });
        Delivery delivery = new Delivery(key, task);
        pending.put(key, delivery);
        try { worker.execute(task); }
        catch (RuntimeException rejected) { pending.remove(key); throw rejected; }
        return delivery;
    }

    /** Stops accepting work; already accepted native transactions are never interrupted. */
    @Override public void close() { worker.shutdown(); }

    public synchronized boolean hasPending() { return !pending.isEmpty(); }

    public static final class Delivery {
        public final String key;
        private final FutureTask<SendResult> task;
        private Delivery(String key, FutureTask<SendResult> task) { this.key = key; this.task = task; }
        public boolean isDone() { return task.isDone(); }
        public SendResult await() throws Exception {
            return await(0L);
        }

        /** A timeout stops observing only. The native transaction and its deduplication key remain live. */
        public SendResult await(long timeoutMillis) throws Exception {
            if (timeoutMillis < 0L) throw new IllegalArgumentException("timeout must not be negative");
            try { return timeoutMillis == 0L ? task.get() : task.get(timeoutMillis, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            catch (ExecutionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException(cause);
            }
        }
    }
}
