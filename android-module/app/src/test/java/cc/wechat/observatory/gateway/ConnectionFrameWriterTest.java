package cc.wechat.observatory.gateway;

import cc.wechat.observatory.wechat.OutboxDeliveryCoordinator;
import cc.wechat.observatory.wechat.SendResult;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ConnectionFrameWriterTest {
    @Test public void acknowledgementAndPongCannotInterleaveFrames() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CountDownLatch firstByte = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        OutputStream slow = new OutputStream() {
            public void write(int value) throws IOException {
                bytes.write(value);
                if (value == 'A') {
                    firstByte.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                    catch (InterruptedException e) { throw new IOException(e); }
                }
            }
        };
        try (ConnectionFrameWriter writer = new ConnectionFrameWriter(new Socket(), slow, () -> true)) {
            Thread ack = thread(() -> writer.write(bytes("ACK")), error);
            ack.start(); assertTrue(firstByte.await(2, TimeUnit.SECONDS));
            Thread pong = thread(() -> writer.write(bytes("PONG")), error); pong.start();
            release.countDown(); ack.join(2000); pong.join(2000);
            assertFalse(ack.isAlive()); assertFalse(pong.isAlive()); assertNull(error.get());
            assertEquals("ACKPONG", bytes.toString("UTF-8"));
        } finally { release.countDown(); }
    }

    @Test public void blockedNativeSendDoesNotBlockPongAndNewConnectionSharesItsResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ByteArrayOutputStream oldOutput = new ByteArrayOutputStream(), newOutput = new ByteArrayOutputStream();
        try (OutboxDeliveryCoordinator coordinator = new OutboxDeliveryCoordinator(2, 4);
             ConnectionFrameWriter oldWriter = new ConnectionFrameWriter(new Socket(), oldOutput, () -> true);
             ConnectionFrameWriter newWriter = new ConnectionFrameWriter(new Socket(), newOutput, () -> true)) {
            OutboxDeliveryCoordinator.Delivery delivery = coordinator.submit("scope", 1, "recipient", "text", "created", () -> {
                entered.countDown(); release.await(); return SendResult.sent(42);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try (OutboxAckTracker oldTracker = new OutboxAckTracker(a -> oldWriter.write(bytes(a.payload)), e -> fail(), () -> 0, false)) {
                oldTracker.track(1, delivery.key, () -> delivery.isDone() ? ack(delivery.await()) : null);
                oldTracker.tick();
                oldWriter.write(bytes("PONG"));
                assertEquals("PONG", oldOutput.toString("UTF-8"));
                oldTracker.close(); oldWriter.close();
                OutboxDeliveryCoordinator.Delivery replay = coordinator.submit("scope", 1, "recipient", "text", "created", () -> {
                    throw new AssertionError("Reconnect must not send natively again");
                });
                assertSame(delivery, replay);
                release.countDown(); assertTrue(replay.await().ok);
                oldTracker.tick(); assertEquals("PONG", oldOutput.toString("UTF-8"));
                try (OutboxAckTracker newTracker = new OutboxAckTracker(a -> newWriter.write(bytes(a.payload)), e -> fail(), () -> 0, false)) {
                    newTracker.track(1, replay.key, () -> ack(replay.await())); newTracker.tick();
                    assertEquals("ACK42", newOutput.toString("UTF-8"));
                }
            }
        } finally { release.countDown(); }
    }

    @Test public void oldScopeOrClosedWriterCannotTouchReplacementSocket() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        RecordingSocket oldSocket = new RecordingSocket(), replacement = new RecordingSocket();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ConnectionFrameWriter oldWriter = new ConnectionFrameWriter(oldSocket, output, current::get)) {
            current.set(false);
            try { oldWriter.write(bytes("ACK")); fail(); } catch (IOException expected) { }
            oldWriter.close();
            try { oldWriter.write(bytes("LATE")); fail(); } catch (IOException expected) { }
            assertEquals(0, output.size()); assertTrue(oldSocket.closed); assertFalse(replacement.closed);
        }
    }

    private static OutboxAckTracker.Ack ack(SendResult result) {
        return new OutboxAckTracker.Ack(1, "sent", result.chatRecordId, "", "ACK" + result.chatRecordId);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private interface Operation { void run() throws Exception; }
    private static Thread thread(Operation action, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable t) { error.set(t); } });
        thread.setDaemon(true); return thread;
    }
    private static class RecordingSocket extends Socket {
        boolean closed;
        @Override public void close() { closed = true; }
    }
}
