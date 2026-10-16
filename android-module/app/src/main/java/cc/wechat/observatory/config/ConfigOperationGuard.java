package cc.wechat.observatory.config;

/** Admission check for a new operation, independent of registration/account state. */
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
}
