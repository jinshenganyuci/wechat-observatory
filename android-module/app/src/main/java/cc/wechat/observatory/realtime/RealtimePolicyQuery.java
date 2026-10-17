package cc.wechat.observatory.realtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Process;

import java.util.Set;
import java.util.UUID;

/** One private, synchronous policy snapshot. No receiver or result is retained. */
public final class RealtimePolicyQuery {
    static final String BATTERY = "com.oplus.battery";
    static final String ACTION = "cc.wechat.observatory.RUNTIME_POLICY_QUERY_V1";
    static final String ACTIVE = "protection_active";
    private static final String NONCE_PREFIX = ACTION + ".nonce.";

    private RealtimePolicyQuery() { }

    public static boolean isProtectionActive(Context context) {
        return query(context, Build.VERSION.SDK_INT, Process.myUid());
    }

    static boolean query(Context context, int api, int uid) {
        if (context == null || api != 36 || uid != 1000 || !BATTERY.equals(context.getPackageName())) return false;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { }
        };
        String nonce = NONCE_PREFIX + UUID.randomUUID();
        IntentFilter filter = new IntentFilter(ACTION);
        filter.addCategory(nonce);
        boolean active = false;
        try {
            Intent reply = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Set<String> categories = reply == null ? null : reply.getCategories();
            active = reply != null && ACTION.equals(reply.getAction())
                    && categories != null && categories.size() == 1 && categories.contains(nonce)
                    && reply.getBooleanExtra(ACTIVE, false);
        } catch (RuntimeException unavailable) {
            // A normal framework or a failed query must preserve normal power policy.
        } finally {
            try { context.unregisterReceiver(receiver); }
            catch (RuntimeException unavailable) { active = false; }
        }
        return active;
    }

    /** The nonce is part of the actual AMS filter, not caller-supplied intent extras. */
    static String nonceCategory(IntentFilter filter) {
        if (filter == null || filter.countActions() != 1 || !ACTION.equals(filter.getAction(0))
                || filter.countCategories() != 1) return null;
        String category = filter.getCategory(0);
        if (category == null || !category.startsWith(NONCE_PREFIX)) return null;
        String nonce = category.substring(NONCE_PREFIX.length());
        try { return UUID.fromString(nonce).toString().equals(nonce) ? category : null; }
        catch (IllegalArgumentException malformed) { return null; }
    }
}
