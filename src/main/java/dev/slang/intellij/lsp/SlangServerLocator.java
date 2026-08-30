package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Resolves the bundled slangd or an explicitly enabled external override. */
public final class SlangServerLocator {
    private static final List<String> EXECUTABLE_NAMES = SystemInfo.isWindows
            ? List.of("slangd.exe", "slangd")
            : List.of("slangd", "slangd.exe");

    private final BundledResolver bundledResolver;

    public SlangServerLocator() {
        bundledResolver = BundledRuntimeHolder.INSTANCE::resolve;
    }

    SlangServerLocator(@NotNull BundledResolver bundledResolver) {
        this.bundledResolver = bundledResolver;
    }

    /** The plugin-controlled runtime is the default for every project. */
    public @NotNull Path resolve(@NotNull Project project) throws ExecutionException {
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        return resolve(
                project,
                settings.getExternalSlangdPath(),
                settings.isUseExternalSlangd()
        );
    }

    /** Resolves unpersisted settings values for the Settings preview and tests. */
    public @NotNull Path resolve(
            @NotNull Project project,
            @Nullable String externalPath,
            boolean useExternal
    ) throws ExecutionException {
        if (!useExternal) {
            return bundledResolver.resolve();
        }

        String configured = externalPath == null ? "" : externalPath.trim();
        if (configured.isEmpty()) {
            throw new ExecutionException(
                    "External slangd override is enabled, but no executable is configured. "
                            + "Select it in Settings | Languages & Frameworks | Slang."
            );
        }

        Path resolved = resolveConfiguredPath(configured, project);
        if (resolved == null) {
            throw new ExecutionException(
                    "The configured external Slang language server does not exist or is not a file: "
                            + configured
            );
        }
        return resolved;
    }

    private static @Nullable Path resolveConfiguredPath(@NotNull String value, @NotNull Project project) {
        Path path = safePath(stripMatchingQuotes(value));
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

        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
            if (Files.isDirectory(normalized)) {
                for (String executableName : EXECUTABLE_NAMES) {
                    Path candidate = normalized.resolve(executableName);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
            return null;
        } catch (InvalidPathException | SecurityException ignored) {
            return null;
        }
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

    @FunctionalInterface
    interface BundledResolver {
        @NotNull Path resolve() throws ExecutionException;
    }

    /** One verified bundle per plugin classloader, shared by every project locator. */
    private static final class BundledRuntimeHolder {
        private static final SlangBundledRuntime INSTANCE = new SlangBundledRuntime();
    }
}
