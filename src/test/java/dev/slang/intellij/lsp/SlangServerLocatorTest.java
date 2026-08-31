package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SlangServerLocatorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void configuredRelativePathWinsOverEnvironment() throws Exception {
        Path projectRoot = temporaryFolder.newFolder("project").toPath();
        Path configured = createExecutable(projectRoot.resolve("tools"));
        Path environmentExecutable = createExecutable(temporaryFolder.newFolder("env").toPath());
        Map<String, String> environment = Map.of("SLANGD_PATH", environmentExecutable.toString());

        SlangServerLocator locator = new SlangServerLocator(environment);
        Path resolved = locator.resolve(project(projectRoot), projectRoot.relativize(configured).toString(), true);

        assertEquals(configured.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void detectsVulkanSdkBinDirectory() throws Exception {
        Path sdkRoot = temporaryFolder.newFolder("VulkanSDK").toPath();
        Path expected = createExecutable(sdkRoot.resolve("Bin"));
        Map<String, String> environment = new HashMap<>();
        environment.put("VULKAN_SDK", sdkRoot.toString());

        Path resolved = new SlangServerLocator(environment)
                .resolve(project(temporaryFolder.getRoot().toPath()), "", true);

        assertEquals(expected.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void reportsDisabledAutoDetectionClearly() throws IOException {
        Project project = project(temporaryFolder.newFolder("project").toPath());
        try {
            new SlangServerLocator(Map.of()).resolve(project, "", false);
            fail("Expected ExecutionException");
        } catch (ExecutionException exception) {
            assertTrue(exception.getMessage().contains("auto-detection is disabled"));
        }
    }

    private static Path createExecutable(Path directory) throws IOException {
        Files.createDirectories(directory);
        String name = SystemInfo.isWindows ? "slangd.exe" : "slangd";
        return Files.createFile(directory.resolve(name));
    }

    private static Project project(Path basePath) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getBasePath" -> basePath.toString();
                    case "isDisposed" -> false;
                    case "getName" -> "test-project";
                    case "toString" -> "TestProject(" + basePath + ")";
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
