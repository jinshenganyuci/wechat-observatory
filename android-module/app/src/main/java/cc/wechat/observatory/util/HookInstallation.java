package cc.wechat.observatory.util;

import java.util.HashSet;
import java.util.Set;

/** Shares successful hook installations between lifecycle and worker callbacks. */
public final class HookInstallation {
    public interface Action {
        void install() throws Throwable;
    }

    private final Set<Object> installed = new HashSet<>();

    /**
     * A failed attempt remains retryable. Use the reflected Method as the key so
     * the same method is hooked once, while a replacement class loader gets its
     * own hook. Holding the lock also prevents two startup callbacks racing.
     */
    public synchronized boolean installOnce(Object key, Action action) throws Throwable {
        if (installed.contains(key)) {
            return false;
        }
        action.install();
        installed.add(key);
        return true;
    }
}
