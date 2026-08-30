package dev.slang.intellij.synth;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Materializes source files exposed by slangd through slang-synth:// URIs. */
public final class SlangSyntheticModuleFileProvider {
    private static final Logger LOG = Logger.getInstance(SlangSyntheticModuleFileProvider.class);
    private static final int PROCESS_TIMEOUT_MILLIS = 10_000;
    private static final int WAIT_TIMEOUT_SECONDS = 12;

    private final Project project;
    private final ModuleLoader moduleLoader;
    private final Map<ModuleKey, LightVirtualFile> cache = new ConcurrentHashMap<>();
    private final Map<ModuleKey, CompletableFuture<LightVirtualFile>> inFlight = new ConcurrentHashMap<>();
    private final Map<VirtualFile, String> sourceUris = new ConcurrentHashMap<>();
    private volatile SessionRuntime sessionRuntime;
    private long nextGeneration;

    public SlangSyntheticModuleFileProvider(@NotNull Project project) {
        this.project = project;
        moduleLoader = this::loadModule;
    }

    SlangSyntheticModuleFileProvider(@NotNull Project project, @NotNull ModuleLoader moduleLoader) {
        this.project = project;
        this.moduleLoader = moduleLoader;
    }

    /** Associates future synthetic-module requests with a successfully started LSP session. */
    public synchronized void activateSession(@NotNull Path executable) {
        cache.clear();
        sourceUris.clear();
        sessionRuntime = new SessionRuntime(
                executable.toAbsolutePath().normalize(),
                ++nextGeneration
        );
    }

    public @Nullable VirtualFile findFileByUri(@NotNull String uriText) {
        String module = moduleFromUri(uriText);
        if (module == null) {
            return null;
        }

        SessionRuntime session = sessionRuntime;
        if (session == null) {
            return null;
        }
        ModuleKey key = new ModuleKey(module, session.generation);
        LightVirtualFile cached = cache.get(key);
        if (cached != null && sessionRuntime == session) {
            return cached;
        }

        CompletableFuture<LightVirtualFile> future = startLoading(key, module, uriText, session);
        Application application = ApplicationManager.getApplication();
        if (application != null && application.isDispatchThread()) {
            // LspServerDescriptor.findFileByUri is synchronous. There is no native
            // async return type in Build 261, so never wait here: materialization
            // continues on the application executor and the next URI resolution
            // uses the cache. In normal LSP navigation this method is called from
            // a background request coroutine and returns the freshly loaded file.
            return future.getNow(null);
        }

        try {
            return future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | TimeoutException exception) {
            LOG.warn("Cannot materialize " + uriText, exception);
            return null;
        }
    }

    public @Nullable String getUri(@NotNull VirtualFile file) {
        return sourceUris.get(file);
    }

    private @NotNull CompletableFuture<LightVirtualFile> startLoading(
            @NotNull ModuleKey key,
            @NotNull String module,
            @NotNull String uriText,
            @NotNull SessionRuntime session
    ) {
        CompletableFuture<LightVirtualFile> newFuture = new CompletableFuture<>();
        CompletableFuture<LightVirtualFile> existing = inFlight.putIfAbsent(key, newFuture);
        if (existing != null) {
            return existing;
        }

        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                LightVirtualFile file = project.isDisposed() || sessionRuntime != session
                        ? null
                        : moduleLoader.load(session.executable, module);
                newFuture.complete(publishIfCurrent(key, uriText, session, file));
            } catch (Throwable throwable) {
                newFuture.completeExceptionally(throwable);
            } finally {
                inFlight.remove(key, newFuture);
            }
        });
        return newFuture;
    }

    private synchronized @Nullable LightVirtualFile publishIfCurrent(
            @NotNull ModuleKey key,
            @NotNull String uriText,
            @NotNull SessionRuntime session,
            @Nullable LightVirtualFile file
    ) {
        if (file == null || sessionRuntime != session) {
            return null;
        }
        cache.put(key, file);
        sourceUris.put(file, uriText);
        return file;
    }

    private @Nullable LightVirtualFile loadModule(@NotNull Path slangd, @NotNull String module) {
        GeneralCommandLine commandLine = new GeneralCommandLine(
                slangd.toString(),
                "--print-builtin-module",
                module
        ).withCharset(StandardCharsets.UTF_8);
        String basePath = project.getBasePath();
        if (basePath != null) {
            commandLine.withWorkDirectory(basePath);
        }

        final ProcessOutput output;
        try {
            output = new CapturingProcessHandler(commandLine).runProcess(PROCESS_TIMEOUT_MILLIS);
        } catch (ExecutionException exception) {
            LOG.warn("Cannot run slangd to load built-in module " + module, exception);
            return null;
        }

        if (output.isTimeout()) {
            LOG.warn("Timed out loading Slang built-in module " + module);
            return null;
        }
        if (!output.isExitCodeSet() || output.getExitCode() != 0) {
            String stderr = output.getStderr().trim();
            if (stderr.length() > 500) {
                stderr = stderr.substring(0, 500) + "...";
            }
            LOG.warn("slangd failed to print built-in module " + module
                    + (stderr.isEmpty() ? "" : ": " + stderr));
            return null;
        }

        LightVirtualFile file = new LightVirtualFile(safeFileName(module), output.getStdout());
        file.setCharset(StandardCharsets.UTF_8);
        file.setWritable(false);
        return file;
    }

    private static @Nullable String moduleFromUri(@NotNull String uriText) {
        final URI uri;
        try {
            uri = new URI(uriText);
        } catch (URISyntaxException exception) {
            return null;
        }
        if (!"slang-synth".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            return null;
        }

        String module = uri.getHost();
        if (module == null || module.isBlank()) {
            String path = uri.getPath();
            if (path != null && path.startsWith("/") && path.indexOf('/', 1) == -1) {
                module = path.substring(1);
            }
        }
        if (module == null || module.isBlank() || module.contains("/") || module.contains("\\")) {
            return null;
        }
        return module;
    }

    private static @NotNull String safeFileName(@NotNull String module) {
        String safe = module.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.endsWith(".slang") ? safe : safe + ".slang";
    }

    @FunctionalInterface
    interface ModuleLoader {
        @Nullable LightVirtualFile load(@NotNull Path executable, @NotNull String module);
    }

    private record SessionRuntime(@NotNull Path executable, long generation) {
    }

    private record ModuleKey(@NotNull String module, long generation) {
    }
}
