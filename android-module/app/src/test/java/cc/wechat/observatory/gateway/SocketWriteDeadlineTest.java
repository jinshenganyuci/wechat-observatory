package cc.wechat.observatory.gateway;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SocketWriteDeadlineTest {
    @Test(timeout = 5000) public void blockedTcpWriteIsReleasedByClosingItsSocket() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket listener = new ServerSocket(0, 1, loopback);
             Socket sender = new Socket(loopback, listener.getLocalPort());
             Socket receiver = listener.accept()) {
            sender.setSendBufferSize(1024);
            receiver.setReceiveBufferSize(1024);
            OutputStream output = sender.getOutputStream();
            byte[] body = new byte[64 * 1024];
            // The peer never consumes bytes. Once TCP buffers fill, close must
            // release the blocking write; SO_TIMEOUT would not protect it.
            SocketTimeoutException timeout = assertThrows(SocketTimeoutException.class,
                    () -> SocketWriteDeadline.run(sender, () -> {
                        while (true) output.write(body);
                    }, 100));
            assertTrue(timeout.getCause() instanceof IOException);
            assertFalse(receiver.isClosed());
        }
    }

    @Test public void successfulWriteCannotBeClosedByItsCancelledTimer() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        AtomicInteger writes = new AtomicInteger();
        SocketWriteDeadline.run(socket, writes::incrementAndGet, 30);
        assertEquals(1, writes.get());
        assertFalse(socket.closed.await(150, TimeUnit.MILLISECONDS));
        assertEquals(0, socket.closes.get());
    }

    @Test public void originalIOExceptionIsPreservedAndCancelsTheTimer() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        IOException original = new IOException("write failed independently");
        IOException thrown = assertThrows(IOException.class,
                () -> SocketWriteDeadline.run(socket, () -> { throw original; }, 30));
        assertSame(original, thrown);
        assertFalse(socket.closed.await(150, TimeUnit.MILLISECONDS));
        assertEquals(0, socket.closes.get());
    }

    @Test public void timeoutClosesCapturedSocketAndNeverTheReplacementConnection() throws Exception {
        RecordingSocket oldSocket = new RecordingSocket();
        RecordingSocket replacement = new RecordingSocket();
        AtomicReference<Socket> current = new AtomicReference<>(oldSocket);
        IOException interruptedWrite = new IOException("old socket was closed during write");
        SocketTimeoutException timeout = assertThrows(SocketTimeoutException.class,
                () -> SocketWriteDeadline.run(oldSocket, () -> {
                    current.set(replacement);
                    awaitClosed(oldSocket);
                    throw interruptedWrite;
                }, 30));
        assertSame(interruptedWrite, timeout.getCause());
        assertSame(replacement, current.get());
        assertEquals(1, oldSocket.closes.get());
        assertEquals(0, replacement.closes.get());
    }

    @Test public void timeoutWinningTheCompletionRaceCannotReturnSuccess() {
        RecordingSocket socket = new RecordingSocket();
        SocketTimeoutException timeout = assertThrows(SocketTimeoutException.class,
                () -> SocketWriteDeadline.run(socket, () -> awaitClosed(socket), 30));
        // The action returned normally after the deadline had won the race.
        assertNull(timeout.getCause());
        assertEquals(1, socket.closes.get());
    }

    @Test public void invalidTimeoutDoesNotRunActionOrCloseSocket() {
        RecordingSocket socket = new RecordingSocket();
        AtomicInteger writes = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> SocketWriteDeadline.run(socket, writes::incrementAndGet, 0));
        assertEquals(0, writes.get());
        assertEquals(0, socket.closes.get());
        assertEquals(15000L, SocketWriteDeadline.DEFAULT_TIMEOUT_MS);
    }

    private static void awaitClosed(RecordingSocket socket) throws IOException {
        try {
            if (!socket.closed.await(5, TimeUnit.SECONDS)) throw new IOException("socket close did not arrive");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for close", interrupted);
        }
    }

    private static final class RecordingSocket extends Socket {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }
}
