package dev.slang.intellij.synth;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlangSyntheticModuleFileProviderTest {
    @Test
    public void restartUsesTheNewSessionExecutableAndInvalidatesCachedModules() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        SlangSyntheticModuleFileProvider provider = new SlangSyntheticModuleFileProvider(
                project(),
                (executable, module) -> {
                    loads.incrementAndGet();
                    return new LightVirtualFile(module + ".slang", executable.getParent().getFileName().toString());
                }
        );
        Path firstExecutable = Path.of("runtime-a", "slangd.exe");
        Path secondExecutable = Path.of("runtime-b", "slangd.exe");

        provider.activateSession(firstExecutable);
        VirtualFile first = findInBackground(provider, "slang-synth://core");
        assertNotNull(first);
        assertEquals("runtime-a", text(first));
        assertEquals("slang-synth://core", provider.getUri(first));

        provider.activateSession(secondExecutable);
        assertNull(provider.getUri(first));
        VirtualFile second = findInBackground(provider, "slang-synth://core");
        assertNotNull(second);
        assertNotSame(first, second);
        assertEquals("runtime-b", text(second));

        // A new server process is a new session even when its executable path is unchanged.
        provider.activateSession(secondExecutable);
        VirtualFile restarted = findInBackground(provider, "slang-synth://core");
        assertNotNull(restarted);
        assertNotSame(second, restarted);
        assertEquals("runtime-b", text(restarted));
        assertEquals(3, loads.get());
    }

    @Test
    public void resultFromPreviousSessionCannotRepopulateTheCurrentCache() throws Exception {
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLoad = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        SlangSyntheticModuleFileProvider provider = new SlangSyntheticModuleFileProvider(
                project(),
                (executable, module) -> {
                    loads.incrementAndGet();
                    String runtime = executable.getParent().getFileName().toString();
                    if (runtime.equals("runtime-a")) {
                        firstLoadStarted.countDown();
                        try {
                            assertTrue(releaseFirstLoad.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }
                    return new LightVirtualFile(module + ".slang", runtime);
                }
        );

        provider.activateSession(Path.of("runtime-a", "slangd.exe"));
        CompletableFuture<VirtualFile> stale = CompletableFuture.supplyAsync(
                () -> provider.findFileByUri("slang-synth://core")
        );
        assertTrue(firstLoadStarted.await(5, TimeUnit.SECONDS));

        provider.activateSession(Path.of("runtime-b", "slangd.exe"));
        releaseFirstLoad.countDown();
        assertNull(stale.get(5, TimeUnit.SECONDS));

        VirtualFile current = findInBackground(provider, "slang-synth://core");
        assertNotNull(current);
        assertEquals("runtime-b", text(current));
        assertEquals(2, loads.get());
    }

    private static VirtualFile findInBackground(
            SlangSyntheticModuleFileProvider provider,
            String uri
    ) throws Exception {
        return CompletableFuture.supplyAsync(() -> provider.findFileByUri(uri)).get(5, TimeUnit.SECONDS);
    }

    private static String text(VirtualFile file) throws Exception {
        return ((LightVirtualFile) file).getContent().toString();
    }

    private static Project project() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getName" -> "test-project";
                    case "toString" -> "TestProject";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }
}
