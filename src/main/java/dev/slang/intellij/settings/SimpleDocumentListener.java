package dev.slang.intellij.settings;

import org.jetbrains.annotations.NotNull;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class SimpleDocumentListener implements DocumentListener {
    private final Runnable callback;

    SimpleDocumentListener(@NotNull Runnable callback) {
        this.callback = callback;
    }

    @Override
    public void insertUpdate(DocumentEvent event) {
        callback.run();
    }

    @Override
    public void removeUpdate(DocumentEvent event) {
        callback.run();
    }

    @Override
    public void changedUpdate(DocumentEvent event) {
        callback.run();
    }
}
