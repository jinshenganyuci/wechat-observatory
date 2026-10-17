package cc.wechat.observatory.realtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class RealtimeProtectionClientTest {
    private final FakeContext context = new FakeContext();
    // Robolectric 4.14 runs SDK35; the protocol's supported SDK36 gate is injected.
    private final RealtimeProtectionClient.Client client = new RealtimeProtectionClient.Client(36);

    @Test public void enabledUsesOnePrivateReceiverAndDisableReleasesIt() {
        context.ack = acknowledgement(Process.myUid());
        client.update(context, true);
        client.update(context, true);
        assertEquals(1, context.registrations);
        assertEquals(Context.RECEIVER_NOT_EXPORTED, context.flags);
        assertEquals(1, context.filter.countActions());
        assertEquals(RealtimeProtectionClient.ACTION, context.filter.getAction(0));
        assertEquals("息屏保护已登记", status());
        client.update(context, false);
        client.update(context, false);
        assertEquals(1, context.removals);
        assertEquals("后台保护未启用", status());
        client.update(context, true);
        assertEquals(2, context.registrations);
    }

    @Test public void normalAndroidNullReturnNeverClaimsSystemProtection() {
        client.update(context, true);
        assertTrue(status().contains("未确认"));
        client.update(context, true);
        assertEquals("no timed registration polling", 1, context.registrations);
        client.update(context, false);
        assertEquals(1, context.removals);
    }

    @Test public void wrongUidOrWrongActionDoesNotAcknowledgeProtection() {
        context.ack = acknowledgement(Process.myUid() + 1);
        client.update(context, true);
        assertTrue(status().contains("未确认"));
        client.update(context, false);
        context.ack = acknowledgement(Process.myUid()).setAction("another.action");
        client.update(context, true);
        assertTrue(status().contains("未确认"));
    }

    @Test public void failedUnregisterIsNotReportedAsDisabledAndCanBeRetried() {
        context.ack = acknowledgement(Process.myUid());
        client.update(context, true);
        context.failUnregister = true;
        client.update(context, false);
        assertTrue(status().contains("注销未确认"));
        context.failUnregister = false;
        client.update(context, false);
        assertEquals(2, context.removals);
        assertEquals("后台保护未启用", status());
    }

    @Test public void absentRegistrationAfterFailedRegisterCanBeCleared() {
        context.failRegister = true;
        client.update(context, true);
        assertTrue(status().contains("登记失败"));
        context.absentOnUnregister = true;
        client.update(context, false);
        assertEquals("后台保护未启用", status());
        context.failRegister = false;
        context.ack = acknowledgement(Process.myUid());
        client.update(context, true);
        assertEquals("息屏保护已登记", status());
    }

    @Test public void nonWeChatContextCannotRegisterAToken() {
        context.packageName = "other.app";
        client.update(context, true);
        assertEquals(0, context.registrations);
        assertTrue(status().contains("不支持"));
    }

    @Test public void unsupportedAndroidVersionCannotRegisterEvenWithAValidAck() {
        context.ack = acknowledgement(Process.myUid());
        for (int api : new int[]{23, 33, 35, 37}) {
            RealtimeProtectionClient.Client unsupported = new RealtimeProtectionClient.Client(api);
            unsupported.update(context, true);
            assertTrue(unsupported.statusText().contains("不支持"));
        }
        assertEquals(0, context.registrations);
    }

    private String status() {
        // Instance state keeps tests independent of the production singleton.
        return client.statusText();
    }

    private static Intent acknowledgement(int uid) {
        return new Intent(RealtimeProtectionClient.ACTION).putExtra(RealtimeProtectionClient.ACK_UID, uid);
    }

    static final class FakeContext extends ContextWrapper {
        int registrations, removals, flags;
        String packageName = ProtectionTokens.WECHAT;
        boolean failRegister, failUnregister, absentOnUnregister;
        IntentFilter filter;
        Intent ack;
        FakeContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public String getPackageName() { return packageName; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            registrations++;
            this.filter = filter;
            this.flags = flags;
            if (failRegister) throw new SecurityException("unavailable");
            return ack;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) {
            removals++;
            if (absentOnUnregister) throw new IllegalArgumentException("absent");
            if (failUnregister) throw new SecurityException("unavailable");
        }
    }
}
