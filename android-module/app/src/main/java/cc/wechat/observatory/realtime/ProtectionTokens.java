package cc.wechat.observatory.realtime;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

/** Process-lifetime registrations only: no persisted UID list, timer or network state. */
final class ProtectionTokens {
    static final String WECHAT = "com.tencent.mm";
    private final Map<IBinder, Entry> entries = new HashMap<>();
    private long moduleEpoch;
    private boolean moduleAvailable;
    private boolean policyAvailable;

    synchronized void setPolicyAvailable(boolean available) {
        policyAvailable = available;
        if (!available) revokeModule();
    }

    synchronized boolean policyAvailable() { return policyAvailable; }

    synchronized long epoch() { return moduleEpoch; }

    /** A package event racing the package-manager query invalidates its result. */
    synchronized boolean allowModule(long observedEpoch, boolean available) {
        if (moduleEpoch != observedEpoch) return false;
        if (!available) {
            revokeModule();
            return false;
        }
        moduleAvailable = true;
        return true;
    }

    synchronized void revokeModule() {
        moduleEpoch++;
        moduleAvailable = false;
        Entry[] old = entries.values().toArray(new Entry[0]);
        entries.clear();
        for (Entry entry : old) unlink(entry);
    }

    /** lookup and publication share AMS's registration lock with unregister. */
    boolean register(Object registrationLock, RegistrationReader lookup,
                     int callingUid, int callingPid, long epoch) {
        synchronized (registrationLock) {
            Registration registration = lookup.read();
            if (registration == null || registration.token == null || callingUid < 10000
                    || registration.uid != callingUid || registration.pid != callingPid
                    || !WECHAT.equals(registration.packageName) || !registration.privateMarker) {
                return false;
            }
            synchronized (this) {
                if (!policyAvailable || !moduleAvailable || moduleEpoch != epoch) return false;
                Entry existing = entries.get(registration.token);
                if (existing != null) return existing.uid == callingUid && existing.token.isBinderAlive();
                Entry entry = new Entry(registration.token, registration.uid);
                entries.put(entry.token, entry);
                try {
                    entry.token.linkToDeath(entry, 0);
                    // Also covers death between publication and linkToDeath.
                    if (entries.get(entry.token) == entry && entry.token.isBinderAlive()) return true;
                } catch (RemoteException | RuntimeException unavailable) {
                    // Never grant protection for a dead or unobservable process.
                }
                remove(entry);
                return false;
            }
        }
    }

    synchronized void remove(IBinder token) {
        Entry entry = entries.get(token);
        if (entry != null) remove(entry);
    }

    synchronized boolean containsUid(int uid) {
        if (!policyAvailable || !moduleAvailable) return false;
        for (Entry entry : entries.values()) if (entry.uid == uid) return true;
        return false;
    }

    private synchronized void remove(Entry entry) {
        if (entries.get(entry.token) != entry) return;
        entries.remove(entry.token);
        unlink(entry);
    }

    private void unlink(Entry entry) {
        try { entry.token.unlinkToDeath(entry, 0); }
        catch (RuntimeException ignored) { /* A dead binder may already have removed the recipient. */ }
    }

    interface RegistrationReader { Registration read(); }

    static final class Registration {
        final IBinder token;
        final int uid;
        final int pid;
        final String packageName;
        final boolean privateMarker;

        Registration(IBinder token, int uid, int pid, String packageName, boolean privateMarker) {
            this.token = token;
            this.uid = uid;
            this.pid = pid;
            this.packageName = packageName;
            this.privateMarker = privateMarker;
        }
    }

    private final class Entry implements IBinder.DeathRecipient {
        final IBinder token;
        final int uid;
        Entry(IBinder token, int uid) { this.token = token; this.uid = uid; }
        @Override public void binderDied() { remove(this); }
    }
}
