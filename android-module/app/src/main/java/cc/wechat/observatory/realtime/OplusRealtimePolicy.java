package cc.wechat.observatory.realtime;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Optional, process-lifetime Hans exemption for an explicitly registered WeChat UID. */
public final class OplusRealtimePolicy {
    // This is a private OEM contract, not a promise that all Android 16 ROMs match.
    // Add a build only after tools/verify_oplus_realtime.py and device validation.
    private static final String AUDITED_BUILD =
            "OnePlus/PJZ110/OP5D0DL1:16/BP2A.250605.015/V.3205195-ced00f-d0523d:user/release-keys";
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static boolean installed;

    private OplusRealtimePolicy() { }

    static boolean isAuditedBuild() {
        return Build.VERSION.SDK_INT == 36 && AUDITED_BUILD.equals(Build.FINGERPRINT);
    }

    public static synchronized void install(ClassLoader loader) {
        if (installed || FAILED.get() || !isAuditedBuild()) return;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            HansPolicyContract contract = new HansPolicyContract(
                    Class.forName("com.android.server.am.OplusHansManager", false, loader),
                    Class.forName("com.android.server.hans.OplusHansImportance", false, loader),
                    Class.forName("com.android.server.hans.OplusHansPackage", false, loader),
                    Class.forName("com.android.server.hans.scene.HansSceneManager", false, loader));
            hooks.add(XposedBridge.hookMethod(contract.importance, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !Boolean.FALSE.equals(param.getResult())) return;
                    try {
                        Object pkg = param.args[0];
                        if (!eligible(contract, pkg)) return;
                        synchronized (contract.lock.invoke(pkg)) {
                            contract.reason.invoke(pkg, "Observatory realtime");
                            param.setResult(true);
                        }
                    } catch (Throwable failure) { disable(failure); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(contract.destroySocket, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (eligible(contract, (Integer) param.args[0], (String) param.args[1])) {
                            param.setResult(null);
                        }
                    } catch (Throwable failure) { disable(failure); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(contract.firewall, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (Boolean.FALSE.equals(param.args[1]) && eligible(contract, param.args[0])) {
                            // Run the original method so both Hans's recorded state
                            // and its actual network rule agree that the UID is allowed.
                            param.args[1] = true;
                        }
                    } catch (Throwable failure) { disable(failure); }
                }
            }));
            if (!SystemProtectionRegistry.install(loader)) {
                throw new IllegalStateException("Unsupported receiver lifecycle contract");
            }
            SystemProtectionRegistry.setQueryDecisionSupplier(() -> {
                try { return !FAILED.get() && contract.isThermallyUnrestricted(); }
                catch (Throwable failure) { disable(failure); return false; }
            });
            installed = true;
            SystemProtectionRegistry.setPolicyAvailable(true);
            XposedBridge.log("Observatory realtime: optional Hans policy ready; no UID registered yet");
        } catch (Throwable unsupported) {
            SystemProtectionRegistry.setPolicyAvailable(false);
            for (XC_MethodHook.Unhook hook : hooks) {
                try { hook.unhook(); } catch (Throwable ignored) { }
            }
            disable(unsupported);
        }
    }

    private static boolean eligible(HansPolicyContract contract, Object pkg) throws Exception {
        return pkg != null && eligible(contract, (Integer) contract.uid.invoke(pkg),
                (String) contract.packageName.invoke(pkg));
    }

    private static boolean eligible(HansPolicyContract contract, int uid, String name) throws Exception {
        if (FAILED.get() || !ProtectionTokens.WECHAT.equals(name)
                || !SystemProtectionRegistry.isProtectedUid(uid)) return false;
        return contract.isThermallyUnrestricted();
    }

    private static void disable(Throwable failure) {
        SystemProtectionRegistry.setPolicyAvailable(false);
        if (FAILED.compareAndSet(false, true)) {
            XposedBridge.log("Observatory realtime disabled; original power policy retained: "
                    + failure.getClass().getSimpleName());
        }
    }
}
