package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.*;

public class SlangBundledRuntimeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    static SlangBundledRuntime createBundle(Path plugin) throws Exception {
        Path runtime = Files.createDirectories(plugin.resolve(SlangBundledRuntime.DIRECTORY));
        JsonObject files = new JsonObject();
        for (String name : List.of("slangd.exe", "slang-compiler.dll", "slang-glsl-module.dll")) {
            byte[] bytes = ("fixture-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(runtime.resolve(name), bytes);
            files.addProperty(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("profile", "clion-slang-enhanced");
        manifest.addProperty("platform", "windows-x86_64");
        manifest.add("files", files);
        Files.writeString(runtime.resolve("manifest.json"), manifest.toString());
        return new SlangBundledRuntime(() -> plugin, true);
    }

    @Test
    public void resolvesFilesRelativeToInstalledPluginIncludingSpaces() throws Exception {
        Path plugin = temporary.newFolder("Slang plugin 0.8").toPath();
        assertEquals(plugin.resolve(SlangBundledRuntime.DIRECTORY).resolve("slangd.exe"),
                createBundle(plugin).resolveExecutable());
    }

    @Test
    public void rejectsMixedCompilerDllEvenAfterPreviousSuccessfulLaunch() throws Exception {
        Path plugin = temporary.newFolder().toPath();
        var runtime = createBundle(plugin);
        Path executable = runtime.resolveExecutable();
        Files.writeString(executable.resolveSibling("slang-compiler.dll"), "stock DLL");
        assertEquals(executable, runtime.previewExecutable()); // Preview does not hash on the EDT.
        ExecutionException error = assertThrows(ExecutionException.class, runtime::resolveExecutable);
        assertTrue(error.getMessage().contains("Checksum mismatch"));
    }

    @Test
    public void missingModuleAndInvalidManifestGiveActionableErrors() throws Exception {
        Path plugin = temporary.newFolder().toPath();
        var runtime = createBundle(plugin);
        Path directory = plugin.resolve(SlangBundledRuntime.DIRECTORY);
        Files.writeString(directory.resolve("manifest.json"), "{}");
        assertTrue(assertThrows(ExecutionException.class, runtime::resolveExecutable).getMessage().contains("Reinstall"));
        Files.delete(directory.resolve("slang-glsl-module.dll"));
        assertTrue(assertThrows(ExecutionException.class, runtime::resolveExecutable).getMessage().contains("incomplete"));
    }

    @Test
    public void unsupportedPlatformDoesNotEvenReadPluginDirectory() {
        var runtime = new SlangBundledRuntime(() -> { fail("must not resolve Windows files"); return null; }, false);
        assertTrue(assertThrows(ExecutionException.class, runtime::resolveExecutable).getMessage().contains("Windows x64"));
    }
}
