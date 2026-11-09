package cc.wechat.observatory.wechat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.util.Arrays;
import java.util.Collection;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class WeChat8079SendAdapterTest {
    @Parameterized.Parameters(name = "WeChat {0}") public static Collection<Object[]> versions() {
        return Arrays.asList(new Object[][]{{"8.0.79", 3200L}, {"8.0.80", 3220L}});
    }
    @Parameterized.Parameter(0) public String version;
    @Parameterized.Parameter(1) public long versionCode;
    private WeChatVersionProfile profile;
    private String kernelClass;

    private Map<String, Class<?>> classes;

    @Before public void reset() {
        profile = WeChatVersionProfile.forVersion(version, versionCode);
        boolean v8080 = profile.is8080();
        Builder.v8080 = v8080;
        kernelClass = v8080 ? "fi5.n0" : "ni5.n0";
        classes = new HashMap<>();
        classes.put(v8080 ? "us.g" : "qs.g", Registry.class);
        classes.put(v8080 ? "us.h" : "qs.h", Slot.class);
        classes.put(kernelClass, Kernel.class);
        classes.put(v8080 ? "q61.t1" : "f61.t1", Factory.class);
        classes.put(v8080 ? "q61.p1" : "f61.p1", Session.class);
        classes.put(v8080 ? "q61.s1" : "f61.s1", Builder.class);
        classes.put(v8080 ? "q61.o1" : "f61.o1", Request.class);
        classes.put("com.tencent.mm.modelbase.a3", Queue.class);
        classes.put("com.tencent.mm.modelbase.n1", Scene.class);
        classes.put(v8080 ? "w41.e2" : "l41.d2", Types.class);
        Registry.a = new Slot[]{new Slot()};
        Kernel.f = new boolean[]{true};
        Factory.a = new Session();
        Factory.creates = 0;
        Builder.builds = 0;
        Builder.last = null;
        Builder.wrongRecipientSetter = false;
        Builder.invalidRequest = false;
        Queue.ready = true;
    }

    private WeChat8079SendAdapter.Bindings bind() throws Exception {
        return new WeChat8079SendAdapter.Bindings(name -> {
            Class<?> result = classes.get(name);
            if (result == null) throw new ClassNotFoundException(name);
            return result;
        }, profile);
    }

    @Test public void usesVerifiedRecipientSetterAndPreservesMessageParameters() throws Exception {
        WeChat8079SendAdapter.Bindings bindings = bind();
        WeChat8079SendAdapter.PreparedSend prepared = bindings.prepare("alice@openim", "hello\n世界");
        assertEquals("alice@openim", Builder.last.b);
        assertEquals("hello\n世界", Builder.last.d);
        assertEquals(11, Builder.last.e);
        assertEquals(0, Builder.last.f);
        assertEquals(4, Builder.last.i);
        assertNull("Recipient must not be written into metadata", Builder.last.q);
        assertEquals(42L, prepared.chatRecordId);
        assertEquals(11, prepared.msgType);
        assertEquals(0, ((Request) prepared.request).executions);
        assertEquals(1, Builder.builds);
    }

    @Test public void waitsForNativeInitializationWithoutCreatingOrInstallingAnything() throws Exception {
        WeChat8079SendAdapter.Bindings bindings = bind();
        Registry.a[0] = null;
        assertEquals(version + " extension registry pending", bindings.readiness());
        Registry.a[0] = new Slot();
        Kernel.f[0] = false;
        assertEquals(version + " service manager pending", bindings.readiness());
        Kernel.f[0] = true;
        Factory.a = null;
        assertEquals(version + " send factory pending", bindings.readiness());
        try {
            bindings.prepare("alice", "hello");
            fail("must wait for WeChat's factory");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("factory pending"));
        }
        assertNull(Factory.a);
        assertEquals(0, Factory.creates);
        assertEquals(0, Builder.builds);
        Factory.a = new Session();
        Queue.ready = false;
        assertEquals(version + " network queue pending", bindings.readiness());
        Queue.ready = true;
        assertNull(bindings.readiness());
    }

    @Test public void rejectsSameNameCollisionWithWrongFieldType() throws Exception {
        classes.put(kernelClass, UnrelatedKernel.class);
        try {
            bind();
            fail("a matching field name is not enough");
        } catch (NoSuchFieldException expected) {
            assertTrue(expected.getMessage().contains("unexpected type"));
        }
    }

    @Test public void rejectsSameNameMethodWithWrongReturnType() throws Exception {
        classes.put("com.tencent.mm.modelbase.a3", UnrelatedQueue.class);
        try {
            bind();
            fail("a matching method name is not enough");
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage().contains("return type"));
        }
    }

    @Test public void checksSetterSemanticsBeforeCreatingLocalMessage() throws Exception {
        Builder.wrongRecipientSetter = true;
        try {
            bind().prepare("alice", "hello");
            fail("must reject a changed recipient setter");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("parameter binding changed"));
        }
        assertEquals(0, Builder.builds);
    }

    @Test public void rejectsMissingNetworkRequestAfterOnlyOnePreparation() throws Exception {
        Builder.invalidRequest = true;
        try {
            bind().prepare("alice", "hello");
            fail("must not acknowledge a request with no network scene");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no network request"));
        }
        assertEquals(1, Factory.creates);
        assertEquals(1, Builder.builds);
    }

    @Test public void busyQueueRetriesSamePreparedRequestWithoutCreatingDuplicateRows() throws Exception {
        WeChat8079SendAdapter.Bindings bindings = bind();
        WeChat8079SendAdapter.PreparedSend prepared = bindings.prepare("alice", "hello");
        boolean accepted = QueueSubmissionRetrier.awaitAccepted(
                () -> (Boolean) bindings.execute.invoke(prepared.request),
                delay -> { }, 3, 0L);
        assertTrue(accepted);
        assertEquals(2, ((Request) prepared.request).executions);
        assertEquals(1, Builder.builds);
        assertEquals(1, Factory.creates);
    }

    public static class Slot { }
    public static class Registry { public static Slot[] a; }
    public static class Kernel { public static boolean[] f; }
    public static class Session { }
    public static class Scene { }
    public static class Queue {
        static boolean ready;
        public static boolean c() { return ready; }
    }
    public static class Types {
        public static int C(String recipient) { return recipient.endsWith("@openim") ? 11 : 1; }
    }
    public static class Factory {
        public static Session a;
        static int creates;
        public static Builder a(String recipient) {
            creates++;
            return new Builder();
        }
    }
    public static class Builder {
        static int builds;
        static Builder last;
        static boolean wrongRecipientSetter, invalidRequest, v8080;
        public String b, d, q;
        public int e, f, i;
        public Builder h(String value) {
            if (v8080) throw new AssertionError("8.0.80 has no h(String) recipient setter");
            if (wrongRecipientSetter) q = value; else b = value;
            return this;
        }
        public Builder g(String value) {
            if (!v8080 || wrongRecipientSetter) q = value; else b = value;
            return this;
        }
        public Builder h(int value) {
            if (!v8080) throw new AssertionError("8.0.79 has no h(int) type setter");
            e = value; return this;
        }
        public Builder e(String value) { d = value; return this; }
        public Builder i(int value) {
            if (v8080) throw new AssertionError("8.0.80 has no i(int) type setter");
            e = value; return this;
        }
        public Request a() {
            builds++;
            last = this;
            Request result = new Request();
            result.a = invalidRequest ? null : new Scene();
            result.b = 42L;
            return result;
        }
    }
    public static class Request {
        public Scene a;
        public long b;
        int executions;
        public boolean a() { return ++executions > 1; }
    }
    public static class UnrelatedKernel { public static int f; }
    public static class UnrelatedQueue { public static String c() { return "wrong"; } }
}
