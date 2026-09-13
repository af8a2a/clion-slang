package dev.slang.intellij.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import dev.slang.intellij.settings.SlangServerSource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/** Resolves the versioned runtime installed alongside the plugin JAR by the IDE. */
public final class SlangBundledRuntime {
    public static final String DIRECTORY = "runtime/windows-x86_64";
    private static final List<String> BINARIES = List.of("slangd.exe", "slang-compiler.dll", "slang-glsl-module.dll");
    private final Supplier<Path> pluginRoot;
    private final boolean supported;

    public SlangBundledRuntime() {
        this(() -> {
            var plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.shader.slang"));
            return plugin == null ? null : plugin.getPluginPath();
        }, SlangServerSource.isBundledSupported());
    }

    SlangBundledRuntime(Supplier<Path> pluginRoot, boolean supported) {
        this.pluginRoot = pluginRoot;
        this.supported = supported;
    }

    /** Cheap settings preview: no extraction, process launch, or checksum work on the UI thread. */
    public @NotNull Path previewExecutable() throws ExecutionException {
        if (!supported) {
            throw new ExecutionException("Bundled slangd supports Windows x64. Select External / official slangd on this platform.");
        }
        Path root = pluginRoot.get();
        if (root == null) throw new ExecutionException("Cannot locate the Slang plugin installation.");
        Path runtime = root.resolve(DIRECTORY).toAbsolutePath().normalize();
        for (String name : BINARIES) {
            if (!Files.isRegularFile(runtime.resolve(name))) {
                throw new ExecutionException("Bundled slangd is incomplete: " + runtime.resolve(name)
                        + ". Reinstall the plugin or select External / official slangd in Slang settings.");
            }
        }
        return runtime.resolve("slangd.exe");
    }

    /** Verify the executable and its matching DLLs before each server launch. Never fall back to PATH. */
    public @NotNull Path resolveExecutable() throws ExecutionException {
        Path executable = previewExecutable();
        Path runtime = executable.getParent();
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(runtime.resolve("manifest.json"))).getAsJsonObject();
            if (manifest.get("schemaVersion").getAsInt() != 1
                    || !"clion-slang-enhanced".equals(manifest.get("profile").getAsString())
                    || !"windows-x86_64".equals(manifest.get("platform").getAsString())) {
                throw new IOException("Unsupported bundled runtime manifest");
            }
            JsonObject files = manifest.getAsJsonObject("files");
            for (String name : BINARIES) {
                JsonElement entry = files.get(name);
                if (entry == null || !sha256(runtime.resolve(name)).equals(entry.getAsString())) {
                    throw new IOException("Checksum mismatch or missing manifest entry for " + name);
                }
            }
            return executable;
        } catch (IOException | RuntimeException exception) {
            throw new ExecutionException("Cannot verify bundled slangd: " + exception.getMessage()
                    + ". Reinstall the plugin or select External / official slangd in Slang settings.", exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
