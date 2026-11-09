package cc.wechat.observatory.wechat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Shared send flow for bytecode-verified WeChat 8.0.79/3200 and 8.0.80/3220 arm64. The caller must select this
 * adapter by the exact version profile. No legacy send path is used after this
 * adapter starts preparing a message, because preparation inserts a local row.
 */
public final class WeChat8079SendAdapter {
    private static volatile ClassLoader cachedLoader;
    private static volatile Bindings cachedBindings;
    private static long cachedVersionCode;

    private WeChat8079SendAdapter() { }

    /** Null means ready. This observes initialization; it never installs services. */
    public static String readiness(ClassLoader loader, WeChatVersionProfile profile) throws Exception {
        return bindings(loader, profile).readiness();
    }

    public static PreparedSend prepare(ClassLoader loader, WeChatVersionProfile profile,
                                       String recipient, String text) throws Exception {
        return bindings(loader, profile).prepare(recipient, text);
    }

    public static String factoryClass(WeChatVersionProfile profile) {
        if (profile.is8079()) return "f61.t1";
        if (profile.is8080()) return "q61.t1";
        throw new UnsupportedOperationException("No verified text send binding for " + profile.versionName);
    }

    private static synchronized Bindings bindings(final ClassLoader loader, WeChatVersionProfile profile)
            throws Exception {
        if (loader == null) {
            throw new IllegalStateException("WeChat classLoader is unavailable");
        }
        if (cachedBindings == null || cachedLoader != loader || cachedVersionCode != profile.versionCode) {
            Bindings resolved = new Bindings(new ClassLookup() {
                @Override public Class<?> find(String name) throws ClassNotFoundException {
                    return Class.forName(name, false, loader);
                }
            }, profile);
            cachedLoader = loader;
            cachedVersionCode = profile.versionCode;
            cachedBindings = resolved;
        }
        return cachedBindings;
    }

    public static final class PreparedSend {
        public final Object request;
        public final long chatRecordId;
        public final int msgType;

        PreparedSend(Object request, long chatRecordId, int msgType) {
            this.request = request;
            this.chatRecordId = chatRecordId;
            this.msgType = msgType;
        }
    }

    interface ClassLookup {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    /** Package access permits JVM fixtures to exercise the same reflective binding. */
    static final class Bindings {
        final Field registrySlot, kernelReady, factory, recipientField, textField,
                typeField, flags, scene, localId, source;
        final Method queueReady, create, recipientSetter, textSetter, typeSetter,
                build, execute, resolveType;
        final Class<?> builderClass, requestClass;
        final String versionName;

        Bindings(ClassLookup lookup, WeChatVersionProfile profile) throws Exception {
            String factoryName = factoryClass(profile); // Reject unsupported versions before resolving names.
            boolean v8080 = profile.is8080();
            versionName = profile.versionName;
            Class<?> registry = lookup.find(v8080 ? "us.g" : "qs.g");
            Class<?> slotType = Array.newInstance(lookup.find(v8080 ? "us.h" : "qs.h"), 0).getClass();
            registrySlot = field(registry, "a", slotType, true);
            kernelReady = field(lookup.find(v8080 ? "fi5.n0" : "ni5.n0"), "f", boolean[].class, true);
            Class<?> factoryClass = lookup.find(factoryName);
            factory = field(factoryClass, "a", lookup.find(v8080 ? "q61.p1" : "f61.p1"), true);
            builderClass = lookup.find(v8080 ? "q61.s1" : "f61.s1");
            requestClass = lookup.find(v8080 ? "q61.o1" : "f61.o1");
            queueReady = method(lookup.find("com.tencent.mm.modelbase.a3"), "c", boolean.class, true);
            create = method(factoryClass, "a", builderClass, true, String.class);
            recipientSetter = method(builderClass, v8080 ? "g" : "h", builderClass, false, String.class);
            textSetter = method(builderClass, "e", builderClass, false, String.class);
            typeSetter = method(builderClass, v8080 ? "h" : "i", builderClass, false, int.class);
            recipientField = field(builderClass, "b", String.class, false);
            textField = field(builderClass, "d", String.class, false);
            typeField = field(builderClass, "e", int.class, false);
            flags = field(builderClass, "f", int.class, false);
            source = field(builderClass, "i", int.class, false);
            build = method(builderClass, "a", requestClass, false);
            execute = method(requestClass, "a", boolean.class, false);
            scene = field(requestClass, "a", lookup.find("com.tencent.mm.modelbase.n1"), false);
            localId = field(requestClass, "b", long.class, false);
            resolveType = method(lookup.find(v8080 ? "w41.e2" : "l41.d2"), "C", int.class, true, String.class);
        }

        String readiness() throws Exception {
            Object slots = registrySlot.get(null);
            if (slots == null || Array.getLength(slots) == 0 || Array.get(slots, 0) == null) {
                return versionName + " extension registry pending";
            }
            boolean[] initialized = (boolean[]) kernelReady.get(null);
            if (initialized == null || initialized.length == 0 || !initialized[0]) {
                return versionName + " service manager pending";
            }
            if (factory.get(null) == null) {
                return versionName + " send factory pending";
            }
            if (!Boolean.TRUE.equals(queueReady.invoke(null))) {
                return versionName + " network queue pending";
            }
            return null;
        }

        PreparedSend prepare(String recipient, String text) throws Exception {
            if (recipient == null || recipient.trim().isEmpty() || text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("recipient and text are required");
            }
            String pending = readiness();
            if (pending != null) {
                throw new IllegalStateException(pending);
            }
            int type = (Integer) resolveType.invoke(null, recipient);
            if (type <= 0) {
                throw new IllegalStateException(versionName + " returned an invalid text message type");
            }
            Object builder = create.invoke(null, recipient);
            if (!builderClass.isInstance(builder)) {
                throw new IllegalStateException(versionName + " send factory returned no builder");
            }
            // Mirrors each version's SendMsgEvent listener. Recipient/type setters
            // changed in 8.0.80; verify the destination fields before creating a row.
            recipientSetter.invoke(builder, recipient);
            textSetter.invoke(builder, text);
            typeSetter.invoke(builder, type);
            flags.setInt(builder, 0);
            source.setInt(builder, 4);
            // Check parameter meaning before build() creates a local message.
            if (!recipient.equals(recipientField.get(builder))
                    || !text.equals(textField.get(builder)) || typeField.getInt(builder) != type) {
                throw new IllegalStateException(versionName + " send builder parameter binding changed");
            }
            Object request = build.invoke(builder);
            if (!requestClass.isInstance(request) || scene.get(request) == null) {
                throw new IllegalStateException(versionName + " send builder returned no network request");
            }
            long id = localId.getLong(request);
            if (id <= 0L) {
                throw new IllegalStateException(versionName + " send builder returned an invalid local message id: " + id);
            }
            return new PreparedSend(request, id, type);
        }
    }

    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic) {
            throw new NoSuchFieldException(owner.getName() + "." + name + " has an unexpected type or static modifier");
        }
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> returns, boolean isStatic,
                                  Class<?>... arguments) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " has an unexpected return type or static modifier");
        }
        method.setAccessible(true);
        return method;
    }
}
