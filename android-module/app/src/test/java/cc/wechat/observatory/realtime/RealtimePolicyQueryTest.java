package cc.wechat.observatory.realtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class RealtimePolicyQueryTest {
    @Test public void eachQueryUsesOneNewPrivateNonceAndAlwaysUnregisters() {
        FakeContext context = new FakeContext();
        context.active = true;
        assertTrue(query(context));
        assertTrue(query(context));
        assertEquals(2, context.registrations);
        assertEquals(2, context.removals);
        assertNotEquals(context.filters.get(0).getCategory(0), context.filters.get(1).getCategory(0));
        assertNotSame(context.receivers.get(0), context.receivers.get(1));
        for (IntentFilter filter : context.filters) {
            assertEquals(1, filter.countActions());
            assertEquals(1, filter.countCategories());
            assertEquals(RealtimePolicyQuery.ACTION, filter.getAction(0));
            assertNotNull(RealtimePolicyQuery.nonceCategory(filter));
        }
        assertEquals(Context.RECEIVER_NOT_EXPORTED, context.flags);
    }

    @Test public void falseOrMissingResponsePreservesNormalPolicy() {
        FakeContext context = new FakeContext();
        assertFalse(query(context));
        context.nullReply = true;
        assertFalse(query(context));
        assertEquals(2, context.removals);
    }

    @Test public void staleStickySameActionCannotForgeAFreshConfirmation() {
        FakeContext context = new FakeContext();
        context.active = true;
        assertTrue(query(context));
        Intent oldReply = context.lastReply;
        context.fixedReply = oldReply;
        assertFalse(query(context));
        context.fixedReply = new Intent(RealtimePolicyQuery.ACTION).putExtra(RealtimePolicyQuery.ACTIVE, true);
        assertFalse(query(context));
        assertEquals(3, context.removals);
    }

    @Test public void wrongActionOrAdditionalCategoryCannotConfirm() {
        FakeContext context = new FakeContext();
        context.active = true;
        context.wrongAction = true;
        assertFalse(query(context));
        context.wrongAction = false;
        context.extraCategory = true;
        assertFalse(query(context));
        assertEquals(2, context.removals);
    }

    @Test public void registerAndUnregisterFailuresFailClosedWithoutRetainedState() {
        FakeContext context = new FakeContext();
        context.failRegister = true;
        assertFalse(query(context));
        assertEquals(1, context.removals);
        context.failRegister = false;
        context.active = true;
        context.failUnregister = true;
        assertFalse(query(context));
        context.failUnregister = false;
        assertTrue(query(context));
        assertEquals(3, context.registrations);
        assertEquals(3, context.removals);
    }

    @Test public void unexpectedEnvironmentNeverRegisters() {
        FakeContext context = new FakeContext();
        assertFalse(RealtimePolicyQuery.query(null, 36, 1000));
        assertFalse(RealtimePolicyQuery.query(context, 35, 1000));
        assertFalse(RealtimePolicyQuery.query(context, 36, 101000));
        context.packageName = "another.system.app";
        assertFalse(query(context));
        assertEquals(0, context.registrations);
        assertEquals(0, context.removals);
    }

    @Test public void onlyOneCanonicalNonceAndOneActionAreAccepted() {
        FakeContext context = new FakeContext();
        query(context);
        IntentFilter valid = context.filters.get(0);
        assertNotNull(RealtimePolicyQuery.nonceCategory(valid));
        assertNull(RealtimePolicyQuery.nonceCategory(new IntentFilter(RealtimePolicyQuery.ACTION)));
        IntentFilter extraAction = new IntentFilter(valid);
        extraAction.addAction("another.action");
        assertNull(RealtimePolicyQuery.nonceCategory(extraAction));
        IntentFilter extraCategory = new IntentFilter(valid);
        extraCategory.addCategory("another.category");
        assertNull(RealtimePolicyQuery.nonceCategory(extraCategory));
        IntentFilter malformed = new IntentFilter(RealtimePolicyQuery.ACTION);
        malformed.addCategory(RealtimePolicyQuery.ACTION + ".nonce.1-1-1-1-1");
        assertNull(RealtimePolicyQuery.nonceCategory(malformed));
    }

    private static boolean query(FakeContext context) { return RealtimePolicyQuery.query(context, 36, 1000); }

    static final class FakeContext extends ContextWrapper {
        final List<IntentFilter> filters = new ArrayList<>();
        final List<BroadcastReceiver> receivers = new ArrayList<>();
        String packageName = RealtimePolicyQuery.BATTERY;
        int registrations, removals, flags;
        boolean active, nullReply, wrongAction, extraCategory, failRegister, failUnregister;
        Intent fixedReply, lastReply;

        FakeContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public String getPackageName() { return packageName; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            registrations++;
            receivers.add(receiver);
            filters.add(filter);
            this.flags = flags;
            if (failRegister) throw new SecurityException("register failed");
            if (nullReply) return null;
            if (fixedReply != null) return fixedReply;
            lastReply = new Intent(wrongAction ? "another.action" : RealtimePolicyQuery.ACTION)
                    .addCategory(filter.getCategory(0)).putExtra(RealtimePolicyQuery.ACTIVE, active);
            if (extraCategory) lastReply.addCategory("another.category");
            return lastReply;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) {
            removals++;
            assertSame(receivers.get(receivers.size() - 1), receiver);
            if (failUnregister) throw new SecurityException("unregister failed");
        }
    }
}
