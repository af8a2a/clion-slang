package dev.slang.intellij.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** Lazily starts one project-wide slangd instance when a Slang source file is opened. */
public final class SlangLspServerSupportProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(
            @NotNull Project project,
            @NotNull VirtualFile file,
            @NotNull LspServerStarter serverStarter
    ) {
        if (isSlangFile(file)) {
            serverStarter.ensureServerStarted(new SlangLspServerDescriptor(project));
        }
    }

    private static boolean isSlangFile(@NotNull VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.equals("slang") || normalized.equals("slangh");
    }
}
