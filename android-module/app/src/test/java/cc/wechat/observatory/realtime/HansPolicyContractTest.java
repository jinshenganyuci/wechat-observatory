package cc.wechat.observatory.realtime;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.*;

/** Exercises the production reflection contract without Android or Xposed stubs. */
public class HansPolicyContractTest {
    private HansPolicyContract contract;

    @Before public void setup() throws Exception {
        FakeManager.instance = new FakeManager();
        FakeScene.instance = new FakeScene();
        contract = contract(FakeScene.class);
    }

    @Test public void allZeroLevelsAndInactiveThermalModeAllowExemption() throws Exception {
        assertTrue(contract.isThermallyUnrestricted());
        assertEquals("the audited thermal scene is 4", 4, FakeManager.instance.requestedScene);
    }

    @Test public void independentThermalModeRejectsEvenWhenAllNumericLevelsAreZero() throws Exception {
        FakeScene.instance.thermalMode = true;
        assertFalse(contract.isThermallyUnrestricted());
        FakeScene.instance.thermalMode = false;
        assertTrue(contract.isThermallyUnrestricted());
    }

    @Test public void anyActiveNumericThermalLevelRejectsExemption() throws Exception {
        FakeManager.instance.thermalLevel = 1;
        assertFalse(contract.isThermallyUnrestricted());
        FakeManager.instance.thermalLevel = 0;
        FakeScene.instance.managementLevel = 1;
        assertFalse(contract.isThermallyUnrestricted());
        FakeScene.instance.managementLevel = 0;
        FakeScene.instance.priorityLevel = 1;
        assertFalse(contract.isThermallyUnrestricted());
    }

    @Test public void unknownNegativeAndFutureNumericLevelsRejectExemption() throws Exception {
        for (int unknown : new int[] {-1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            FakeManager.instance.thermalLevel = unknown;
            assertFalse("unknown strict thermal level " + unknown, contract.isThermallyUnrestricted());
            FakeManager.instance.thermalLevel = 0;
            FakeScene.instance.managementLevel = unknown;
            assertFalse("unknown management level " + unknown, contract.isThermallyUnrestricted());
            FakeScene.instance.managementLevel = 0;
            FakeScene.instance.priorityLevel = unknown;
            assertFalse("unknown priority level " + unknown, contract.isThermallyUnrestricted());
            FakeScene.instance.priorityLevel = 0;
        }
    }

    @Test public void absentManagerSingletonRejectsExemption() throws Exception {
        FakeManager.instance = null;
        assertFalse(contract.isThermallyUnrestricted());
    }

    @Test public void absentSceneSingletonRejectsExemption() throws Exception {
        FakeScene.instance = null;
        assertFalse(contract.isThermallyUnrestricted());
    }

    @Test public void unreadableThermalStatePropagatesFailureInsteadOfAllowingExemption() {
        FakeScene.instance.failure = new IllegalStateException("thermal state unavailable");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> contract.isThermallyUnrestricted());
        assertSame(FakeScene.instance.failure, failure.getCause());
    }

    @Test public void wrongThermalReturnTypeRejectsTheContractBeforeHooksCanInstall() {
        assertThrows(NoSuchMethodException.class, () -> contract(WrongReturnScene.class));
    }

    @Test public void staticThermalGetterRejectsTheContractBeforeHooksCanInstall() {
        assertThrows(NoSuchMethodException.class, () -> contract(StaticGetterScene.class));
    }

    @Test public void missingThermalModeGetterRejectsTheContractBeforeHooksCanInstall() {
        assertThrows(NoSuchMethodException.class, () -> contract(MissingModeScene.class));
    }

    private static HansPolicyContract contract(Class<?> scene) throws ReflectiveOperationException {
        return new HansPolicyContract(FakeManager.class, FakeImportance.class, FakePackage.class, scene);
    }

    static final class FakeManager {
        static FakeManager instance;
        int thermalLevel;
        int requestedScene = -1;
        public static FakeManager getInstance() { return instance; }
        public int getStrictModeLevelByScene(int scene) {
            requestedScene = scene;
            return thermalLevel;
        }
        public void nwPowerDestroySocketForProc(int uid, String name, int pid) { }
        public void nwPowerSetFirewall(FakePackage pkg, boolean allow, int argument, int extra) { }
    }

    static final class FakeImportance {
        public boolean isImportantPkg(FakePackage pkg, int mask, boolean check, String reason) { return false; }
    }

    static final class FakePackage {
        public int getUid() { return 99910355; }
        public String getPkgName() { return "com.tencent.mm"; }
        public Object getLock() { return this; }
        public void setImportantReason(String reason) { }
    }

    static final class FakeScene {
        static FakeScene instance;
        int managementLevel;
        int priorityLevel;
        boolean thermalMode;
        RuntimeException failure;
        public static FakeScene getInstance() { return instance; }
        public int getThermalMngLevel() { return managementLevel; }
        public int getThermalPriorityLevel() { return priorityLevel; }
        public boolean isInThermalMode() {
            if (failure != null) throw failure;
            return thermalMode;
        }
    }

    static final class WrongReturnScene {
        public static WrongReturnScene getInstance() { return null; }
        public int getThermalMngLevel() { return 0; }
        public int getThermalPriorityLevel() { return 0; }
        public int isInThermalMode() { return 0; }
    }

    static final class StaticGetterScene {
        public static StaticGetterScene getInstance() { return null; }
        public static int getThermalMngLevel() { return 0; }
        public int getThermalPriorityLevel() { return 0; }
        public boolean isInThermalMode() { return false; }
    }

    static final class MissingModeScene {
        public static MissingModeScene getInstance() { return null; }
        public int getThermalMngLevel() { return 0; }
        public int getThermalPriorityLevel() { return 0; }
    }
}
