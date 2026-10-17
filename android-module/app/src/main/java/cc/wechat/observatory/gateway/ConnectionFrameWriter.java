package cc.wechat.observatory.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes complete frames for one captured socket, including ACKs and control frames. */
public final class ConnectionFrameWriter implements AutoCloseable {
    public interface Scope { boolean current(); }
    private final Socket socket;
    private final OutputStream output;
    private final Scope scope;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConnectionFrameWriter(Socket socket, OutputStream output, Scope scope) {
        this.socket = socket;
        this.output = output;
        this.scope = scope;
    }

    public synchronized void write(byte[] frame) throws IOException {
        if (closed.get() || !scope.current()) throw new IOException("Outbox connection scope changed");
        SocketWriteDeadline.run(socket, () -> { output.write(frame); output.flush(); });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
