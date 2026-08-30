package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.project.Project;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SlangServerLocatorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bundledRuntimeIsTheDefault() throws Exception {
        Path bundled = createExecutable(temporaryFolder.newFolder("bundled").toPath());
        SlangServerLocator locator = new SlangServerLocator(() -> bundled);

        Path resolved = locator.resolve(project(temporaryFolder.newFolder("project").toPath()), "", false);

        assertEquals(bundled.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void explicitExternalRelativePathOverridesBundle() throws Exception {
        Path projectRoot = temporaryFolder.newFolder("project").toPath();
        Path external = createExecutable(projectRoot.resolve("tools"));
        Path bundled = createExecutable(temporaryFolder.newFolder("bundled").toPath());
        SlangServerLocator locator = new SlangServerLocator(() -> bundled);

        Path resolved = locator.resolve(project(projectRoot), projectRoot.relativize(external).toString(), true);

        assertEquals(external.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void legacyExternalDirectoryResolvesItsSlangdExecutable() throws Exception {
        Path projectRoot = temporaryFolder.newFolder("project").toPath();
        Path tools = projectRoot.resolve("tools");
        Path external = createExecutable(tools);
        SlangServerLocator locator = new SlangServerLocator(
                () -> temporaryFolder.getRoot().toPath().resolve("unused-bundled-slangd.exe")
        );

        Path resolved = locator.resolve(project(projectRoot), projectRoot.relativize(tools).toString(), true);

        assertEquals(external.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void storedExternalPathIsIgnoredUntilOverrideIsEnabled() throws Exception {
        Path projectRoot = temporaryFolder.newFolder("project").toPath();
        Path external = createExecutable(projectRoot.resolve("tools"));
        Path bundled = createExecutable(temporaryFolder.newFolder("bundled").toPath());
        SlangServerLocator locator = new SlangServerLocator(() -> bundled);

        Path resolved = locator.resolve(project(projectRoot), external.toString(), false);

        assertEquals(bundled.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void reportsMissingExternalOverrideClearly() throws IOException {
        Project project = project(temporaryFolder.newFolder("project").toPath());
        SlangServerLocator locator = new SlangServerLocator(
                () -> temporaryFolder.getRoot().toPath().resolve("bundled-slangd.exe")
        );

        try {
            locator.resolve(project, "", true);
            fail("Expected ExecutionException");
        } catch (ExecutionException exception) {
            assertTrue(exception.getMessage().contains("External slangd override is enabled"));
        }
    }

    private static Path createExecutable(Path directory) throws IOException {
        Files.createDirectories(directory);
        return Files.createFile(directory.resolve("slangd.exe"));
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
