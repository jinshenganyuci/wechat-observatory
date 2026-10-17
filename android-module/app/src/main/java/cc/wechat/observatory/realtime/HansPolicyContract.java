package cc.wechat.observatory.realtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolve the entire audited vendor contract before installing any policy hook. */
final class HansPolicyContract {
    private static final int THERMAL_SCENE = 4;
    final Method importance, destroySocket, firewall, managerInstance, thermalLevel;
    final Method uid, packageName, lock, reason;
    final Method sceneInstance, thermalManagement, thermalPriority, thermalMode;

    HansPolicyContract(Class<?> manager, Class<?> importanceClass, Class<?> hansPackage, Class<?> scene)
            throws ReflectiveOperationException {
        importance = method(importanceClass, "isImportantPkg", boolean.class, false,
                hansPackage, int.class, boolean.class, String.class);
        destroySocket = method(manager, "nwPowerDestroySocketForProc", void.class, false,
                int.class, String.class, int.class);
        firewall = method(manager, "nwPowerSetFirewall", void.class, false,
                hansPackage, boolean.class, int.class, int.class);
        managerInstance = method(manager, "getInstance", manager, true);
        thermalLevel = method(manager, "getStrictModeLevelByScene", int.class, false, int.class);
        uid = method(hansPackage, "getUid", int.class, false);
        packageName = method(hansPackage, "getPkgName", String.class, false);
        lock = method(hansPackage, "getLock", Object.class, false);
        reason = method(hansPackage, "setImportantReason", void.class, false, String.class);
        sceneInstance = method(scene, "getInstance", scene, true);
        thermalManagement = method(scene, "getThermalMngLevel", int.class, false);
        thermalPriority = method(scene, "getThermalPriorityLevel", int.class, false);
        thermalMode = method(scene, "isInThermalMode", boolean.class, false);
    }

    boolean isThermallyUnrestricted() throws Exception {
        Object manager = managerInstance.invoke(null);
        Object scene = sceneInstance.invoke(null);
        // Thermal mode is independent of the three numeric policy levels.
        // Unknown or uninitialized state retains the vendor's original policy.
        return manager != null && scene != null
                && ((Integer) thermalLevel.invoke(manager, THERMAL_SCENE)) == 0
                && ((Integer) thermalManagement.invoke(scene)) == 0
                && ((Integer) thermalPriority.invoke(scene)) == 0
                && Boolean.FALSE.equals(thermalMode.invoke(scene));
    }

    private static Method method(Class<?> owner, String name, Class<?> result,
                                 boolean isStatic, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        int modifiers = method.getModifiers();
        if (method.getReturnType() != result || Modifier.isStatic(modifiers) != isStatic
                || Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
        method.setAccessible(true);
        return method;
    }
}
