package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import dev.slang.intellij.settings.SlangProjectSettings;
import dev.slang.intellij.settings.SlangServerSource;
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

    @Test
    public void bundledSelectionIgnoresManualPathAndEveryExternalEnvironmentSource() throws Exception {
        Path root = temporaryFolder.newFolder("plugin").toPath();
        SlangBundledRuntime bundle = SlangBundledRuntimeTest.createBundle(root);
        Path external = createExecutable(temporaryFolder.newFolder("official").toPath());
        SlangServerLocator locator = new SlangServerLocator(Map.of("SLANGD_PATH", external.toString()), bundle);
        assertEquals(bundle.resolveExecutable(), locator.resolve(project(root), SlangServerSource.BUNDLED,
                external.toString(), false));
    }

    @Test
    public void explicitExternalChoiceBypassesUnavailableBundleAndRetainsDiscovery() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Path external = createExecutable(temporaryFolder.newFolder("official").toPath());
        var unavailable = new SlangBundledRuntime(() -> { fail("must not inspect bundle"); return null; }, true);
        var locator = new SlangServerLocator(Map.of("SLANGD_PATH", external.toString()), unavailable);
        assertEquals(external, locator.resolve(project(root), SlangServerSource.EXTERNAL, "ignored-saved-path", true));
        assertEquals(external, locator.resolve(project(root), SlangServerSource.EXTERNAL, external.toString(), false));
    }

    @Test
    public void brokenBundleDoesNotSilentlyFallBackToOfficialServer() throws Exception {
        Path root = temporaryFolder.newFolder("missing-bundle").toPath();
        Path external = createExecutable(temporaryFolder.newFolder("official").toPath());
        var locator = new SlangServerLocator(Map.of("SLANGD_PATH", external.toString()),
                new SlangBundledRuntime(() -> root, true));
        try {
            locator.resolve(project(root), SlangServerSource.BUNDLED, external.toString(), true);
            fail("Expected missing bundle error");
        } catch (ExecutionException exception) {
            assertTrue(exception.getMessage().contains("Bundled slangd is incomplete"));
        }
    }

    @Test
    public void projectResolutionUsesPersistedSourceForBothServerAndBuiltinModuleCallers() throws Exception {
        Path root = temporaryFolder.newFolder("plugin").toPath();
        var bundle = SlangBundledRuntimeTest.createBundle(root);
        Path external = createExecutable(temporaryFolder.newFolder("official").toPath());
        var settings = new SlangProjectSettings();
        settings.setSlangdPath(external.toString());
        settings.setAutoDetectSlangd(false);
        var locator = new SlangServerLocator(Map.of(), bundle);
        Project project = project(root, settings);
        settings.setServerSource(SlangServerSource.BUNDLED);
        assertEquals(bundle.resolveExecutable(), locator.resolve(project));
        settings.setServerSource(SlangServerSource.EXTERNAL);
        assertEquals(external, locator.resolve(project));
    }

    private static Path createExecutable(Path directory) throws IOException {
        Files.createDirectories(directory);
        String name = SystemInfo.isWindows ? "slangd.exe" : "slangd";
        return Files.createFile(directory.resolve(name));
    }

    private static Project project(Path basePath) {
        return project(basePath, new SlangProjectSettings());
    }

    private static Project project(Path basePath, SlangProjectSettings settings) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getBasePath" -> basePath.toString();
                    case "getService" -> arguments[0] == SlangProjectSettings.class ? settings : null;
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
