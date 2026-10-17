package cc.wechat.observatory;

import cc.wechat.observatory.realtime.OplusRealtimePolicy;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Keep the WeChat worker and its static executors out of system_server. */
public final class ModuleEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if ("android".equals(param.packageName) && "android".equals(param.processName)) {
            OplusRealtimePolicy.install(param.classLoader);
        } else if ("com.tencent.mm".equals(param.packageName)
                && "com.tencent.mm".equals(param.processName)) {
            new HookEntry().handleLoadPackage(param);
        }
    }
}
