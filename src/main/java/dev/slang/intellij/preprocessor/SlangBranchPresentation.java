package dev.slang.intellij.preprocessor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/** Converts a validated compiler snapshot into decorations. Never evaluates preprocessor expressions. */
public record SlangBranchPresentation(List<TextRange> inactive, List<TextRange> active, List<Label> labels) {
    private static final Set<String> OPENINGS = Set.of("if", "ifdef", "ifndef");
    private static final Set<String> KINDS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");
    private static final int MAX_ITEMS = 8_192;
    public record Label(int offset, String text) {}

    public static @Nullable SlangBranchPresentation create(Document document, SlangPreprocessorTrace trace) {
        if (trace == null || trace.directives() == null || trace.inactiveRegions() == null
                || trace.status() != null && !"ok".equals(trace.status())
                || (long) trace.directives().size() + trace.inactiveRegions().size() > MAX_ITEMS) return null;
        var directives = trace.directives();
        var ranges = new ArrayList<TextRange>();
        var inactive = new ArrayList<TextRange>();
        var active = new ArrayList<TextRange>();
        var labels = new ArrayList<Label>();
        var branches = new ArrayDeque<Integer>();
        var openings = new ArrayDeque<Integer>();
        var controllers = new ArrayList<Integer>();
        int previousEnd = 0;
        for (int i = 0; i < directives.size(); i++) {
            var directive = directives.get(i);
            if (directive == null || directive.kind() == null || !KINDS.contains(directive.kind())) return null;
            TextRange range = toRange(document, directive.range());
            TextRange keyword = toRange(document, directive.keywordRange());
            if (range == null || keyword == null || keyword.isEmpty() || range.getStartOffset() < previousEnd
                    || !range.contains(keyword) || directive.range().getStart().getCharacter() != 0
                    || !slice(document, keyword).equals(directive.kind())) return null;
            previousEnd = range.getEndOffset();
            boolean opening = OPENINGS.contains(directive.kind());
            if (opening) {
                if (directive.depth() != branches.size() || directive.matchingIfDirective() != i
                        || directive.previousBranchDirective() != -1
                        || directive.parentDirective() != (branches.isEmpty() ? -1 : branches.peek())) return null;
                openings.push(i);
                branches.push(i);
            } else {
                if (branches.isEmpty() || directive.depth() != branches.size() - 1
                        || directive.matchingIfDirective() != openings.peek()
                        || directive.previousBranchDirective() != branches.peek()) return null;
                int previous = branches.pop();
                if (directive.parentDirective() != (branches.isEmpty() ? -1 : branches.peek())) return null;
                if (directive.kind().equals("endif")) {
                    openings.pop();
                } else {
                    if (directives.get(previous).kind().equals("else")) return null;
                    branches.push(i);
                }
                String label = directiveText(document, directives.get(previous));
                if (directives.get(previous).kind().equals("else")) {
                    int beforeElse = directives.get(previous).previousBranchDirective();
                    label = directiveText(document, directives.get(beforeElse)) + " #else";
                }
                labels.add(new Label(range.getEndOffset(), abbreviate(label)));
            }
            if ((!directive.evaluated() && directive.value())
                    || (directive.kind().equals("endif") && directive.active())) return null;
            if (directive.active()) active.add(keyword);
            ranges.add(range);
            int controller = -1;
            for (int branch : branches) {
                if (!directives.get(branch).active()) {
                    controller = branch;
                    break;
                }
            }
            controllers.add(controller);
        }
        if (!branches.isEmpty()) return null;
        int directiveIndex = -1;
        previousEnd = 0;
        for (var region : trace.inactiveRegions()) {
            if (region == null) return null;
            TextRange range = toRange(document, region.range());
            if (range == null || range.isEmpty() || range.getStartOffset() < previousEnd) return null;
            while (directiveIndex + 1 < ranges.size()
                    && ranges.get(directiveIndex + 1).getEndOffset() <= range.getStartOffset()) directiveIndex++;
            if (directiveIndex < 0 || region.controllingDirective() < 0
                    || region.controllingDirective() != controllers.get(directiveIndex)
                    || (directiveIndex + 1 < ranges.size()
                    && range.getEndOffset() > ranges.get(directiveIndex + 1).getStartOffset())) return null;
            inactive.add(range);
            previousEnd = range.getEndOffset();
        }
        return new SlangBranchPresentation(List.copyOf(inactive), List.copyOf(active), List.copyOf(labels));
    }

    /** Invalid positions are rejected, not clamped onto unrelated source code. */
    static @Nullable TextRange toRange(Document document, Range range) {
        if (range == null) return null;
        int start = toOffset(document, range.getStart());
        int end = toOffset(document, range.getEnd());
        return start < 0 || end < start ? null : new TextRange(start, end);
    }

    private static int toOffset(Document document, Position position) {
        if (position == null || position.getLine() < 0 || position.getLine() >= document.getLineCount()
                || position.getCharacter() < 0) return -1;
        int start = document.getLineStartOffset(position.getLine());
        int end = document.getLineEndOffset(position.getLine());
        if (position.getCharacter() > end - start) return -1;
        int offset = start + position.getCharacter();
        CharSequence text = document.getCharsSequence();
        if (offset > start && offset < end && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset))) return -1;
        return offset;
    }

    private static String directiveText(Document document, SlangPreprocessorTrace.Directive directive) {
        TextRange range = toRange(document, directive.range());
        String text = slice(document, range);
        // Only textual cleanup for labels; no macro parsing or branch evaluation.
        text = text.replaceAll("\\\\\\r?\\n", " ").replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\r\\n]*", " ").replaceAll("\\s+", " ").trim();
        return abbreviate(text);
    }

    private static String abbreviate(String text) {
        int count = text.codePointCount(0, text.length());
        return count <= 80 ? text : text.substring(0, text.offsetByCodePoints(0, 79)) + "…";
    }

    private static String slice(Document document, TextRange range) {
        return document.getCharsSequence().subSequence(range.getStartOffset(), range.getEndOffset()).toString();
    }
}
