package dev.slang.intellij.preprocessor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.ui.components.JBScrollPane;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/** Transactional editor for temporary input. Applying starts a session; Cancel does not change it. */
public final class SlangPreviewBranchAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    static LspServer supportedServer(Project project, VirtualFile file) {
        var settings = SlangProjectSettings.getInstance(project);
        if (!settings.isShowPreprocessorBranches()) return null;
        return LspServerManager.getInstance(project).getServersForProvider(SlangLspServerSupportProvider.class).stream()
                .filter(s -> s.getState() == LspServerState.Running && s.getInitializeResult() != null
                        && SlangPreprocessorTrace.supportsPreview(s.getInitializeResult().getCapabilities())
                        && (settings.getShaderVariant(file.getPath()) == null
                        || SlangPreprocessorTrace.supportsVariants(s.getInitializeResult().getCapabilities())))
                .findFirst().orElse(null);
    }

    @Override public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean visible = project != null && SlangSelectContextAction.supports(file);
        event.getPresentation().setVisible(visible);
        boolean enabled = visible && supportedServer(project, file) != null && event.getData(CommonDataKeys.EDITOR) != null;
        event.getPresentation().setEnabled(enabled);
        event.getPresentation().setDescription(enabled ? "Temporarily override initial macros for branch display only"
                : "Requires branch display and an M4d-enabled slangd (plus M4e for Shader Variants)");
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        VirtualFile target = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!SlangSelectContextAction.supports(target)) return;
        LspServer server = supportedServer(project, target);
        if (server == null) return;
        var contexts = SlangContextService.getInstance(project);
        long revision = contexts.revision();
        new Task.Backgroundable(project, "Resolve Slang branch preview context", true) {
            private SlangContextService.ResolvedContext resolved;
            private String error;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { resolved = contexts.resolveBuildContext(target); }
                catch (IllegalArgumentException exception) { error = exception.getMessage(); }
            }
            private boolean current() {
                return !project.isDisposed() && !editor.isDisposed() && target.isValid()
                        && FileEditorManager.getInstance(project).isFileOpen(target)
                        && contexts.revision() == revision && supportedServer(project, target) == server;
            }
            @Override public void onSuccess() {
                if (!current()) return;
                if (error != null) { Messages.showErrorDialog(project, error, "Slang Branch Preview"); return; }
                var baseline = contexts.baseline(target, resolved);
                var existing = contexts.previews().current(target.getPath(), baseline, server);
                var dialog = new PreviewDialog(project, resolved.label(), existing == null ? null : existing.macros());
                if (!dialog.showAndGet()) return;
                if (!current()) {
                    if (!project.isDisposed()) Messages.showInfoMessage(project,
                            "The context or editor changed. Reopen Branch Preview to use the current context.", "Slang Branch Preview");
                    return;
                }
                contexts.previews().start(target.getPath(), baseline, server, dialog.macros());
                SlangBranchDisplayService.getInstance(project).refresh();
            }
        }.queue();
    }

    private static final class PreviewDialog extends DialogWrapper {
        private final JTextArea definitions = new JTextArea(8, 54);
        private final JTextArea undefinitions = new JTextArea(4, 54);
        private final String context;
        PreviewDialog(Project project, String context, SlangMacroPreview existing) {
            super(project);
            this.context = context;
            if (existing != null) {
                definitions.setText(existing.definitions());
                undefinitions.setText(existing.undefinitions());
            }
            setTitle("Slang Branch Preview — Temporary Macros");
            setOKButtonText(existing == null ? "Start Preview" : "Update Preview");
            init();
        }

        @Override protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            // Text areas, not HTML labels: names and macro values are untrusted project data.
            JTextArea explanation = new JTextArea("Context: " + context
                    + "\nBranch display only; completion and diagnostics keep the original environment."
                    + "\nSource #define/#undef directives still apply. Nothing is saved to configuration."
                    + "\nAfter applying, use Stop Slang Branch Preview to restore; closing the file or switching context also stops it.");
            explanation.setEditable(false);
            explanation.setLineWrap(true);
            explanation.setWrapStyleWord(true);
            explanation.setOpaque(false);
            explanation.setFont(UIManager.getFont("Label.font"));
            panel.add(explanation);
            panel.add(Box.createVerticalStrut(12));
            panel.add(new JLabel("Define — one NAME=value per line; NAME or NAME= means an empty replacement (not 1):"));
            panel.add(new JBScrollPane(definitions));
            panel.add(Box.createVerticalStrut(8));
            panel.add(new JLabel("Undefine — one NAME per line; removes initial workspace / Variant definitions:"));
            panel.add(new JBScrollPane(undefinitions));
            panel.setPreferredSize(new Dimension(660, 390));
            return panel;
        }
        SlangMacroPreview macros() { return SlangMacroPreview.parse(definitions.getText(), undefinitions.getText()); }
        @Override protected @Nullable ValidationInfo doValidate() {
            try { macros(); return null; }
            catch (IllegalArgumentException exception) { return new ValidationInfo(exception.getMessage()); }
        }
        @Override public @Nullable JComponent getPreferredFocusedComponent() { return definitions; }
    }
}
