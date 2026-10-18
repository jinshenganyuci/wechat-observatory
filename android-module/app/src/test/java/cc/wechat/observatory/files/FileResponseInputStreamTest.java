package cc.wechat.observatory.files;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class FileResponseInputStreamTest {
    @Test public void exactBodyDoesNotWaitForKeepAliveConnectionToClose() throws Exception {
        InputStream openSocket = new InputStream() {
            int reads;
            @Override public int read() { if (reads++ == 0) return 65; throw new AssertionError("waited beyond Content-Length"); }
        };
        FileResponseInputStream body = new FileResponseInputStream(openSocket, 1);
        assertEquals(65, body.read()); assertEquals(-1, body.read());
        assertEquals(-1, body.read(new byte[8]));
    }
    @Test public void bulkReadCannotConsumeBytesBeyondBody() throws Exception {
        ByteArrayInputStream raw = new ByteArrayInputStream(new byte[]{1, 2, 3, 4});
        FileResponseInputStream body = new FileResponseInputStream(raw, 2); byte[] buffer = new byte[16];
        assertEquals(2, body.read(buffer)); assertEquals(-1, body.read(buffer)); assertEquals(3, raw.read());
    }
    @Test public void shortBodyIsAnErrorForSingleAndBulkReads() throws Exception {
        FileResponseInputStream single = new FileResponseInputStream(new ByteArrayInputStream(new byte[]{1}), 2);
        assertEquals(1, single.read()); assertThrows(EOFException.class, single::read);
        FileResponseInputStream bulk = new FileResponseInputStream(new ByteArrayInputStream(new byte[]{1}), 2);
        byte[] bytes = new byte[8]; assertEquals(1, bulk.read(bytes));
        assertThrows(EOFException.class, () -> bulk.read(bytes));
    }
    @Test public void zeroLengthReadAndEmptyBodyRespectInputStreamContract() throws Exception {
        FileResponseInputStream body = new FileResponseInputStream(new ByteArrayInputStream(new byte[0]), 0);
        assertEquals(0, body.read(new byte[1], 0, 0)); assertEquals(-1, body.read());
        assertThrows(IndexOutOfBoundsException.class, () -> body.read(new byte[1], 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileResponseInputStream(new ByteArrayInputStream(new byte[0]), -1));
    }
}
