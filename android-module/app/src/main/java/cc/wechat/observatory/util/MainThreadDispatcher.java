package cc.wechat.observatory.util;

import android.os.Handler;
import android.os.Looper;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, read-only queries to a Handler's thread, normally WeChat's main thread. */
public final class MainThreadDispatcher {
    public static final long DEFAULT_TIMEOUT_MS = 5000L;
    private final Handler handler;

    public MainThreadDispatcher(Handler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public <T> T readOnly(Callable<T> query) throws Exception {
        return readOnly(query, DEFAULT_TIMEOUT_MS);
    }

    /**
     * The query must only read and return a snapshot. Publish changes separately,
     * after this method succeeds and the caller revalidates its configuration and
     * account. Never use this method to send messages or mutate shared state.
     *
     * A timed-out queued query is removed and cancelled. An already-running query
     * may finish, but cancellation discards its return value without interrupting
     * the handler thread. A query called on the handler's own thread runs directly;
     * there is no safe way to preempt that thread after a timeout.
     */
    public <T> T readOnly(Callable<T> query, long timeoutMs) throws Exception {
        Objects.requireNonNull(query, "query");
        if (timeoutMs <= 0L) throw new IllegalArgumentException("timeout must be positive");
        if (Looper.myLooper() == handler.getLooper()) return query.call();

        FutureTask<T> task = new FutureTask<>(query);
        if (!handler.post(task)) {
            task.cancel(false);
            throw new RejectedExecutionException("Main thread rejected the read-only query");
        }
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            cancel(task);
            throw timedOut;
        } catch (InterruptedException interrupted) {
            cancel(task);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Read-only main thread query failed", cause);
        }
    }

    private void cancel(FutureTask<?> task) {
        // Cancel even if a Handler implementation unexpectedly rejects removal.
        try { handler.removeCallbacks(task); }
        finally { task.cancel(false); }
    }
}
