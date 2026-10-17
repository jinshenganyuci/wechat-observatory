package cc.wechat.observatory.realtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Use the OEM's own no-network-disconnection mode for a fresh sensing cycle. */
public final class OplusDeepSleepPolicy {
    private static final String TAG = "WechatGateway";
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static boolean installed;
    private static Boolean auditedPackage;

    private OplusDeepSleepPolicy() { }

    public static synchronized void install(ClassLoader loader) {
        if (installed || FAILED.get() || !OplusRealtimePolicy.isAuditedBuild() || Process.myUid() != 1000) return;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            OplusDeepSleepContract contract = new OplusDeepSleepContract(
                    Class.forName("com.oplus.deepsleep.ControllerCenter", false, loader),
                    Class.forName("com.oplus.deepsleep.ControllerCenter$WorkHandler", false, loader));
            // This is the decision for an OEM cycle, not a cached authorization.
            // The next eligibility evaluation always discards it before querying again.
            Map<Object, Boolean> cycles = Collections.synchronizedMap(new WeakHashMap<>());
            hooks.add(XposedBridge.hookMethod(contract.eligibility, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        synchronized (contract.lock(param.thisObject)) {
                            cycles.remove(param.thisObject);
                        }
                    } catch (Throwable failure) { fail(failure); }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (FAILED.get() || param.hasThrowable() || !Boolean.FALSE.equals(param.getResult())) return;
                    try {
                        Object controller = param.thisObject;
                        Context context = (Context) contract.context(controller);
                        if (!auditedPackage(context)) return;
                        synchronized (contract.lock(controller)) {
                            // Never zero an existing disconnect/recovery cycle. It must
                            // finish through the OEM's original restore path first.
                            if (!contract.cleanSensing(controller)
                                    || !RealtimePolicyQuery.isProtectionActive(context)
                                    || !contract.cleanSensing(controller)) return;
                            cycles.put(controller, Boolean.TRUE);
                            param.setResult(true);
                        }
                        Log.i(TAG, "night network: fresh sensing cycle keeps network available");
                    } catch (Throwable failure) { fail(failure); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(contract.dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (FAILED.get() || !(param.args[0] instanceof Message)) return;
                    int what = ((Message) param.args[0]).what;
                    if (what != 11 && what != 19 && what != 20) return;
                    try {
                        Object controller = contract.owner(param.thisObject);
                        boolean discard;
                        synchronized (contract.lock(controller)) {
                            discard = Boolean.TRUE.equals(cycles.get(controller))
                                    && contract.suppressLateDisable(controller, what);
                        }
                        if (discard) {
                            param.setResult(null);
                            Log.i(TAG, "night network: discarded stale automatic disable message=" + what);
                        }
                        // Modem callbacks, mixed restore/re-disable (23), and
                        // every enable=true path retain their original behavior.
                    } catch (Throwable failure) { fail(failure); }
                }
            }));
            installed = true;
            Log.i(TAG, "night network: OEM eligibility hooks installed");
        } catch (Throwable unsupported) {
            for (XC_MethodHook.Unhook hook : hooks) {
                try { hook.unhook(); } catch (Throwable ignored) { }
            }
            fail(unsupported);
        }
    }

    private static synchronized boolean auditedPackage(Context context) throws Exception {
        if (context == null || !RealtimePolicyQuery.BATTERY.equals(context.getPackageName())) return false;
        if (auditedPackage == null) {
            PackageInfo info = context.getPackageManager().getPackageInfo(RealtimePolicyQuery.BATTERY, 0);
            auditedPackage = info.getLongVersionCode() == 29 && "1.2.9".equals(info.versionName);
            if (!auditedPackage) Log.i(TAG, "night network: unsupported Battery version; original policy retained");
        }
        return auditedPackage;
    }

    private static void fail(Throwable failure) {
        if (FAILED.compareAndSet(false, true)) {
            Log.i(TAG, "night network: original policy retained after " + failure.getClass().getSimpleName());
        }
    }
}
