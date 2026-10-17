package cc.wechat.observatory.realtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.UserHandle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** System-server half of the receiver lifecycle marker. No UID is written to disk. */
public final class SystemProtectionRegistry {
    private static final String MODULE = "cc.wechat.observatory";
    private static final ProtectionTokens TOKENS = new ProtectionTokens();
    private static volatile boolean installed;
    private static boolean watchingPackages;
    private static ReceiverRegistrationLookup lookup;

    private SystemProtectionRegistry() { }

    /** Hot path for Hans hooks: memory only, with the complete Android UID. */
    public static boolean isProtectedUid(int uid) { return installed && TOKENS.containsUid(uid); }

    /** Only enable after every required, version-checked Hans hook was installed. */
    public static void setPolicyAvailable(boolean available) { TOKENS.setPolicyAvailable(available && installed); }

    public static synchronized boolean install(ClassLoader loader) {
        if (installed) return true;
        if (Build.VERSION.SDK_INT != 36) return false;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> ams = Class.forName("com.android.server.am.ActivityManagerService", false, loader);
            Class<?> controller = Class.forName("com.android.server.am.BroadcastController", false, loader);
            Class<?> receiverList = Class.forName("com.android.server.am.ReceiverList", false, loader);
            Class<?> process = Class.forName("com.android.server.am.ProcessRecord", false, loader);
            Class<?> filter = Class.forName("com.android.server.am.BroadcastFilter", false, loader);
            Class<?> thread = Class.forName("android.app.IApplicationThread", false, loader);
            Class<?> receiver = Class.forName("android.content.IIntentReceiver", false, loader);
            lookup = new ReceiverRegistrationLookup(ams, controller, receiverList, process, filter);
            Method register = ams.getDeclaredMethod("registerReceiverWithFeature", thread, String.class,
                    String.class, String.class, receiver, IntentFilter.class, String.class, int.class, int.class);
            Method remove = controller.getDeclaredMethod("removeReceiverLocked", receiverList);
            java.lang.reflect.Field controllerService = ReceiverRegistrationLookup.field(controller, "mService");
            java.lang.reflect.Field actualReceiver = ReceiverRegistrationLookup.field(receiverList, "receiver");
            if (controllerService.getType() != ams) return false;
            hooks.add(XposedBridge.hookMethod(register, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isMarkerRequest(param.args)) return;
                    param.setObjectExtra("observatory.uid", Binder.getCallingUid());
                    param.setObjectExtra("observatory.pid", Binder.getCallingPid());
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!installed || !TOKENS.policyAvailable() || param.hasThrowable() || !isMarkerRequest(param.args)) return;
                    try {
                        Object uid = param.getObjectExtra("observatory.uid");
                        Object pid = param.getObjectExtra("observatory.pid");
                        if (!(uid instanceof Integer) || !(pid instanceof Integer)) return;
                        if (Thread.holdsLock(param.thisObject)) return;
                        IBinder token = ((IInterface) param.args[4]).asBinder();
                        Context context = lookup.context(param.thisObject);
                        // Package-manager work and receiver registration must be
                        // outside AMS's lock and under the system-server identity.
                        long epoch = checkModule(context);
                        if (epoch < 0) return;
                        if (TOKENS.register(param.thisObject, () -> lookup.find(param.thisObject, token),
                                (Integer) uid, (Integer) pid, epoch)) {
                            // Private return-value handshake only; never publish a sticky broadcast.
                            param.setResult(new Intent(RealtimeProtectionClient.ACTION)
                                    .putExtra(RealtimeProtectionClient.ACK_UID, (Integer) uid));
                        }
                    } catch (Throwable unavailable) {
                        TOKENS.revokeModule();
                        logFailure("registration", unavailable);
                    }
                }
            }));
            // Covers explicit unregisterReceiver AND AMS's internal process cleanup.
            hooks.add(XposedBridge.hookMethod(remove, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args[0] == null) return;
                    try {
                        Object service = controllerService.get(param.thisObject);
                        IBinder token = ((IInterface) actualReceiver.get(param.args[0])).asBinder();
                        synchronized (service) {
                            // Another registration of this binder must not be erased.
                            if (lookup.find(service, token) == null) TOKENS.remove(token);
                        }
                    } catch (Throwable unavailable) {
                        TOKENS.revokeModule();
                        logFailure("unregistration", unavailable);
                    }
                }
            }));
            installed = true;
            return true;
        } catch (Throwable unsupported) {
            for (XC_MethodHook.Unhook hook : hooks) {
                try { hook.unhook(); } catch (Throwable ignored) { }
            }
            TOKENS.revokeModule();
            logFailure("unsupported framework", unsupported);
            return false;
        }
    }

    private static boolean isMarkerRequest(Object[] args) {
        return args.length == 9 && ProtectionTokens.WECHAT.equals(args[1])
                && args[4] instanceof IInterface && args[5] instanceof IntentFilter
                && ReceiverRegistrationLookup.privateMarker((IntentFilter) args[5])
                && args[8] instanceof Integer && (((Integer) args[8]) & Context.RECEIVER_NOT_EXPORTED) != 0
                && (((Integer) args[8]) & Context.RECEIVER_EXPORTED) == 0;
    }

    /** -1 means the registration must retain normal system power policy. */
    private static long checkModule(Context context) throws Exception {
        if (context == null) return -1;
        long identity = Binder.clearCallingIdentity();
        try {
            watchPackages(context);
            long epoch = TOKENS.epoch();
            boolean available;
            try {
                // Android's system context is user 0, the owner of the system-framework module.
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(MODULE, 0);
                available = info.enabled && (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
                available = false;
            }
            return TOKENS.allowModule(epoch, available) ? epoch : -1;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static synchronized void watchPackages(Context context) throws Exception {
        if (watchingPackages) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        BroadcastReceiver watcher = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (intent.getData() != null && MODULE.equals(intent.getData().getSchemeSpecificPart())) {
                    // Removal/update/Android app-disable invalidates old registrations.
                    // This runs in system_server across users, independently of HMA
                    // or whether a package broadcast reaches the WeChat clone.
                    TOKENS.revokeModule();
                }
            }
        };
        Method register = Context.class.getMethod("registerReceiverAsUser", BroadcastReceiver.class,
                UserHandle.class, IntentFilter.class, String.class, Handler.class, int.class);
        Object allUsers = UserHandle.class.getField("ALL").get(null);
        register.invoke(context, watcher, allUsers, filter, null, null, Context.RECEIVER_NOT_EXPORTED);
        watchingPackages = true;
    }

    private static void logFailure(String stage, Throwable failure) {
        XposedBridge.log("Observatory realtime " + stage + ": " + failure.getClass().getSimpleName());
    }
}
