package cc.wechat.observatory.files;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native type-3 image pipeline verified against WeChat 8.0.80/3220, not a file attachment. */
public final class WeChat8080ImageAdapter {
    private final ClassLoader loader;
    private final Class<?> parameters, crossParameters, services, serviceType, imageStores;
    private final ConcurrentHashMap<Object, WeChat8079FileAdapter.Trace> traces = new ConcurrentHashMap<>();

    public WeChat8080ImageAdapter(ClassLoader loader, String name, long code) throws Exception {
        if (!"8.0.80".equals(name) || code != 3220)
            throw new UnsupportedOperationException("Unverified WeChat image binding");
        this.loader = loader;
        parameters = find("ta0.g"); crossParameters = find("ma0.i0");
        services = find("fi5.n0"); serviceType = find("pt.d1"); imageStores = find("g61.a1");
        parameters.getConstructor(String.class, int.class, String.class, String.class, crossParameters);
        crossParameters.getConstructor();
        require(find("xa0.w").getDeclaredMethod("Uj", parameters), find("kotlinx.coroutines.flow.j"));
        require(imageStores.getDeclaredMethod("Hj"), find("g61.k0"));
        require(find("g61.k0").getDeclaredMethod("z3", find("com.tencent.mm.storage.f9")), find("g61.a0"));
        require(find("g61.a0").getDeclaredMethod("l"), boolean.class);
        Class<?> eventType = find("ta0.c");
        if (eventType.getDeclaredField("a").getType() != parameters
                || eventType.getDeclaredField("c").getType() != long.class
                || eventType.getDeclaredField("i").getType() != find("ta0.h"))
            throw new NoSuchFieldException("Native image event changed");
        Method event = find("gb0.r").getDeclaredMethod("P7", eventType);
        require(event, void.class);
        XposedBridge.hookMethod(event, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable()) return;
                try {
                    Object snapshot = call.args[0];
                    Object params = XposedHelpers.getObjectField(snapshot, "a");
                    WeChat8079FileAdapter.Trace trace = traces.get(params);
                    if (trace == null || !trace.recipient.equals(XposedHelpers.getObjectField(params, "d"))) return;
                    long id = XposedHelpers.getLongField(snapshot, "c");
                    if (id > 0) {
                        trace.messageId = id;
                        trace.observer.observed(id, false, false);
                    }
                    Object phase = XposedHelpers.getObjectField(snapshot, "b");
                    if (phase instanceof Enum && "Finish".equals(((Enum<?>) phase).name())) {
                        Object result = XposedHelpers.getObjectField(snapshot, "i");
                        Object type = result == null ? null : XposedHelpers.getObjectField(result, "a");
                        boolean ok = type instanceof Enum && "Success".equals(((Enum<?>) type).name());
                        trace.phasesOk = ok; trace.finished = true;
                        trace.observer.observed(trace.messageId, true, ok);
                        traces.remove(params);
                    }
                } catch (Throwable ignored) { /* Observation never changes native behavior. */ }
            }
        });
    }

    public void submit(FileJob job, File source, WeChat8079FileAdapter.Observer observer) throws Exception {
        if (!job.isImage()) throw new IllegalArgumentException("Not an image job");
        Object cross = XposedHelpers.newInstance(crossParameters);
        XposedHelpers.setIntField(cross, "a", 3); // Native gallery source, normal compressed image.
        Object params = XposedHelpers.newInstance(parameters, source.getAbsolutePath(), 0, job.owner, job.recipient, cross);
        if (!source.getAbsolutePath().equals(XposedHelpers.getObjectField(params, "a"))
                || !job.owner.equals(XposedHelpers.getObjectField(params, "c"))
                || !job.recipient.equals(XposedHelpers.getObjectField(params, "d")))
            throw new IllegalStateException("Native image parameter binding changed");
        if (traces.size() >= 8) throw new IllegalStateException("Unfinished image observation limit reached");
        traces.put(params, new WeChat8079FileAdapter.Trace(job.recipient, observer));
        Object service = XposedHelpers.callStaticMethod(services, "c", serviceType);
        // Uj schedules the native sequence; do not collect a second flow or retry this call.
        XposedHelpers.callMethod(service, "Uj", params);
    }

    /** Confirm the exact observed native message and its image upload record after reconnect/restart. */
    public WeChat8079FileAdapter.Proof inspect(FileJob job, long messageId) throws Exception {
        if (messageId <= 0) return WeChat8079FileAdapter.Proof.pending();
        Object messages = XposedHelpers.getStaticObjectField(find("yx0.k0"), "A1");
        Object message = XposedHelpers.callMethod(messages, "k", job.recipient, messageId);
        if (message == null || number(message, "getMsgId") != messageId
                || number(message, "A0") != 1 || number(message, "getType") != 3
                || !job.recipient.equals(XposedHelpers.callMethod(message, "O0")))
            return WeChat8079FileAdapter.Proof.pending();
        long serverId = number(message, "G0"), status = number(message, "N0");
        Object storage = XposedHelpers.callStaticMethod(imageStores, "Hj");
        Object image = XposedHelpers.callMethod(storage, "z3", message);
        if (image == null || XposedHelpers.getLongField(image, "o") != messageId)
            return WeChat8079FileAdapter.Proof.pending();
        String talker = String.valueOf(XposedHelpers.getObjectField(image, "m"));
        if (!talker.isEmpty() && !"null".equals(talker) && !job.recipient.equals(talker))
            return WeChat8079FileAdapter.Proof.pending();
        int imageStatus = XposedHelpers.getIntField(image, "p");
        if (status == 5 && imageStatus == -1) return new WeChat8079FileAdapter.Proof(false, true);
        boolean complete = Boolean.TRUE.equals(XposedHelpers.callMethod(image, "l"));
        return new WeChat8079FileAdapter.Proof(status == 2 && serverId > 0 && complete
                && XposedHelpers.getLongField(image, "b") == serverId && imageStatus >= 0, false);
    }

    private static long number(Object value, String method) {
        return ((Number) XposedHelpers.callMethod(value, method)).longValue();
    }
    private Class<?> find(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static void require(Method method, Class<?> returns) throws NoSuchMethodException {
        if (method.getReturnType() != returns) throw new NoSuchMethodException("Native image return type changed");
    }
}
