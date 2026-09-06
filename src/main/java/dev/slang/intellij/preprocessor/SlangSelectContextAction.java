package dev.slang.intellij.preprocessor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SlangSelectContextAction extends DumbAwareAction {
    private record Choice(Path root, String label, boolean browse) {
        Choice(Path root, String label) { this(root, label, false); }
        @Override public String toString() { return label; }
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && supports(event.getData(CommonDataKeys.VIRTUAL_FILE)));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project != null && editor != null) show(project, editor);
    }

    static boolean supports(VirtualFile file) {
        return file != null && file.isValid() && file.isInLocalFileSystem()
                && ("slang".equalsIgnoreCase(file.getExtension()) || "slangh".equalsIgnoreCase(file.getExtension()));
    }

    public static void show(Project project, Editor editor) {
        VirtualFile target = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!supports(target)) return;
        SlangContextService contexts = SlangContextService.getInstance(project);
        new Task.Backgroundable(project, "Discover Slang include contexts", true) {
            private SlangContextService.Discovery discovery;
            @Override public void run(@NotNull ProgressIndicator indicator) { discovery = contexts.discover(target); }
            @Override public void onSuccess() {
                if (project.isDisposed() || editor.isDisposed() || !target.isValid()) return;
                List<Choice> choices = new ArrayList<>();
                choices.add(new Choice(null, "Auto — unique includer, otherwise current file"));
                choices.add(new Choice(SlangContextService.path(target), "Current file — " + target.getName()));
                for (var candidate : discovery.candidates()) {
                    String chain = String.join(" → ", candidate.chain().stream().map(contexts::relative).toList());
                    if (candidate.shortened()) chain += " → … → " + contexts.relative(SlangContextService.path(target));
                    choices.add(new Choice(candidate.root(), chain));
                }
                String pinned = SlangProjectSettings.getInstance(project).getPreprocessorContext(target.getPath());
                Path selected = pinned == null ? null : Path.of(pinned);
                if (selected != null && choices.stream().noneMatch(c -> selected.equals(c.root)))
                    choices.add(new Choice(selected, "Previously selected (not discovered) — " + contexts.relative(selected)));
                Choice current = choices.stream().filter(c -> Objects.equals(c.root, selected)).findFirst().orElse(choices.getFirst());
                choices.add(new Choice(null, "Choose another .slang root file…", true));
                JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                        .setTitle("Slang Preprocessor Context — " + target.getName())
                        .setNamerForFiltering(Choice::label).setFilterAlwaysVisible(true)
                        .setSelectedValue(current, true)
                        .setAdText((discovery.limited() ? "Scan incomplete. " : "")
                                + "Potential includes; slangd confirms execution. " + contexts.description(target))
                        .setItemChosenCallback(choice -> {
                            if (choice.browse) FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("slang")
                                            .withTitle("Choose Slang Compilation Root"), project, target,
                                    chosen -> { if (chosen.isInLocalFileSystem()) contexts.select(target, SlangContextService.path(chosen)); });
                            else contexts.select(target, choice.root);
                        })
                        .createPopup().showInBestPositionFor(editor);
            }
        }.queue();
    }
}
