package cc.wechat.observatory.files;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Exact 8.0.79/3200 bindings verified against native file sends. No alternative sender. */
public final class WeChat8079FileAdapter {
    private final ClassLoader loader;
    private final Class<?> parameters, controller, vfs, attachment, stores, migration;
    private final ConcurrentHashMap<Object, Trace> traces = new ConcurrentHashMap<>();

    public WeChat8079FileAdapter(ClassLoader loader) throws Exception {
        this.loader = loader;
        parameters = find("gu.o0"); controller = find("rt5.w");
        vfs = find("com.tencent.mm.vfs.z6"); attachment = find("com.tencent.mm.pluginsdk.model.app.d");
        stores = find("com.tencent.mm.pluginsdk.model.app.v5"); migration = find("ox0.t");
        parameters.getConstructor(String.class, String.class);
        require(parameters.getDeclaredMethod("n", String.class), void.class);
        require(find("rt5.v").getDeclaredMethod("c", parameters), void.class);
        require(vfs.getDeclaredMethod("E", String.class), InputStream.class);
        require(find("im.c8").getDeclaredMethod("M0"), int.class);
        require(find("im.c8").getDeclaredMethod("z0"), int.class);
        Class<?> task = find("gu.m0");
        if (task.getDeclaredField("o").getType() != parameters) throw new NoSuchFieldException("file task parameters changed");
        Method event = task.getDeclaredMethod("h"); require(event, find("qq3.f0"));
        XposedBridge.hookMethod(event, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable() || call.getResult() == null) return;
                try {
                    Object params = XposedHelpers.getObjectField(call.thisObject, "o");
                    Trace trace = traces.get(params);
                    if (trace == null) return;
                    Object snapshot = call.getResult();
                    Object idTalker = XposedHelpers.getObjectField(snapshot, "g");
                    if (idTalker != null) {
                        long id = XposedHelpers.getLongField(idTalker, "d");
                        String talker = String.valueOf(XposedHelpers.getObjectField(idTalker, "e"));
                        if (id > 0 && trace.recipient.equals(talker)) {
                            trace.messageId = id;
                            trace.observer.observed(id, false, false);
                        }
                    }
                    Object phase = XposedHelpers.getObjectField(snapshot, "a");
                    if (phase instanceof Enum && "Finish".equals(((Enum<?>)phase).name())) {
                        boolean ok = true;
                        for (String name : new String[]{"m", "n", "o", "p", "J"}) {
                            Object result = XposedHelpers.getObjectField(params, name);
                            ok &= result != null && XposedHelpers.getIntField(result, "a") == 0;
                        }
                        trace.phasesOk = ok;
                        trace.finished = true;
                        trace.observer.observed(trace.messageId, true, ok);
                        traces.remove(params);
                    }
                } catch (Throwable failure) {
                    // Observation must never change native behavior or authorize a second submission.
                }
            }
        });
    }

    public interface Observer { void observed(long messageId, boolean finished, boolean phasesOk) throws Exception; }

    /** Caller serializes this main-thread call and performs fresh identity/config checks first. */
    public Trace submit(FileJob job, File source, Observer observer) throws Exception {
        Object params = XposedHelpers.newInstance(parameters, job.recipient, source.getAbsolutePath());
        XposedHelpers.callMethod(params, "n", job.name);
        if (!job.recipient.equals(XposedHelpers.getObjectField(params, "q"))
                || !source.getAbsolutePath().equals(XposedHelpers.getObjectField(params, "r"))
                || !job.name.equals(XposedHelpers.getObjectField(params, "s"))
                || XposedHelpers.getBooleanField(params, "x")) throw new IllegalStateException("Native file parameter binding changed");
        Trace trace = new Trace(job.recipient, observer);
        if (traces.size() >= 8) throw new IllegalStateException("Unfinished native file tasks fill the observation limit");
        traces.put(params, trace);
        // Keep the observation after an exception: scheduling could already have happened.
        XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(controller, "a"), "c", params);
        return trace;
    }

    /** Read-only native storage lookup. Never call Z1/Mj: they may migrate or rename files. */
    public Proof inspect(FileJob job, long messageId) throws Exception {
        if (messageId <= 0) return Proof.pending();
        Object messages = XposedHelpers.getStaticObjectField(find("ox0.k0"), "d1");
        Object message = XposedHelpers.callMethod(messages, "k", job.recipient, messageId);
        if (message == null) return Proof.pending();
        long actualId = ((Number)XposedHelpers.callMethod(message, "getMsgId")).longValue();
        String talker = String.valueOf(XposedHelpers.callMethod(message, "N0"));
        long serverId = ((Number)XposedHelpers.callMethod(message, "H0")).longValue();
        int status = ((Number)XposedHelpers.callMethod(message, "M0")).intValue();
        int outgoing = ((Number)XposedHelpers.callMethod(message, "z0")).intValue();
        if (actualId != messageId || outgoing != 1 || !job.recipient.equals(talker)) return Proof.pending();
        Object record = XposedHelpers.newInstance(attachment);
        XposedHelpers.setLongField(record, "field_msgInfoId", messageId);
        XposedHelpers.setObjectField(record, "field_msgInfoTalker", job.recipient);
        Object storage = XposedHelpers.callStaticMethod(stores, "yj");
        boolean migrated = Boolean.TRUE.equals(XposedHelpers.callMethod(
                XposedHelpers.getStaticObjectField(migration, "c1"), "a", "appattach"));
        boolean found = Boolean.TRUE.equals(XposedHelpers.callMethod(storage, "get", record,
                migrated ? new String[]{"msgInfoId", "msgInfoTalker"} : new String[]{"msgInfoId"}));
        if (!found) return Proof.pending();
        long state = XposedHelpers.getLongField(record, "field_status");
        long total = XposedHelpers.getLongField(record, "field_totalLen");
        long offset = XposedHelpers.getLongField(record, "field_offset");
        if (status == 5 && state == 198) return new Proof(false, true);
        if (status != 2 || serverId <= 0 || state != 199 || total != job.size || offset != total
                || !XposedHelpers.getBooleanField(record, "field_isUpload")) return Proof.pending();
        String path = String.valueOf(XposedHelpers.getObjectField(record, "field_fileFullPath"));
        boolean matches;
        try (InputStream input = (InputStream)XposedHelpers.callStaticMethod(vfs, "E", path)) {
            matches = job.sha256.equals(hash(input, job.size));
        }
        return new Proof(matches, false);
    }

    public static String hash(InputStream input, long expectedSize) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384]; int count; long total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > expectedSize || total > FileJob.MAX_BYTES) throw new java.io.IOException("Attachment exceeds expected size");
            digest.update(buffer, 0, count);
        }
        if (total != expectedSize) throw new java.io.IOException("Attachment size mismatch");
        return FileJob.hex(digest.digest());
    }

    private Class<?> find(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static void require(Method method, Class<?> returns) throws NoSuchMethodException {
        if (method.getReturnType() != returns) throw new NoSuchMethodException("Native file return type changed");
    }
    public static final class Trace {
        final String recipient; final Observer observer;
        public volatile long messageId;
        public volatile boolean finished, phasesOk;
        Trace(String recipient, Observer observer) { this.recipient = recipient; this.observer = observer; }
    }
    public static final class Proof {
        public final boolean sent, failed;
        public Proof(boolean sent, boolean failed) { this.sent = sent; this.failed = failed; }
        static Proof pending() { return new Proof(false, false); }
    }
}
