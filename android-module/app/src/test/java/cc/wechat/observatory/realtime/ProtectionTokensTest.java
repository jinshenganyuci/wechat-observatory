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
