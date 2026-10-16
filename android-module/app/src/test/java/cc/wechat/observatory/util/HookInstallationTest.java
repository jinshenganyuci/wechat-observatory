package cc.wechat.observatory.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HookInstallationTest {
    @Test
    public void retriesFailureWithoutDuplicatingAlreadyInstalledLifecycleHooks() throws Throwable {
        HookInstallation hooks = new HookInstallation();
        AtomicInteger attaches = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        assertTrue(hooks.installOnce("attach", attaches::incrementAndGet));
        try {
            hooks.installOnce("wcdb", () -> {
                observations.incrementAndGet();
                throw new ClassNotFoundException("runtime dex is not available yet");
            });
            fail("expected the first WCDB installation to fail");
        } catch (ClassNotFoundException expected) {
            // A late application callback can retry after the runtime dex loads.
        }
        assertFalse(hooks.installOnce("attach", attaches::incrementAndGet));
        assertTrue(hooks.installOnce("wcdb", observations::incrementAndGet));
        assertFalse(hooks.installOnce("wcdb", observations::incrementAndGet));
        assertEquals(1, attaches.get());
        assertEquals(2, observations.get());
    }

    @Test
    public void concurrentLifecycleAndWorkerAttemptsInstallOnlyOnce() throws Exception {
        HookInstallation hooks = new HookInstallation();
        AtomicInteger installations = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("startup attempts were not released");
                    }
                    try {
                        return hooks.installOnce("wcdb", installations::incrementAndGet);
                    } catch (Throwable error) {
                        throw new AssertionError(error);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, installations.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void distinctRuntimeMethodsCanEachBeInstalled() throws Throwable {
        HookInstallation hooks = new HookInstallation();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(hooks.installOnce(String.class.getMethod("length"), calls::incrementAndGet));
        assertFalse(hooks.installOnce(String.class.getMethod("length"), calls::incrementAndGet));
        assertTrue(hooks.installOnce(StringBuilder.class.getMethod("length"), calls::incrementAndGet));
        assertEquals(2, calls.get());
    }
}
