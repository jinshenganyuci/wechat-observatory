package cc.wechat.observatory.realtime;

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ProtectionTokensTest {
    private static final int UID = 99910355;
    private static final int PID = 19893;
    private final ProtectionTokens tokens = new ProtectionTokens();
    private final FakeAms ams = new FakeAms();
    private final FakeBinder binder = new FakeBinder();
    private ReceiverRegistrationLookup lookup;
    private long epoch;

    @Before public void setup() throws Exception {
        lookup = new ReceiverRegistrationLookup(FakeAms.class, FakeController.class,
                FakeReceiverList.class, FakeProcess.class, FakeFilter.class);
        tokens.setPolicyAvailable(true);
        epoch = tokens.epoch();
        assertTrue(tokens.allowModule(epoch, true));
        addActualRegistration(binder, UID);
    }

    @Test public void onlyActualPrivateWeChatRegistrationProtectsItsCompleteUid() {
        assertTrue(register(binder));
        assertTrue(tokens.containsUid(UID));
        assertFalse("same app in user 0 must not inherit the clone's protection", tokens.containsUid(10355));
        assertFalse(tokens.containsUid(1000));
        assertTrue(register(binder));
        assertEquals("duplicate registration must not add another death recipient", 1, binder.links);
        tokens.remove(binder);
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void returnWithoutRegisteredReceiverDoesNotGrantProtection() {
        ams.mBroadcastController.mRegisteredReceivers.clear();
        assertFalse(register(binder));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void requestCannotImpersonateTheUidOrPackageInTheActualRegistry() {
        assertFalse(tokens.register(ams, () -> lookup.find(ams, binder), 10355, PID, epoch));
        assertFalse(tokens.register(ams, () -> lookup.find(ams, binder), UID, PID + 1, epoch));
        actual(binder).app.info.packageName = "another.package";
        assertFalse(register(binder));
        actual(binder).app.info.packageName = ProtectionTokens.WECHAT;
        actual(binder).app.info.uid = 10355;
        assertFalse(register(binder));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void exportedOrMissingMarkerIsRejectedEvenIfRequestClaimedOtherwise() {
        actual(binder).get(0).exported = true;
        assertFalse(register(binder));
        actual(binder).clear();
        actual(binder).add(new FakeFilter("some.other.action", false));
        assertFalse(register(binder));
        actual(binder).clear();
        assertFalse(register(binder));
    }

    @Test public void wrongBinderInActualRowIsRejected() {
        actual(binder).receiver = new FakeBinder();
        assertFalse(register(binder));
    }

    @Test public void unregisterBeforeLateAfterHookCannotRecreateProtection() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<Boolean> registered;
            synchronized (ams) {
                registered = worker.submit(() -> { started.countDown(); return register(binder); });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                ams.mBroadcastController.mRegisteredReceivers.remove(binder);
                tokens.remove(binder);
            }
            assertFalse(registered.get(2, TimeUnit.SECONDS));
            assertFalse(tokens.containsUid(UID));
        } finally { worker.shutdownNow(); }
    }

    @Test public void deathAndUnregisterOnlyRemoveTheirOwnToken() {
        FakeBinder second = new FakeBinder();
        addActualRegistration(second, UID);
        assertTrue(register(binder));
        assertTrue(register(second));
        binder.die();
        assertTrue(tokens.containsUid(UID));
        tokens.remove(second);
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void deathDuringLinkCannotLeaveAnEntryWithoutADeathRecipient() {
        binder.dieOnLink = true;
        assertFalse(register(binder));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void failedDeathLinkCannotGrantProtection() {
        binder.failLink = true;
        assertFalse(register(binder));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void aLateOldDeathCallbackCannotEraseAReplacementEntry() {
        assertTrue(register(binder));
        IBinder.DeathRecipient old = binder.recipients.get(0);
        tokens.remove(binder);
        assertTrue(register(binder));
        old.binderDied();
        assertTrue(tokens.containsUid(UID));
    }

    @Test public void moduleRemovalRevokesAndInvalidatesInFlightRegistrationQueries() {
        assertTrue(register(binder));
        tokens.revokeModule();
        assertFalse(tokens.containsUid(UID));
        assertFalse("stale package-manager result", tokens.allowModule(epoch, true));
        assertFalse(register(binder));
        assertFalse(tokens.allowModule(tokens.epoch(), false));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void packageAvailableAgainDoesNotResurrectOldTokens() {
        assertTrue(register(binder));
        tokens.revokeModule();
        epoch = tokens.epoch();
        assertTrue(tokens.allowModule(epoch, true));
        assertFalse(tokens.containsUid(UID));
        assertTrue(register(binder));
    }

    @Test public void unavailablePolicyRevokesTokensAndRequiresFreshRegistrationWhenRestored() {
        assertTrue(register(binder));
        tokens.setPolicyAvailable(false);
        assertFalse(tokens.containsUid(UID));
        epoch = tokens.epoch();
        assertTrue(tokens.allowModule(epoch, true));
        assertFalse(register(binder));
        tokens.setPolicyAvailable(true);
        assertFalse(tokens.containsUid(UID));
        assertTrue(register(binder));
    }

    @Test public void incompatibleFrameworkLayoutIsRejectedBeforeInstallingHooks() {
        assertThrows(ReflectiveOperationException.class, () -> new ReceiverRegistrationLookup(
                FakeAms.class, Object.class, FakeReceiverList.class, FakeProcess.class, FakeFilter.class));
    }

    @Test public void queryNeedsActualPrivateSystemBatteryRegistrationAndMatchingPid() {
        assertTrue(register(binder));
        FakeBinder query = addQuery();
        String nonce = actual(query).get(0).getCategory(0);
        assertEquals(Boolean.TRUE, query(query, nonce, 1000, PID, () -> true));
        assertNull(query(query, nonce, 101000, PID, () -> true));
        assertNull(query(query, nonce, 1000, PID + 1, () -> true));
        actual(query).app.info.packageName = "another.system.app";
        assertNull(query(query, nonce, 1000, PID, () -> true));
        actual(query).app.info.packageName = RealtimePolicyQuery.BATTERY;
        actual(query).app.info.uid = 101000;
        assertNull(query(query, nonce, 1000, PID, () -> true));
        assertTrue(tokens.containsUid(UID));
        assertEquals("queries never create protection/death registrations", 0, query.links);
    }

    @Test public void queryRejectsExportedWrongNonceExtraActionAndWrongActualBinder() {
        assertTrue(register(binder));
        FakeBinder query = addQuery();
        FakeFilter filter = actual(query).get(0);
        String nonce = filter.getCategory(0);
        assertNull(query(query, nonce + "a", 1000, PID, () -> true));
        filter.exported = true;
        assertNull(query(query, nonce, 1000, PID, () -> true));
        filter.exported = false;
        filter.addAction("another.action");
        assertNull(query(query, nonce, 1000, PID, () -> true));
        actual(query).clear();
        actual(query).add(new FakeFilter(RealtimePolicyQuery.ACTION, false));
        actual(query).get(0).addCategory(nonce);
        actual(query).receiver = new FakeBinder();
        assertNull(query(query, nonce, 1000, PID, () -> true));
        assertTrue(tokens.containsUid(UID));
    }

    @Test public void queryThermalReaderRunsOutsideAmsAndTokenLocksAndFailsClosed() {
        assertTrue(register(binder));
        FakeBinder query = addQuery();
        String nonce = actual(query).get(0).getCategory(0);
        AtomicBoolean observedUnlocked = new AtomicBoolean();
        assertEquals(Boolean.TRUE, query(query, nonce, 1000, PID, () -> {
            observedUnlocked.set(!Thread.holdsLock(ams) && !Thread.holdsLock(tokens));
            return true;
        }));
        assertTrue(observedUnlocked.get());
        assertEquals(Boolean.FALSE, query(query, nonce, 1000, PID, null));
        assertEquals(Boolean.FALSE, query(query, nonce, 1000, PID, () -> { throw new IllegalStateException(); }));
        assertTrue("a query failure never revokes existing protection", tokens.containsUid(UID));
        synchronized (ams) {
            assertNull(query(query, nonce, 1000, PID, () -> { fail("must not run under AMS"); return true; }));
        }
    }

    @Test public void queryRechecksRevocationAndItsOwnUnregisterAfterThermalRead() {
        assertTrue(register(binder));
        FakeBinder query = addQuery();
        String nonce = actual(query).get(0).getCategory(0);
        assertNull(query(query, nonce, 1000, PID, () -> {
            synchronized (ams) { ams.mBroadcastController.mRegisteredReceivers.remove(query); }
            return true;
        }));
        assertTrue(tokens.containsUid(UID));
        FakeBinder secondQuery = addQuery();
        String secondNonce = actual(secondQuery).get(0).getCategory(0);
        assertEquals(Boolean.FALSE, query(secondQuery, secondNonce, 1000, PID, () -> {
            tokens.revokeModule();
            return true;
        }));
        assertFalse(tokens.containsUid(UID));
    }

    @Test public void queryRequiresALiveProtectionTokenAndNeverCachesTheResult() {
        FakeBinder query = addQuery();
        String nonce = actual(query).get(0).getCategory(0);
        AtomicBoolean thermalRead = new AtomicBoolean();
        assertEquals(Boolean.FALSE, query(query, nonce, 1000, PID,
                () -> { thermalRead.set(true); return true; }));
        assertFalse("no thermal read is needed without a protected process", thermalRead.get());
        assertTrue(register(binder));
        assertEquals(Boolean.TRUE, query(query, nonce, 1000, PID, () -> true));
        binder.alive = false; // Death delivery may lag the underlying binder becoming dead.
        assertEquals(Boolean.FALSE, query(query, nonce, 1000, PID, () -> true));
        assertEquals(0, query.links);
    }

    @Test public void bothActualRegistryReadsHoldAmsAndPolicyWithdrawalIsRechecked() {
        assertTrue(register(binder));
        FakeBinder query = addQuery();
        String nonce = actual(query).get(0).getCategory(0);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(Boolean.FALSE, tokens.queryPolicy(ams, () -> {
            assertTrue(Thread.holdsLock(ams));
            reads.incrementAndGet();
            return lookup.findQuery(ams, query, nonce);
        }, 1000, PID, () -> {
            tokens.setPolicyAvailable(false);
            return true;
        }));
        assertEquals(2, reads.get());
    }

    private Boolean query(FakeBinder receiver, String nonce, int uid, int pid,
                          java.util.function.BooleanSupplier decision) {
        return tokens.queryPolicy(ams, () -> lookup.findQuery(ams, receiver, nonce), uid, pid, decision);
    }

    private FakeBinder addQuery() {
        FakeBinder query = new FakeBinder();
        addActualRegistration(query, 1000);
        FakeReceiverList row = actual(query);
        row.app.info.packageName = RealtimePolicyQuery.BATTERY;
        row.clear();
        FakeFilter filter = new FakeFilter(RealtimePolicyQuery.ACTION, false);
        filter.addCategory(RealtimePolicyQuery.ACTION + ".nonce." + UUID.randomUUID());
        row.add(filter);
        return query;
    }

    private boolean register(FakeBinder token) {
        return tokens.register(ams, () -> lookup.find(ams, token), UID, PID, epoch);
    }

    private FakeReceiverList actual(FakeBinder token) {
        return ams.mBroadcastController.mRegisteredReceivers.get(token);
    }

    private void addActualRegistration(FakeBinder token, int uid) {
        FakeReceiverList row = new FakeReceiverList();
        row.uid = uid;
        row.pid = PID;
        row.app = new FakeProcess();
        row.app.info = new ApplicationInfo();
        row.app.info.uid = uid;
        row.app.info.packageName = ProtectionTokens.WECHAT;
        row.receiver = token;
        row.add(new FakeFilter(RealtimeProtectionClient.ACTION, false));
        ams.mBroadcastController.mRegisteredReceivers.put(token, row);
    }

    static final class FakeAms {
        final FakeController mBroadcastController = new FakeController();
        Context mContext;
    }
    static final class FakeController {
        final Map<IBinder, FakeReceiverList> mRegisteredReceivers = new HashMap<>();
    }
    static final class FakeProcess { ApplicationInfo info; }
    static final class FakeReceiverList extends ArrayList<FakeFilter> {
        int uid, pid;
        FakeProcess app;
        IInterface receiver;
    }
    static final class FakeFilter extends IntentFilter {
        boolean exported;
        FakeFilter(String action, boolean exported) { super(action); this.exported = exported; }
    }
    static final class FakeBinder extends Binder implements IInterface {
        final ArrayList<IBinder.DeathRecipient> recipients = new ArrayList<>();
        boolean alive = true, dieOnLink, failLink;
        int links;
        @Override public IBinder asBinder() { return this; }
        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            links++;
            if (failLink) throw new IllegalStateException("link unavailable");
            recipients.add(recipient);
            if (dieOnLink) die();
        }
        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            return recipients.remove(recipient);
        }
        void die() {
            alive = false;
            for (IBinder.DeathRecipient recipient : new ArrayList<>(recipients)) recipient.binderDied();
        }
    }
}
