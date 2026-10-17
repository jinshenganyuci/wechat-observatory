package cc.wechat.observatory.realtime;

import android.content.Context;
import android.os.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Read-only contract for the audited Battery controller; callers hold its existing mLock. */
final class OplusDeepSleepContract {
    final Method eligibility;
    final Method dispatch;

    private final Field owner, lock, context, status, networkSwitch, networkType;
    private final Field disableAllowed, pidDisconnected, abnormal, needsMobileRestore, wifiEnabled;
    private final Field utils, config, workHandler;
    private final Method disconnectedType, airplaneResetPending, airplanePolicy, hasMessages;

    OplusDeepSleepContract(Class<?> controller, Class<?> handler)
            throws ReflectiveOperationException {
        concrete(controller);
        concrete(handler);
        eligibility = method(controller, "isNotAllowDisableNetwork", boolean.class);
        dispatch = method(handler, "handleMessage", void.class, Message.class);
        status = field(controller, "mStatus", int.class);
        networkSwitch = field(controller, "mNetWorkSwitchState", int.class);
        networkType = field(controller, "mNetWorkType", int.class);
        disableAllowed = field(controller, "mDisableNetAllowed", boolean.class);
        pidDisconnected = field(controller, "mIsPidDisNet", boolean.class);
        abnormal = field(controller, "mIsNetAbnormalActive", boolean.class);
        needsMobileRestore = field(controller, "mIsNeedRestoreMobile", boolean.class);
        wifiEnabled = field(controller, "mWifiEnalbe", boolean.class);
        owner = field(handler, "this$0", controller);
        lock = field(controller, "mLock", Object.class);
        context = field(controller, "mContext", Context.class);
        workHandler = field(controller, "mWorkHandler", handler);
        utils = objectField(controller, "mUtils");
        config = objectField(controller, "mConfigUpdateUtil");
        disconnectedType = method(utils.getType(), "getDisNetType", int.class);
        airplaneResetPending = method(utils.getType(), "getDeepSleepResetAirPlaneStatus", boolean.class);
        airplanePolicy = method(config.getType(), "r", boolean.class);
        // The ROM WorkHandler inherits this public, concrete method from Handler.
        hasMessages = checkedMethod(handler.getMethod("hasMessages", int.class), boolean.class);
    }

    Object owner(Object handler) throws IllegalAccessException {
        return required(owner, handler);
    }

    Object lock(Object controller) throws IllegalAccessException {
        return required(lock, controller);
    }

    Object context(Object controller) throws IllegalAccessException {
        return required(context, controller);
    }

    boolean cleanSensing(Object controller) throws ReflectiveOperationException {
        if (status.getInt(controller) != 2 || !noPendingNetworkRestore(controller)) return false;
        Object state = required(utils, controller);
        int disconnected = (Integer) disconnectedType.invoke(state);
        // 6 is the OEM's switch=0 cycle, which never disabled Wi-Fi/mobile data.
        if (disconnected != 0 && disconnected != 6) return false;
        return !((Boolean) airplanePolicy.invoke(required(config, controller)))
                && !((Boolean) airplaneResetPending.invoke(state))
                && !((Boolean) hasMessages.invoke(required(workHandler, controller), 17));
    }

    boolean suppressLateDisable(Object controller, int what) throws IllegalAccessException {
        if (what != 19 && what != 20 && what != 11) return false;
        if (status.getInt(controller) != 4 || networkSwitch.getInt(controller) != 0
                || networkType.getInt(controller) != 0 || disableAllowed.getBoolean(controller)
                || !noPendingNetworkRestore(controller)) return false;
        // Message 11 can also be a real Wi-Fi restoration; retain its enable=true branch.
        return what != 11 || !wifiEnabled.getBoolean(controller);
    }

    private boolean noPendingNetworkRestore(Object controller) throws IllegalAccessException {
        return !pidDisconnected.getBoolean(controller) && !abnormal.getBoolean(controller)
                && !needsMobileRestore.getBoolean(controller);
    }

    private static Object required(Field field, Object target) throws IllegalAccessException {
        Object value = field.get(target);
        if (value == null) throw new IllegalStateException("Unavailable " + field.getName());
        return value;
    }

    private static Field objectField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = field(owner, name, null);
        concrete(field.getType());
        return field;
    }

    private static Field field(Class<?> owner, String name, Class<?> expected)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers())
                || (expected != null && field.getType() != expected)
                || (expected == null && (field.getType().isPrimitive() || field.getType().isArray()))) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
            throws ReflectiveOperationException {
        return checkedMethod(owner.getDeclaredMethod(name, arguments), result);
    }

    private static Method checkedMethod(Method method, Class<?> result) throws NoSuchMethodException {
        int modifiers = method.getModifiers();
        if (method.getReturnType() != result || Modifier.isStatic(modifiers)
                || Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName() + "." + method.getName());
        }
        method.setAccessible(true);
        return method;
    }

    private static void concrete(Class<?> type) throws NoSuchMethodException {
        if (type == null || type.isPrimitive() || type.isArray() || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())) {
            throw new NoSuchMethodException("Unsupported concrete type");
        }
    }
}
