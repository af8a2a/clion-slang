package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import dev.slang.intellij.settings.SlangProjectSettings;
import dev.slang.intellij.settings.SlangServerSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves the slangd executable without starting a process. */
public final class SlangServerLocator {
    private static final List<String> EXECUTABLE_NAMES = SystemInfo.isWindows
            ? List.of("slangd.exe", "slangd")
            : List.of("slangd", "slangd.exe");

    private final Map<String, String> environment;
    private final SlangBundledRuntime bundledRuntime;

    public SlangServerLocator() {
        this(System.getenv());
    }

    SlangServerLocator(@NotNull Map<String, String> environment) {
        this(environment, new SlangBundledRuntime());
    }

    SlangServerLocator(@NotNull Map<String, String> environment, @NotNull SlangBundledRuntime bundledRuntime) {
        this.environment = environment;
        this.bundledRuntime = bundledRuntime;
    }

    /** The selected source is authoritative, including for synthetic built-in module navigation. */
    public @NotNull Path resolve(@NotNull Project project) throws ExecutionException {
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        return resolve(project, settings.getServerSource(), settings.getSlangdPath(), settings.isAutoDetectSlangd());
    }

    public @NotNull Path resolve(@NotNull Project project, @NotNull SlangServerSource source,
                                 @Nullable String configuredPath, boolean autoDetect) throws ExecutionException {
        return source == SlangServerSource.BUNDLED ? bundledRuntime.resolveExecutable()
                : resolve(project, autoDetect ? "" : configuredPath, autoDetect);
    }

    public @NotNull Path preview(@NotNull Project project, @NotNull SlangServerSource source,
                                 @Nullable String configuredPath, boolean autoDetect) throws ExecutionException {
        return source == SlangServerSource.BUNDLED ? bundledRuntime.previewExecutable()
                : resolve(project, autoDetect ? "" : configuredPath, autoDetect);
    }

    /** External resolver: explicit path, then SLANGD_PATH, VULKAN_SDK and PATH. No fallback for an invalid explicit path. */
    public @NotNull Path resolve(
            @NotNull Project project,
            @Nullable String configuredPath,
            boolean autoDetect
    ) throws ExecutionException {
        String configured = configuredPath == null ? "" : configuredPath.trim();
        if (!configured.isEmpty()) {
            Path resolved = resolveConfiguredPath(configured, project);
            if (resolved == null) {
                throw new ExecutionException(
                        "The configured Slang language server does not exist or is not a file: " + configured
                );
            }
            return resolved;
        }
        if (!autoDetect) {
            throw new ExecutionException(
                    "Slang language server auto-detection is disabled, but no slangd path is configured. "
                            + "Set it in Settings | Languages & Frameworks | Slang."
            );
        }

        Set<Path> candidates = new LinkedHashSet<>();
        List<String> inspectedSources = new ArrayList<>();

        String slangdPath = getEnvironment("SLANGD_PATH");
        if (slangdPath != null && !slangdPath.isBlank()) {
            inspectedSources.add("SLANGD_PATH=" + slangdPath);
            addPathOrDirectory(candidates, slangdPath, null);
        }

        String vulkanSdk = getEnvironment("VULKAN_SDK");
        if (vulkanSdk != null && !vulkanSdk.isBlank()) {
            inspectedSources.add("VULKAN_SDK=" + vulkanSdk);
            addDirectory(candidates, safePath(vulkanSdk), "Bin");
            addDirectory(candidates, safePath(vulkanSdk), "bin");
        }

        String pathValue = getEnvironment("PATH");
        if (pathValue != null && !pathValue.isBlank()) {
            inspectedSources.add("PATH");
            for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    addDirectory(candidates, safePath(stripMatchingQuotes(entry.trim())), null);
                }
            }
        }

        for (Path candidate : candidates) {
            Path executable = normalizeExecutable(candidate);
            if (executable != null) {
                return executable;
            }
        }

        String sources = inspectedSources.isEmpty()
                ? "none of SLANGD_PATH, VULKAN_SDK, or PATH was set"
                : String.join(", ", inspectedSources);
        throw new ExecutionException(
                "Cannot find the Slang language server executable (slangd). Checked " + sources + ". "
                        + "Install Slang/the Vulkan SDK or configure slangd in "
                        + "Settings | Languages & Frameworks | Slang."
        );
    }

    private @Nullable Path resolveConfiguredPath(@NotNull String value, @NotNull Project project) {
        String unquoted = stripMatchingQuotes(value);
        Path path = safePath(unquoted);
        if (path == null) {
            return null;
        }

        if (!path.isAbsolute() && project.getBasePath() != null) {
            try {
                path = Paths.get(project.getBasePath()).resolve(path);
            } catch (InvalidPathException ignored) {
                return null;
            }
        }

        Path executable = normalizeExecutable(path);
        if (executable != null) {
            return executable;
        }
        if (Files.isDirectory(path)) {
            for (String name : EXECUTABLE_NAMES) {
                executable = normalizeExecutable(path.resolve(name));
                if (executable != null) {
                    return executable;
                }
            }
        }
        return null;
    }

    private void addPathOrDirectory(@NotNull Set<Path> candidates, @NotNull String value, @Nullable Path relativeTo) {
        Path path = safePath(stripMatchingQuotes(value.trim()));
        if (path == null) {
            return;
        }
        if (!path.isAbsolute() && relativeTo != null) {
            path = relativeTo.resolve(path);
        }
        candidates.add(path);
        for (String name : EXECUTABLE_NAMES) {
            candidates.add(path.resolve(name));
        }
    }

    private void addDirectory(@NotNull Set<Path> candidates, @Nullable Path root, @Nullable String child) {
        if (root == null) {
            return;
        }
        Path directory = child == null ? root : root.resolve(child);
        for (String name : EXECUTABLE_NAMES) {
            candidates.add(directory.resolve(name));
        }
    }

    private static @Nullable Path normalizeExecutable(@NotNull Path candidate) {
        try {
            Path normalized = candidate.toAbsolutePath().normalize();
            return Files.isRegularFile(normalized) ? normalized : null;
        } catch (InvalidPathException | SecurityException ignored) {
            return null;
        }
    }

    private @Nullable String getEnvironment(@NotNull String name) {
        String direct = environment.get(name);
        if (direct != null || !SystemInfo.isWindows) {
            return direct;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static @Nullable Path safePath(@NotNull String value) {
        try {
            return Paths.get(value);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static @NotNull String stripMatchingQuotes(@NotNull String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
