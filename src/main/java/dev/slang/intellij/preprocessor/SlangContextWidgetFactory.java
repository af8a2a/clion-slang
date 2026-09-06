package dev.slang.intellij.preprocessor;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

public final class SlangContextWidgetFactory implements StatusBarWidgetFactory {
    public static final String ID = "SlangPreprocessorContext";
    @Override public @NotNull String getId() { return ID; }
    @Override public @NotNull String getDisplayName() { return "Slang Preprocessor Context"; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new Widget(project); }

    private static final class Widget implements StatusBarWidget, StatusBarWidget.TextPresentation {
        private final Project project;
        Widget(Project project) { this.project = project; }
        @Override public @NotNull String ID() { return ID; }
        @Override public WidgetPresentation getPresentation() { return this; }
        @Override public void install(@NotNull StatusBar bar) {
            project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) { bar.updateWidget(ID); }
            });
        }
        @Override public @NotNull String getText() {
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) return "";
            var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (!SlangSelectContextAction.supports(file)) return "";
            String text = SlangContextService.getInstance(project).description(file);
            return "Slang: " + (text.length() > 55 ? text.substring(0, 52) + "…" : text);
        }
        @Override public String getTooltipText() {
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            return editor == null ? "Slang preprocessor context" : SlangContextService.getInstance(project)
                    .description(FileDocumentManager.getInstance().getFile(editor.getDocument())) + " — click to choose";
        }
        @Override public float getAlignment() { return 0; }
        @Override public Consumer<MouseEvent> getClickConsumer() {
            return event -> {
                var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor != null) SlangSelectContextAction.show(project, editor);
            };
        }
        @Override public void dispose() { }
    }
}
