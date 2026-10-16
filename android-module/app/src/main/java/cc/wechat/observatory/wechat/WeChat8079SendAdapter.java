package cc.wechat.observatory.wechat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Bindings verified against WeChat 8.0.79/3200 arm64. The caller must select this
 * adapter by the exact version profile. No legacy send path is used after this
 * adapter starts preparing a message, because preparation inserts a local row.
 */
public final class WeChat8079SendAdapter {
    private static volatile ClassLoader cachedLoader;
    private static volatile Bindings cachedBindings;

    private WeChat8079SendAdapter() { }

    /** Null means ready. This observes initialization; it never installs services. */
    public static String readiness(ClassLoader loader) throws Exception {
        return bindings(loader).readiness();
    }

    public static PreparedSend prepare(ClassLoader loader, String recipient, String text) throws Exception {
        return bindings(loader).prepare(recipient, text);
    }

    private static synchronized Bindings bindings(final ClassLoader loader) throws Exception {
        if (loader == null) {
            throw new IllegalStateException("WeChat classLoader is unavailable");
        }
        if (cachedBindings == null || cachedLoader != loader) {
            Bindings resolved = new Bindings(new ClassLookup() {
                @Override public Class<?> find(String name) throws ClassNotFoundException {
                    return Class.forName(name, false, loader);
                }
            });
            cachedLoader = loader;
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

        Bindings(ClassLookup lookup) throws Exception {
            Class<?> registry = lookup.find("qs.g");
            Class<?> slotType = Array.newInstance(lookup.find("qs.h"), 0).getClass();
            registrySlot = field(registry, "a", slotType, true);
            kernelReady = field(lookup.find("ni5.n0"), "f", boolean[].class, true);
            Class<?> factoryClass = lookup.find("f61.t1");
            factory = field(factoryClass, "a", lookup.find("f61.p1"), true);
            builderClass = lookup.find("f61.s1");
            requestClass = lookup.find("f61.o1");
            queueReady = method(lookup.find("com.tencent.mm.modelbase.a3"), "c", boolean.class, true);
            create = method(factoryClass, "a", builderClass, true, String.class);
            recipientSetter = method(builderClass, "h", builderClass, false, String.class);
            textSetter = method(builderClass, "e", builderClass, false, String.class);
            typeSetter = method(builderClass, "i", builderClass, false, int.class);
            recipientField = field(builderClass, "b", String.class, false);
            textField = field(builderClass, "d", String.class, false);
            typeField = field(builderClass, "e", int.class, false);
            flags = field(builderClass, "f", int.class, false);
            source = field(builderClass, "i", int.class, false);
            build = method(builderClass, "a", requestClass, false);
            execute = method(requestClass, "a", boolean.class, false);
            scene = field(requestClass, "a", lookup.find("com.tencent.mm.modelbase.n1"), false);
            localId = field(requestClass, "b", long.class, false);
            resolveType = method(lookup.find("l41.d2"), "C", int.class, true, String.class);
        }

        String readiness() throws Exception {
            Object slots = registrySlot.get(null);
            if (slots == null || Array.getLength(slots) == 0 || Array.get(slots, 0) == null) {
                return "8.0.79 extension registry pending";
            }
            boolean[] initialized = (boolean[]) kernelReady.get(null);
            if (initialized == null || initialized.length == 0 || !initialized[0]) {
                return "8.0.79 service manager pending";
            }
            if (factory.get(null) == null) {
                return "8.0.79 send factory pending";
            }
            if (!Boolean.TRUE.equals(queueReady.invoke(null))) {
                return "8.0.79 network queue pending";
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
                throw new IllegalStateException("8.0.79 returned an invalid text message type");
            }
            Object builder = create.invoke(null, recipient);
            if (!builderClass.isInstance(builder)) {
                throw new IllegalStateException("8.0.79 send factory returned no builder");
            }
            // Mirrors qt.u5 (SendMsgEvent listener). In this release g(String)
            // writes extra metadata q; h(String) is the recipient setter.
            recipientSetter.invoke(builder, recipient);
            textSetter.invoke(builder, text);
            typeSetter.invoke(builder, type);
            flags.setInt(builder, 0);
            source.setInt(builder, 4);
            // Check parameter meaning before build() creates a local message.
            if (!recipient.equals(recipientField.get(builder))
                    || !text.equals(textField.get(builder)) || typeField.getInt(builder) != type) {
                throw new IllegalStateException("8.0.79 send builder parameter binding changed");
            }
            Object request = build.invoke(builder);
            if (!requestClass.isInstance(request) || scene.get(request) == null) {
                throw new IllegalStateException("8.0.79 send builder returned no network request");
            }
            long id = localId.getLong(request);
            if (id <= 0L) {
                throw new IllegalStateException("8.0.79 send builder returned an invalid local message id: " + id);
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
