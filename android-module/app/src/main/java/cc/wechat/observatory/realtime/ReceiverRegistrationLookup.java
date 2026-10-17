package cc.wechat.observatory.realtime;

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** The Android 16 AMS/BroadcastController contract; unknown layouts fail closed. */
final class ReceiverRegistrationLookup {
    private final Field controller, context, receivers, uid, pid, app, receiver, info, exported;
    private final Class<?> receiverListClass;
    private final Class<?> filterClass;

    ReceiverRegistrationLookup(Class<?> ams, Class<?> broadcastController, Class<?> receiverList,
                               Class<?> processRecord, Class<?> broadcastFilter) throws ReflectiveOperationException {
        controller = field(ams, "mBroadcastController");
        context = field(ams, "mContext");
        receivers = field(broadcastController, "mRegisteredReceivers");
        uid = field(receiverList, "uid");
        pid = field(receiverList, "pid");
        app = field(receiverList, "app");
        receiver = field(receiverList, "receiver");
        info = field(processRecord, "info");
        exported = field(broadcastFilter, "exported");
        receiverListClass = receiverList;
        filterClass = broadcastFilter;
        if (controller.getType() != broadcastController || !Context.class.isAssignableFrom(context.getType())
                || !Map.class.isAssignableFrom(receivers.getType()) || uid.getType() != int.class
                || pid.getType() != int.class || app.getType() != processRecord
                || !IInterface.class.isAssignableFrom(receiver.getType())
                || !ApplicationInfo.class.isAssignableFrom(info.getType()) || exported.getType() != boolean.class
                || !List.class.isAssignableFrom(receiverList) || !IntentFilter.class.isAssignableFrom(broadcastFilter)) {
            throw new NoSuchFieldException("Unsupported receiver registry layout");
        }
    }

    Context context(Object ams) throws IllegalAccessException { return (Context) context.get(ams); }

    /** Call while synchronized on AMS, exactly like BroadcastController itself. */
    ProtectionTokens.Registration find(Object ams, IBinder token) {
        return find(ams, token, null);
    }

    ProtectionTokens.Registration findQuery(Object ams, IBinder token, String nonceCategory) {
        if (nonceCategory == null) return null;
        return find(ams, token, nonceCategory);
    }

    private ProtectionTokens.Registration find(Object ams, IBinder token, String nonceCategory) {
        try {
            Object registry = controller.get(ams);
            if (registry == null) return null;
            Object row = ((Map<?, ?>) receivers.get(registry)).get(token);
            if (!receiverListClass.isInstance(row)) return null;
            IInterface actualReceiver = (IInterface) receiver.get(row);
            if (actualReceiver == null || !token.equals(actualReceiver.asBinder())) return null;
            Object process = app.get(row);
            if (process == null) return null;
            ApplicationInfo application = (ApplicationInfo) info.get(process);
            int actualUid = uid.getInt(row);
            if (application == null || application.uid != actualUid) return null;
            boolean marker = false;
            for (Object item : (List<?>) row) {
                if (filterClass.isInstance(item) && !exported.getBoolean(item)) {
                    IntentFilter filter = (IntentFilter) item;
                    if (nonceCategory == null ? privateMarker(filter)
                            : nonceCategory.equals(RealtimePolicyQuery.nonceCategory(filter))) marker = true;
                }
            }
            return new ProtectionTokens.Registration(token, actualUid, pid.getInt(row), application.packageName, marker);
        } catch (ReflectiveOperationException | RuntimeException mismatch) {
            return null;
        }
    }

    static boolean privateMarker(IntentFilter filter) {
        return filter != null && filter.countActions() == 1
                && RealtimeProtectionClient.ACTION.equals(filter.getAction(0));
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
