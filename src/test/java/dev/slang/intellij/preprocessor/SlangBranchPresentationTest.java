package dev.slang.intellij.preprocessor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.util.TextRange;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SlangBranchPresentationTest {
    private static final String URI = "file:///Shader.slang";

    @Test public void showsTheCompilerSelectedBranchAndRiderStyleSourceLabels() {
        Document document = new DocumentImpl("#if BLUE\nblue;\n#elif GREEN\ngreen;\n#elif RED\nred;\n#else\nother;\n#endif");
        var directives = List.of(
                directive(document, "if", 0, true, false, false, 0, -1, 0, -1),
                directive(document, "elif", 2, true, true, true, 0, -1, 0, 0),
                directive(document, "elif", 4, false, false, false, 0, -1, 0, 1),
                directive(document, "else", 6, false, false, false, 0, -1, 0, 2),
                directive(document, "endif", 8, false, false, false, 0, -1, 0, 3));
        var trace = new SlangPreprocessorTrace(URI, 1, directives,
                List.of(region(1, 2, 0), region(5, 6, 2), region(7, 8, 3)));
        var presentation = SlangBranchPresentation.create(document, trace);
        assertNotNull(presentation);
        assertEquals(List.of("#if BLUE", "#elif GREEN", "#elif RED", "#elif RED #else"),
                presentation.labels().stream().map(SlangBranchPresentation.Label::text).toList());
        assertEquals(List.of(new TextRange(document.getLineStartOffset(2) + 1, document.getLineStartOffset(2) + 5)),
                presentation.active());
        assertEquals(List.of("blue;\n", "red;\n", "other;\n"),
                presentation.inactive().stream().map(range -> document.getCharsSequence()
                        .subSequence(range.getStartOffset(), range.getEndOffset()).toString()).toList());
        assertEquals(document.getTextLength(), presentation.labels().getLast().offset());
    }

    @Test public void restoresOuterInactiveControllerAfterNestedEndif() {
        Document document = new DocumentImpl("#if 0\n#if UNKNOWN\n// nested\n#endif\n\n#else\nactive;\n#endif\n");
        var trace = new SlangPreprocessorTrace(URI, 1, List.of(
                directive(document, "if", 0, true, false, false, 0, -1, 0, -1),
                directive(document, "if", 1, false, false, false, 1, 0, 1, -1),
                directive(document, "endif", 3, false, false, false, 1, 0, 1, 1),
                directive(document, "else", 5, false, false, true, 0, -1, 0, 0),
                directive(document, "endif", 7, false, false, false, 0, -1, 0, 3)),
                List.of(region(2, 3, 1), region(4, 5, 0)));
        var presentation = SlangBranchPresentation.create(document, trace);
        assertNotNull(presentation);
        assertEquals(2, presentation.inactive().size());
        assertEquals(1, presentation.active().size());
        assertEquals(List.of("#if UNKNOWN", "#if 0", "#if 0 #else"),
                presentation.labels().stream().map(SlangBranchPresentation.Label::text).toList());
    }

    @Test public void handlesMultilineConditionsCommentsUnicodeAndEmptyBodies() {
        Document document = new DocumentImpl("#if defined(BLUE) && \\\n    1 /* 😀 */ // note\n#endif");
        var start = new SlangPreprocessorTrace.Directive("if", new Range(new Position(0, 0),
                new Position(1, document.getLineEndOffset(1) - document.getLineStartOffset(1))),
                new Range(new Position(0, 1), new Position(0, 3)), true, true, true, 0, -1, 0, -1);
        var trace = new SlangPreprocessorTrace(URI, 4, List.of(start,
                directive(document, "endif", 2, false, false, false, 0, -1, 0, 0)), List.of());
        var presentation = SlangBranchPresentation.create(document, trace);
        assertNotNull(presentation);
        assertTrue(presentation.inactive().isEmpty());
        assertEquals("#if defined(BLUE) && 1", presentation.labels().getFirst().text());
    }

    @Test public void rejectsMalformedLinksAndOverlappingOrUncontrolledRegions() {
        Document document = new DocumentImpl("#if 0\ndisabled;\n#endif");
        var opening = directive(document, "if", 0, true, false, false, 0, -1, 0, -1);
        var closing = directive(document, "endif", 2, false, false, false, 0, -1, 0, 0);
        assertNull(SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1,
                List.of(opening), List.of())));
        assertNull(SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1,
                List.of(opening, directive(document, "endif", 2, false, false, false, 0, -1, 10, 0)), List.of())));
        for (var regions : List.of(List.of(region(1, 2, 1)), List.of(region(0, 2, 0)),
                List.of(region(1, 2, 0), region(1, 2, 0)))) {
            assertNull(SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1,
                    List.of(opening, closing), regions)));
        }
        assertNull(SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1,
                Collections.nCopies(8193, opening), List.of())));
        var withNull = new ArrayList<>(List.of(opening, closing));
        withNull.set(1, null);
        assertNull(SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1, withNull, List.of())));
        assertNull(SlangBranchPresentation.create(document, null));
    }

    @Test public void positionsAreStrictUtf16NotClampedAndNeverSplitSurrogatePairs() {
        Document document = new DocumentImpl("a😀z\n");
        assertEquals(new TextRange(1, 3), SlangBranchPresentation.toRange(document,
                new Range(new Position(0, 1), new Position(0, 3))));
        for (var position : List.of(new Position(-1, 0), new Position(3, 0),
                new Position(0, -1), new Position(0, 2), new Position(0, 5))) {
            assertNull(SlangBranchPresentation.toRange(document, new Range(new Position(0, 0), position)));
        }
        assertEquals(new TextRange(0, 5), SlangBranchPresentation.toRange(document,
                new Range(new Position(0, 0), new Position(1, 0))));
    }

    @Test public void emptyTraceIsAValidPresentationAndLongLabelsAreBounded() {
        Document plain = new DocumentImpl("float value;");
        var empty = SlangBranchPresentation.create(plain, new SlangPreprocessorTrace(URI, 1, List.of(), List.of()));
        assertNotNull(empty);
        assertTrue(empty.labels().isEmpty());
        Document document = new DocumentImpl("#if " + "MACRO_".repeat(30) + "\n#endif");
        var presentation = SlangBranchPresentation.create(document, new SlangPreprocessorTrace(URI, 1, List.of(
                directive(document, "if", 0, true, false, false, 0, -1, 0, -1),
                directive(document, "endif", 1, false, false, false, 0, -1, 0, 0)), List.of()));
        assertNotNull(presentation);
        assertEquals(80, presentation.labels().getFirst().text().length());
        assertTrue(presentation.labels().getFirst().text().endsWith("…"));
    }

    static SlangPreprocessorTrace.Directive directive(Document document, String kind, int line,
            boolean evaluated, boolean value, boolean active, int depth, int parent, int matching, int previous) {
        int length = document.getLineEndOffset(line) - document.getLineStartOffset(line);
        return new SlangPreprocessorTrace.Directive(kind, new Range(new Position(line, 0), new Position(line, length)),
                new Range(new Position(line, 1), new Position(line, 1 + kind.length())),
                evaluated, value, active, depth, parent, matching, previous);
    }

    static SlangPreprocessorTrace.InactiveRegion region(int start, int end, int controller) {
        return new SlangPreprocessorTrace.InactiveRegion(new Range(new Position(start, 0), new Position(end, 0)), controller);
    }
}
