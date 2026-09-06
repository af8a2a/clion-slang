package dev.slang.intellij.preprocessor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.TextRange;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SlangBranchDecorationsTest {
    @Test public void splitEditorsOwnTheirDecorationsAndCleanupIsIdempotent() {
        FakeEditor left = new FakeEditor();
        FakeEditor right = new FakeEditor();
        var model = new SlangBranchPresentation(List.of(new TextRange(5, 10)),
                List.of(new TextRange(12, 14)), List.of(new SlangBranchPresentation.Label(20, "#if BLUE")));
        var leftDecorations = new SlangBranchDecorations(left.editor, model, true);
        var rightDecorations = new SlangBranchDecorations(right.editor, model, true);
        assertEquals(List.of(SlangBranchColors.INACTIVE, SlangBranchColors.ACTIVE), left.keys);
        assertEquals(List.of("#if BLUE"), left.labels);
        leftDecorations.dispose();
        leftDecorations.dispose();
        assertEquals(2, left.disposedHighlights.get());
        assertEquals(1, left.disposedInlays.get());
        assertEquals(0, right.disposedHighlights.get());
        rightDecorations.dispose();
        assertEquals(2, right.disposedHighlights.get());
        assertEquals(1, right.disposedInlays.get());
    }

    @Test public void labelToggleDoesNotDisableInactiveOrActiveMarking() {
        FakeEditor editor = new FakeEditor();
        var model = new SlangBranchPresentation(List.of(new TextRange(5, 10)),
                List.of(new TextRange(12, 14)), List.of(new SlangBranchPresentation.Label(20, "#if BLUE")));
        var decorations = new SlangBranchDecorations(editor.editor, model, false);
        assertEquals(2, editor.keys.size());
        assertTrue(editor.labels.isEmpty());
        decorations.dispose();
    }

    private static final class FakeEditor {
        final AtomicInteger disposedHighlights = new AtomicInteger();
        final AtomicInteger disposedInlays = new AtomicInteger();
        final List<TextAttributesKey> keys = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final MarkupModel markup = proxy(MarkupModel.class, (self, method, args) -> {
            if (!method.getName().equals("addRangeHighlighter")) throw new AssertionError(method);
            assertEquals(HighlighterLayer.ADDITIONAL_SYNTAX + 1, args[2]);
            assertEquals(HighlighterTargetArea.EXACT_RANGE, args[4]);
            return proxy(RangeHighlighter.class, (range, operation, parameters) -> {
                switch (operation.getName()) {
                    case "setTextAttributesKey" -> keys.add((TextAttributesKey) parameters[0]);
                    case "dispose" -> disposedHighlights.incrementAndGet();
                    default -> throw new AssertionError(operation);
                }
                return null;
            });
        });
        final InlayModel inlays = proxy(InlayModel.class, (self, method, args) -> {
            if (method.getName().equals("execute")) {
                ((Runnable) args[1]).run();
                return null;
            }
            if (!method.getName().equals("addAfterLineEndElement")) throw new AssertionError(method);
            labels.add(((SlangBranchDecorations.BranchLabelRenderer) args[2]).text());
            return proxy(Inlay.class, (inlay, operation, parameters) -> {
                if (!operation.getName().equals("dispose")) throw new AssertionError(operation);
                disposedInlays.incrementAndGet();
                return null;
            });
        });
        final Editor editor = proxy(Editor.class, (self, method, args) -> switch (method.getName()) {
            case "getMarkupModel" -> markup;
            case "getInlayModel" -> inlays;
            default -> throw new AssertionError(method);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
