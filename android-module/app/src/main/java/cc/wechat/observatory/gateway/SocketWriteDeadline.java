package cc.wechat.observatory.gateway;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounds an output operation by closing only the socket captured for that operation. */
public final class SocketWriteDeadline {
    public static final long DEFAULT_TIMEOUT_MS = 15000L;
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    @FunctionalInterface
    public interface IOAction { void run() throws IOException; }

    private SocketWriteDeadline() { }

    public static void run(Socket socket, IOAction action) throws IOException {
        run(socket, action, DEFAULT_TIMEOUT_MS);
    }

    public static void run(Socket socket, IOAction action, long timeoutMs) throws IOException {
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(action, "action");
        if (timeoutMs <= 0L) throw new IllegalArgumentException("timeout must be positive");
        Deadline deadline = new Deadline(socket);
        ScheduledFuture<?> alarm = TIMER.schedule(deadline, timeoutMs, TimeUnit.MILLISECONDS);
        IOException failure = null;
        try {
            action.run();
        } catch (IOException error) {
            failure = error;
        } finally {
            // Cancel alone is insufficient if the timer has already dequeued.
            // CAS makes completed success and a later socket close exclusive.
            deadline.complete();
            alarm.cancel(false);
        }
        if (deadline.timedOut()) {
            SocketTimeoutException timeout = new SocketTimeoutException("Socket write deadline exceeded");
            if (failure != null) timeout.initCause(failure);
            throw timeout;
        }
        if (failure != null) throw failure;
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "wechat-observatory-socket-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }

    private static final class Deadline implements Runnable {
        private static final int RUNNING = 0;
        private static final int COMPLETED = 1;
        private static final int TIMED_OUT = 2;
        private final AtomicInteger state = new AtomicInteger(RUNNING);
        private final Socket socket;

        Deadline(Socket socket) { this.socket = socket; }

        @Override public void run() {
            if (!state.compareAndSet(RUNNING, TIMED_OUT)) return;
            try { socket.close(); }
            catch (IOException ignored) {
                // The writer still observes timeout, never a successful operation.
            }
        }

        void complete() { state.compareAndSet(RUNNING, COMPLETED); }
        boolean timedOut() { return state.get() == TIMED_OUT; }
    }
}
