package dev.slang.intellij.preprocessor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class SlangStopBranchPreviewAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation().setEnabledAndVisible(project != null && SlangSelectContextAction.supports(file)
                && SlangContextService.getInstance(project).previews().peek(file.getPath()) != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project != null && file != null) SlangContextService.getInstance(project).stopPreview(file);
    }
}
