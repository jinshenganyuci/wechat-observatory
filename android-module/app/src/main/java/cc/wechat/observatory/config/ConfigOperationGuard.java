package cc.wechat.observatory.config;

import cc.wechat.observatory.wechat.AccountSession;
import cc.wechat.observatory.wechat.RuntimeAccount;

/** Checks configuration changes, identity publication and cached control-frame scope. */
public final class ConfigOperationGuard {
    private ConfigOperationGuard() { }

    public static boolean allows(BridgeConfig expected, BridgeConfig current) {
        return expected != null && current != null && current.enabled
                && current.baseUrl != null && !current.baseUrl.trim().isEmpty()
                && current.apiKey != null && !current.apiKey.trim().isEmpty()
                && expected.signature != null && expected.signature.equals(current.signature);
    }

    public static void requireCurrent(BridgeConfig expected, BridgeConfig current) {
        if (!allows(expected, current)) {
            throw new IllegalStateException("Gateway configuration changed or disabled before operation");
        }
    }

    /** Check under the caller's publication lock, for both successful and failed identity reads. */
    public static boolean allowsIdentityResult(BridgeConfig expected, BridgeConfig current,
                                              long readRevision, long currentRevision,
                                              RuntimeAccount observedAccount, RuntimeAccount currentAccount) {
        return readRevision == currentRevision && observedAccount == currentAccount
                && allows(expected, current);
    }

    /** Cached scope check for control frames only; business operations still resolve the live account. */
    public static boolean allowsStream(BridgeConfig expected, BridgeConfig current,
                                       RuntimeAccount currentAccount, AccountSession streamSession) {
        return allows(expected, current) && currentAccount != null && streamSession != null
                && expected.runtimeAccount == currentAccount && currentAccount.session == streamSession;
    }
}
