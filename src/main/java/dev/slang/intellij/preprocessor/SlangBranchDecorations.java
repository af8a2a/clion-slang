package dev.slang.intellij.preprocessor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Editor-local ownership: cleanup never touches semantic tokens, diagnostics or another split. */
final class SlangBranchDecorations implements Disposable {
    private final List<RangeHighlighter> highlights = new ArrayList<>();
    private final List<Inlay<?>> inlays = new ArrayList<>();

    SlangBranchDecorations(Editor editor, SlangBranchPresentation presentation, boolean showLabels) {
        try {
            for (TextRange range : presentation.inactive()) highlight(editor, range, SlangBranchColors.INACTIVE);
            for (TextRange range : presentation.active()) highlight(editor, range, SlangBranchColors.ACTIVE);
            if (showLabels) {
                editor.getInlayModel().execute(true, () -> {
                    for (var label : presentation.labels()) {
                        Inlay<?> inlay = editor.getInlayModel().addAfterLineEndElement(
                                label.offset(), true, new BranchLabelRenderer(label.text()));
                        if (inlay != null) inlays.add(inlay);
                    }
                });
            }
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        }
    }

    private void highlight(Editor editor, TextRange range, TextAttributesKey key) {
        RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
                range.getStartOffset(), range.getEndOffset(), HighlighterLayer.ADDITIONAL_SYNTAX + 1,
                null, HighlighterTargetArea.EXACT_RANGE);
        highlighter.setTextAttributesKey(key);
        highlights.add(highlighter);
    }

    @Override
    public void dispose() {
        for (Inlay<?> inlay : inlays) inlay.dispose();
        inlays.clear();
        for (RangeHighlighter highlight : highlights) highlight.dispose();
        highlights.clear();
    }

    static final class BranchLabelRenderer implements EditorCustomElementRenderer {
        private final String text;

        BranchLabelRenderer(String text) { this.text = text; }

        String text() { return text; }

        private static Font font(Editor editor) {
            return editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return inlay.getEditor().getContentComponent().getFontMetrics(font(inlay.getEditor()))
                    .stringWidth(text) + JBUI.scale(12);
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics graphics, @NotNull Rectangle region,
                          @NotNull TextAttributes ignored) {
            Editor editor = inlay.getEditor();
            TextAttributes attributes = editor.getColorsScheme().getAttributes(SlangBranchColors.LABEL);
            Graphics canvas = graphics.create();
            try {
                canvas.setFont(font(editor));
                FontMetrics metrics = canvas.getFontMetrics();
                int padding = JBUI.scale(4);
                Color background = attributes == null ? null : attributes.getBackgroundColor();
                if (background != null) {
                    canvas.setColor(background);
                    canvas.fillRoundRect(region.x + padding, region.y + 1,
                            region.width - padding, region.height - 2, padding, padding);
                }
                Color foreground = attributes == null ? null : attributes.getForegroundColor();
                canvas.setColor(foreground == null ? editor.getColorsScheme().getDefaultForeground() : foreground);
                canvas.drawString(text, region.x + padding * 2,
                        region.y + (region.height - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                canvas.dispose();
            }
        }
    }
}
