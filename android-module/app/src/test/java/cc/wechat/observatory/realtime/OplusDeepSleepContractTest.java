package cc.wechat.observatory.realtime;

import android.content.Context;
import android.os.Message;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Pure reflection tests; no phone, OEM settings writes, or simulated restoration. */
public class OplusDeepSleepContractTest {
    private OplusDeepSleepContract contract;
    private FakeController controller;

    @Before public void setup() throws Exception {
        contract = new OplusDeepSleepContract(FakeController.class, FakeHandler.class);
        controller = new FakeController();
    }

    @Test public void resolvesPrivateEntrypointsAndRealOwnerLockIdentity() throws Exception {
        assertEquals(boolean.class, contract.eligibility.getReturnType());
        assertEquals(void.class, contract.dispatch.getReturnType());
        assertArrayEquals(new Class<?>[] {Message.class}, contract.dispatch.getParameterTypes());
        assertSame(controller, contract.owner(controller.mWorkHandler));
        assertSame(controller.mLock, contract.lock(controller));
    }

    @Test public void cleanSensingAcceptsOnlyNeverDisconnectedOrNetworkUnchangedCycle() throws Exception {
        assertTrue(contract.cleanSensing(controller));
        controller.mUtils.disconnected = 6;
        assertTrue(contract.cleanSensing(controller));
        for (int value : new int[] {-1, 1, 2, 3, 4, 5, 7, Integer.MAX_VALUE}) {
            controller.mUtils.disconnected = value;
            assertFalse("disconnection state " + value, contract.cleanSensing(controller));
        }
    }

    @Test public void onlySensingCanStartANewProtectedDecision() throws Exception {
        for (int value : new int[] {-1, 0, 1, 3, 4, 5, 6}) {
            controller.mStatus = value;
            assertFalse("status " + value, contract.cleanSensing(controller));
        }
    }

    @Test public void existingPidFilterCannotBeMistakenForACleanCycle() throws Exception {
        controller.mIsPidDisNet = true;
        assertFalse(contract.cleanSensing(controller));
        assertTrue(controller.mIsPidDisNet);
    }

    @Test public void abnormalNetworkOrPendingMobileRestorationRejectsACleanCycle() throws Exception {
        controller.mIsNetAbnormalActive = true;
        assertFalse(contract.cleanSensing(controller));
        controller.mIsNetAbnormalActive = false;
        controller.mIsNeedRestoreMobile = true;
        assertFalse(contract.cleanSensing(controller));
        assertTrue(controller.mIsNeedRestoreMobile);
    }

    @Test public void airplaneRusAndUnfinishedAirplaneRestorationBothReject() throws Exception {
        controller.mConfigUpdateUtil.airplane = true;
        assertFalse(contract.cleanSensing(controller));
        controller.mConfigUpdateUtil.airplane = false;
        controller.mUtils.airplaneReset = true;
        assertFalse(contract.cleanSensing(controller));
        assertTrue(controller.mUtils.airplaneReset);
    }

    @Test public void queuedAirplaneTransitionRejectsEvenAfterRusHasTurnedOff() throws Exception {
        controller.mWorkHandler.pending.add(17);
        assertFalse(contract.cleanSensing(controller));
        assertEquals(17, controller.mWorkHandler.lastCheckedMessage);
        assertTrue("the check must not cancel a pending restoration", controller.mWorkHandler.pending.contains(17));
    }

    @Test public void unrelatedQueuedMessageDoesNotRejectCleanSensing() throws Exception {
        controller.mWorkHandler.pending.add(31);
        assertTrue(contract.cleanSensing(controller));
        assertTrue(controller.mWorkHandler.pending.contains(31));
    }

    @Test public void eligibilityCheckDoesNotRewriteOriginalNetworkState() throws Exception {
        controller.mNetWorkSwitchState = 2;
        controller.mNetWorkType = 2;
        controller.mDisableNetAllowed = true;
        assertTrue(contract.cleanSensing(controller));
        assertEquals(2, controller.mNetWorkSwitchState);
        assertEquals(2, controller.mNetWorkType);
        assertTrue(controller.mDisableNetAllowed);
        assertEquals(0, controller.mUtils.disconnected);
    }

    @Test public void onlyTheThreeLateDisableMessagesCanBeSuppressed() throws Exception {
        controller.mStatus = 4;
        for (int what = 0; what <= 40; what++) {
            assertEquals("message " + what, what == 11 || what == 19 || what == 20,
                    contract.suppressLateDisable(controller, what));
        }
    }

    @Test public void wifiEnableRestorationIsPreserved() throws Exception {
        controller.mStatus = 4;
        controller.mWifiEnalbe = true;
        assertFalse(contract.suppressLateDisable(controller, 11));
        assertTrue(contract.suppressLateDisable(controller, 19));
        assertTrue(contract.suppressLateDisable(controller, 20));
    }

    @Test public void abnormalAndOtherRestoreMessagesAlwaysKeepOriginalBehavior() throws Exception {
        controller.mStatus = 4;
        for (int what : new int[] {17, 23, 24, 31, 33, 34, 35}) {
            assertFalse(contract.suppressLateDisable(controller, what));
        }
    }

    @Test public void lateDisableRequiresTheExactUnchangedNetworkPhase() throws Exception {
        controller.mStatus = 4;
        for (int what : new int[] {11, 19, 20}) {
            controller.mStatus = 2;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mStatus = 4;
            controller.mNetWorkSwitchState = 2;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mNetWorkSwitchState = 0;
            controller.mNetWorkType = 2;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mNetWorkType = 0;
            controller.mDisableNetAllowed = true;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mDisableNetAllowed = false;
            assertTrue(contract.suppressLateDisable(controller, what));
        }
    }

