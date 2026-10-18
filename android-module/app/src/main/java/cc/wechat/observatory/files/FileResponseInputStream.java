package cc.wechat.observatory.files;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** One HTTP response body; a proxy keeping TCP open cannot delay its completion. */
public final class FileResponseInputStream extends InputStream {
    private final InputStream input;
    private long remaining;
    public FileResponseInputStream(InputStream input, long length) {
        if (input == null || length < 0) throw new IllegalArgumentException("Invalid response body");
        this.input = input; remaining = length;
    }
    @Override public int read() throws IOException {
        if (remaining == 0) return -1;
        int value = input.read();
        if (value < 0) throw new EOFException("Truncated file gateway response");
        remaining--; return value;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int count = input.read(bytes, offset, (int)Math.min(remaining, length));
        if (count < 0) throw new EOFException("Truncated file gateway response");
        remaining -= count; return count;
    }
    @Override public void close() throws IOException { input.close(); }
}
