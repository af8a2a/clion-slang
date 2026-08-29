package dev.slang.intellij.lsp;

import com.intellij.openapi.project.Project;
import org.eclipse.lsp4j.ConfigurationItem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SlangWorkspaceConfigurationTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void returnsNestedSlangConfigurationAndExpandsWorkspaceFolder() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath().toAbsolutePath().normalize();
        Path shader = workspace.resolve("Shaders").resolve("Main.slang");
        Files.createDirectories(shader.getParent());
        Files.writeString(shader, "void main() {}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("slangdconfig.json"), """
                {
                  "slang.predefinedMacros": ["VULKAN=1"],
                  "slang.additionalSearchPaths": ["${workspaceFolder}/Shaders"],
                  "slang.workspaceFlavor": "vfx",
                  "ignored.setting": true
                }
                """, StandardCharsets.UTF_8);

        SlangWorkspaceConfiguration configuration = new SlangWorkspaceConfiguration(project(workspace));
        ConfigurationItem item = item("slang", shader.toUri().toString());

        Object result = configuration.get(item);
        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> slang = (Map<?, ?>) result;
        assertEquals(List.of("VULKAN=1"), slang.get("predefinedMacros"));
        assertEquals(List.of(workspace + "/Shaders"), slang.get("additionalSearchPaths"));
        assertEquals("vfx", slang.get("workspaceFlavor"));
        assertTrue(slang.get("inlayHints") instanceof Map<?, ?>);
    }

    @Test
    public void nearestParentConfigurationWinsAndExactSectionReturnsAValue() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath().toAbsolutePath().normalize();
        Path nested = workspace.resolve("nested");
        Path shader = nested.resolve("Main.slang");
        Files.createDirectories(nested);
        Files.writeString(shader, "void main() {}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("slangdconfig.json"),
                "{\"slang.predefinedMacros\":[\"ROOT=1\"]}", StandardCharsets.UTF_8);
        Files.writeString(nested.resolve("slangdconfig.json"),
                "{\"slang.predefinedMacros\":[\"NESTED=1\"]}", StandardCharsets.UTF_8);

        SlangWorkspaceConfiguration configuration = new SlangWorkspaceConfiguration(project(workspace));
        Object value = configuration.get(item("slang.predefinedMacros", shader.toUri().toString()));

        assertNotNull(value);
        assertEquals(List.of("NESTED=1"), value);
    }

    private static ConfigurationItem item(String section, String scopeUri) {
        ConfigurationItem item = new ConfigurationItem();
        item.setSection(section);
        item.setScopeUri(scopeUri);
        return item;
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
