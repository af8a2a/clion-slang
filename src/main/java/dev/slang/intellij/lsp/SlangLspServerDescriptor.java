package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import com.intellij.util.io.BaseDataReader;
import com.intellij.util.io.BaseOutputReader;
import dev.slang.intellij.synth.SlangSyntheticModuleFileProvider;
import org.eclipse.lsp4j.ConfigurationItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

public final class SlangLspServerDescriptor extends ProjectWideLspServerDescriptor {
    private static final String LANGUAGE_ID = "slang";

    private final Project project;
    private final SlangServerLocator locator;
    private final SlangWorkspaceConfiguration workspaceConfiguration;
    private final SlangSyntheticModuleFileProvider syntheticFiles;

    public SlangLspServerDescriptor(@NotNull Project project) {
        super(project, "Slang");
        this.project = project;
        locator = new SlangServerLocator();
        workspaceConfiguration = new SlangWorkspaceConfiguration(project);
        syntheticFiles = new SlangSyntheticModuleFileProvider(project, locator);
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.equals("slang") || normalized.equals("slangh");
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        Path executable = locator.resolve(project);
        GeneralCommandLine commandLine = new GeneralCommandLine(executable.toString())
                .withCharset(StandardCharsets.UTF_8);
        if (project.getBasePath() != null) {
            commandLine.withWorkDirectory(project.getBasePath());
        }
        return commandLine;
    }

    @Override
    public @NotNull OSProcessHandler startServerProcess() throws ExecutionException {
        GeneralCommandLine commandLine = createCommandLine();
        Process process = commandLine.createProcess();
        Process normalizedProcess = new SlangLspProtocolProcess(process);
        return new OSProcessHandler(
                normalizedProcess,
                commandLine.getCommandLineString(),
                commandLine.getCharset()
        ) {
            @Override
            protected BaseOutputReader.Options readerOptions() {
                return new BaseOutputReader.Options() {
                    @Override
                    public BaseDataReader.SleepingPolicy policy() {
                        return BaseOutputReader.Options.forMostlySilentProcess().policy();
                    }

                    @Override
                    public boolean splitToLines() {
                        // LSP Content-Length framing must not be split or normalized as console lines.
                        return false;
                    }
                };
            }
        };
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        return LANGUAGE_ID;
    }

    @Override
    public @Nullable VirtualFile findFileByUri(@NotNull String uri) {
        VirtualFile synthetic = syntheticFiles.findFileByUri(uri);
        return synthetic != null ? synthetic : super.findFileByUri(uri);
    }

    @Override
    public @NotNull String getFileUri(@NotNull VirtualFile file) {
        String syntheticUri = syntheticFiles.getUri(file);
        return syntheticUri != null ? syntheticUri : super.getFileUri(file);
    }

    @Override
    public @Nullable Object getWorkspaceConfiguration(@NotNull ConfigurationItem item) {
        return workspaceConfiguration.get(item);
    }
}
