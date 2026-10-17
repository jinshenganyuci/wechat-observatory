package cc.wechat.observatory.wechat;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AccountMessageStatusReaderTest {
    private final RuntimeAccount account = new RuntimeAccount("wxid_a", "A", "/data/user/0/com.tencent.mm/MicroMsg/account-a/EnMicroMsg.db");

    @Test public void confirmationUsesOnlyTheCapturedDatabaseWithoutResolvingTheKernelAgain() throws Exception {
        Object captured = new Object(); AtomicInteger reads = new AtomicInteger();
        AccountMessageStatusReader reader = new AccountMessageStatusReader(account, captured, () -> true,
                new AccountMessageStatusReader.Database() {
                    public String path(Object db) { assertSame(captured, db); return account.mainDatabasePath; }
                    public int status(Object db, long id) { assertSame(captured, db); assertEquals(42, id); return reads.incrementAndGet() == 1 ? 1 : 2; }
                });
        assertEquals(1, reader.read(42)); assertEquals(2, reader.read(42)); assertEquals(2, reads.get());
    }

    @Test public void anotherAccountsDatabaseIsRejectedBeforeQuery() {
        try {
            new AccountMessageStatusReader(account, new Object(), () -> true, database("/data/user/0/com.tencent.mm/MicroMsg/account-b/EnMicroMsg.db"));
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void accountSwitchBeforeOrDuringQueryNeverPublishesSent() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicInteger reads = new AtomicInteger();
        AccountMessageStatusReader reader = new AccountMessageStatusReader(account, new Object(), current::get,
                new AccountMessageStatusReader.Database() {
                    public String path(Object db) { return account.mainDatabasePath; }
                    public int status(Object db, long id) { reads.incrementAndGet(); current.set(false); return 2; }
                });
        assertEquals(LocalMessageConfirmation.STATUS_UNKNOWN, reader.read(42));
        assertEquals(LocalMessageConfirmation.STATUS_UNKNOWN, reader.read(42)); assertEquals(1, reads.get());
    }

    @Test public void missingDatabaseIsUnknownWithoutSearchingOtherAccounts() throws Exception {
        AccountMessageStatusReader reader = new AccountMessageStatusReader(account, null, () -> true, database("unused"));
        assertEquals(LocalMessageConfirmation.STATUS_UNKNOWN, reader.read(42));
    }

    private static AccountMessageStatusReader.Database database(String path) {
        return new AccountMessageStatusReader.Database() {
            public String path(Object db) { return path; }
            public int status(Object db, long id) { throw new AssertionError("Must not query this database"); }
        };
    }
}