    @Test public void aRealNetworkRestrictionOrRestorationNeverGetsSuppressed() throws Exception {
        controller.mStatus = 4;
        for (int what : new int[] {11, 19, 20}) {
            controller.mIsPidDisNet = true;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mIsPidDisNet = false;
            controller.mIsNetAbnormalActive = true;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mIsNetAbnormalActive = false;
            controller.mIsNeedRestoreMobile = true;
            assertFalse(contract.suppressLateDisable(controller, what));
            controller.mIsNeedRestoreMobile = false;
        }
    }

    @Test public void unreadableOemStatePropagatesInsteadOfGrantingProtection() {
        controller.mUtils.failure = new IllegalStateException("state unavailable");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> contract.cleanSensing(controller));
        assertSame(controller.mUtils.failure, failure.getCause());
    }

    @Test public void missingRuntimeReferencesPropagateInsteadOfInventingState() {
        controller.mUtils = null;
        assertThrows(IllegalStateException.class, () -> contract.cleanSensing(controller));
        controller.mUtils = new FakeUtils();
        controller.mConfigUpdateUtil = null;
        assertThrows(IllegalStateException.class, () -> contract.cleanSensing(controller));
        controller.mConfigUpdateUtil = new FakeConfig();
        controller.mWorkHandler = null;
        assertThrows(IllegalStateException.class, () -> contract.cleanSensing(controller));
    }

    @Test public void nullOwnerLockAndContextAreNotValidRuntimeObjects() {
        assertThrows(IllegalStateException.class, () -> contract.owner(new FakeHandler(null)));
        controller.mLock = null;
        assertThrows(IllegalStateException.class, () -> contract.lock(controller));
        assertThrows(IllegalStateException.class, () -> contract.context(controller));
    }

    @Test public void unexpectedFieldTypeRejectsInstallation() {
        NoSuchFieldException failure = assertThrows(NoSuchFieldException.class,
                () -> new OplusDeepSleepContract(WrongStatusType.class, FakeHandler.class));
        assertTrue(failure.getMessage().endsWith(".mStatus"));
    }

    @Test public void staticStateFieldRejectsInstallation() {
        NoSuchFieldException failure = assertThrows(NoSuchFieldException.class,
                () -> new OplusDeepSleepContract(StaticStatus.class, FakeHandler.class));
        assertTrue(failure.getMessage().endsWith(".mStatus"));
    }

    @Test public void wrongHandlerOwnerTypeRejectsInstallation() {
        NoSuchFieldException failure = assertThrows(NoSuchFieldException.class,
                () -> new OplusDeepSleepContract(FakeController.class, WrongOwnerHandler.class));
        assertTrue(failure.getMessage().endsWith(".this$0"));
    }

    @Test public void wrongEligibilityReturnOrStaticEligibilityRejectsInstallation() {
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(WrongEligibility.class, FakeHandler.class));
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(StaticEligibility.class, FakeHandler.class));
    }

    @Test public void wrongOrStaticDispatchRejectsInstallation() {
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(FakeController.class, WrongDispatch.class));
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(FakeController.class, StaticDispatch.class));
    }

    @Test public void abstractOrNativeEligibilityRejectsInstallation() {
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(AbstractEligibility.class, FakeHandler.class));
        assertThrows(NoSuchMethodException.class,
                () -> new OplusDeepSleepContract(NativeEligibility.class, FakeHandler.class));
    }

    static final class FakeController {
        private int mStatus = 2, mNetWorkSwitchState, mNetWorkType;
        private boolean mDisableNetAllowed, mIsPidDisNet, mIsNetAbnormalActive;
        private boolean mIsNeedRestoreMobile, mWifiEnalbe;
        private Object mLock = new Object();
        private Context mContext;
        private FakeUtils mUtils = new FakeUtils();
        private FakeConfig mConfigUpdateUtil = new FakeConfig();
        private FakeHandler mWorkHandler = new FakeHandler(this);
        private boolean isNotAllowDisableNetwork() { return false; }
    }

    static final class FakeUtils {
        int disconnected;
        boolean airplaneReset;
        RuntimeException failure;
        public int getDisNetType() {
            if (failure != null) throw failure;
            return disconnected;
        }
        public boolean getDeepSleepResetAirPlaneStatus() { return airplaneReset; }
    }

    static final class FakeConfig {
        boolean airplane;
        public boolean r() { return airplane; }
    }

    static class FakeHandlerBase {
        final Set<Integer> pending = new HashSet<>();
        int lastCheckedMessage;
        public boolean hasMessages(int what) {
            lastCheckedMessage = what;
            return pending.contains(what);
        }
    }

    static final class FakeHandler extends FakeHandlerBase {
        private final FakeController this$0;
        FakeHandler(FakeController controller) { this$0 = controller; }
        public void handleMessage(Message message) { }
    }

    static final class WrongStatusType {
        private String mStatus;
        private boolean isNotAllowDisableNetwork() { return false; }
    }

    static final class StaticStatus {
        private static int mStatus;
        private boolean isNotAllowDisableNetwork() { return false; }
    }

    static final class WrongOwnerHandler {
        private Object this$0;
        public void handleMessage(Message message) { }
    }

    static final class WrongEligibility {
        private int isNotAllowDisableNetwork() { return 0; }
    }

    static final class StaticEligibility {
        private static boolean isNotAllowDisableNetwork() { return false; }
    }

    static abstract class AbstractEligibility {
        abstract boolean isNotAllowDisableNetwork();
    }

    static final class NativeEligibility {
        private native boolean isNotAllowDisableNetwork();
    }

    static final class WrongDispatch {
        public boolean handleMessage(Message message) { return false; }
    }

    static final class StaticDispatch {
        public static void handleMessage(Message message) { }
    }
}
