package dev.slang.intellij.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Folds consecutive full-line comments and multiline block comments in Slang files. */
public final class SlangFoldingBuilder extends FoldingBuilderEx implements DumbAware {
    private static final int MAX_SUMMARY_CODE_POINTS = 60;

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root,
            @NotNull Document document,
            boolean quick
    ) {
        ASTNode rootNode = root.getNode();
        if (rootNode == null) {
            return FoldingDescriptor.EMPTY_ARRAY;
        }

        List<FoldRegion> regions = findCommentFoldRegions(document.getImmutableCharSequence());
        if (regions.isEmpty()) {
            return FoldingDescriptor.EMPTY_ARRAY;
        }

        List<FoldingDescriptor> descriptors = new ArrayList<>(regions.size());
        for (FoldRegion region : regions) {
            TextRange range = new TextRange(region.startOffset(), region.endOffset());
            if (!rootNode.getTextRange().contains(range)) {
                continue;
            }

            int relativeStart = region.startOffset() - rootNode.getStartOffset();
            ASTNode anchor = relativeStart >= 0 ? rootNode.findLeafElementAt(relativeStart) : null;
            if (anchor == null
                    || anchor.getStartOffset() != region.startOffset()
                    || !SlangTokenTypes.COMMENTS.contains(anchor.getElementType())) {
                // The document can temporarily be ahead of PSI while the user is typing. Wait for
                // the next committed folding pass instead of binding the range to a stale token.
                continue;
            }
            descriptors.add(new FoldingDescriptor(
                    anchor,
                    range,
                    null,
                    region.placeholderText()
            ));
        }
        return descriptors.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        // Every descriptor carries its range-specific summary. This is only a defensive fallback.
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    static @NotNull List<FoldRegion> findCommentFoldRegions(@NotNull CharSequence source) {
        List<Lexeme> tokens = lex(source);
        List<FoldRegion> regions = new ArrayList<>();

        int index = 0;
        while (index < tokens.size()) {
            Lexeme token = tokens.get(index);
            String lineMarker = lineCommentMarker(source, token);
            String indentation = lineMarker == null ? null : leadingIndentation(source, token.startOffset());
            if (lineMarker != null && indentation != null) {
                int lastCommentIndex = index;
                int commentCount = 1;
                int endOffset = token.endOffset();

                while (lastCommentIndex + 2 < tokens.size()) {
                    Lexeme whitespace = tokens.get(lastCommentIndex + 1);
                    Lexeme nextComment = tokens.get(lastCommentIndex + 2);
                    String nextMarker = lineCommentMarker(source, nextComment);
                    String nextIndentation = nextMarker == null
                            ? null
                            : leadingIndentation(source, nextComment.startOffset());
                    if (whitespace.type() != SlangTokenTypes.WHITE_SPACE
                            || nextMarker == null
                            || !lineMarker.equals(nextMarker)
                            || !indentation.equals(nextIndentation)
                            || !isSingleLineGap(
                                    source,
                                    whitespace.startOffset(),
                                    whitespace.endOffset()
                            )) {
                        break;
                    }

                    lastCommentIndex += 2;
                    commentCount++;
                    endOffset = nextComment.endOffset();
                }

                if (commentCount >= 2) {
                    regions.add(new FoldRegion(
                            token.startOffset(),
                            endOffset,
                            linePlaceholder(source, token.startOffset(), endOffset, lineMarker)
                    ));
                }
                index = lastCommentIndex + 1;
                continue;
            }

            String blockMarker = blockCommentMarker(source, token);
            if (blockMarker != null) {
                int lastChunkIndex = index;
                int endOffset = token.endOffset();
                while (!endsBlockComment(source, endOffset)
                        && lastChunkIndex + 1 < tokens.size()) {
                    Lexeme nextChunk = tokens.get(lastChunkIndex + 1);
                    if (nextChunk.type() != token.type() || nextChunk.startOffset() != endOffset) {
                        break;
                    }
                    lastChunkIndex++;
                    endOffset = nextChunk.endOffset();
                }

                if (containsLineBreak(source, token.startOffset(), endOffset)) {
                    regions.add(new FoldRegion(
                            token.startOffset(),
                            endOffset,
                            blockPlaceholder(source, token.startOffset(), endOffset, blockMarker)
                    ));
                }
                index = lastChunkIndex + 1;
                continue;
            }

            index++;
        }

        return List.copyOf(regions);
    }

    private static List<Lexeme> lex(CharSequence source) {
        SlangLexer lexer = new SlangLexer();
        lexer.start(source, 0, source.length(), 0);
        List<Lexeme> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new Lexeme(lexer.getTokenType(), lexer.getTokenStart(), lexer.getTokenEnd()));
            lexer.advance();
        }
        return tokens;
    }

    private static @Nullable String lineCommentMarker(CharSequence source, Lexeme token) {
        if (token.type() != SlangTokenTypes.LINE_COMMENT && token.type() != SlangTokenTypes.DOC_COMMENT) {
            return null;
        }
        int start = token.startOffset();
        if (!startsWith(source, start, "//")) {
            return null;
        }
        if (startsWith(source, start, "///")) {
            return "///";
        }
        if (startsWith(source, start, "//!")) {
            return "//!";
        }
        return "//";
    }

    private static @Nullable String blockCommentMarker(CharSequence source, Lexeme token) {
        if (token.type() != SlangTokenTypes.BLOCK_COMMENT && token.type() != SlangTokenTypes.DOC_COMMENT) {
            return null;
        }
        int start = token.startOffset();
        if (startsWith(source, start, "/**")) {
            return "/**";
        }
        if (startsWith(source, start, "/*!")) {
            return "/*!";
        }
        return startsWith(source, start, "/*") ? "/*" : null;
    }

    private static @Nullable String leadingIndentation(CharSequence source, int commentStart) {
        int lineStart = commentStart;
        while (lineStart > 0) {
            char previous = source.charAt(lineStart - 1);
            if (previous == '\r' || previous == '\n') {
                break;
            }
            lineStart--;
        }
        for (int offset = lineStart; offset < commentStart; offset++) {
            char c = source.charAt(offset);
            if (c != ' ' && c != '\t') {
                return null;
            }
        }
        return source.subSequence(lineStart, commentStart).toString();
    }

    private static boolean isSingleLineGap(CharSequence source, int startOffset, int endOffset) {
        int lineBreaks = 0;
        for (int offset = startOffset; offset < endOffset; offset++) {
            char c = source.charAt(offset);
            if (c == '\r') {
                lineBreaks++;
                if (offset + 1 < endOffset && source.charAt(offset + 1) == '\n') {
                    offset++;
                }
            } else if (c == '\n') {
                lineBreaks++;
            } else if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return lineBreaks == 1;
    }

    private static boolean containsLineBreak(CharSequence source, int startOffset, int endOffset) {
        for (int offset = startOffset; offset < endOffset; offset++) {
            char c = source.charAt(offset);
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private static boolean endsBlockComment(CharSequence source, int endOffset) {
        return endOffset >= 2
                && source.charAt(endOffset - 2) == '*'
                && source.charAt(endOffset - 1) == '/';
    }

    private static String linePlaceholder(
            CharSequence source,
            int startOffset,
            int endOffset,
            String marker
    ) {
        String summary = firstMeaningfulLine(source, startOffset, endOffset, false);
        return summary.isEmpty() ? marker + " ..." : marker + " " + summary + " ...";
    }

    private static String blockPlaceholder(
            CharSequence source,
            int startOffset,
            int endOffset,
            String marker
    ) {
        String summary = firstMeaningfulLine(source, startOffset, endOffset, true);
        return summary.isEmpty()
                ? marker + " ... */"
                : marker + " " + summary + " ... */";
    }

    private static String firstMeaningfulLine(
            CharSequence source,
            int startOffset,
            int endOffset,
            boolean blockComment
    ) {
        String text = source.subSequence(startOffset, endOffset).toString();
        var lines = text.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            String content = blockComment ? stripBlockDecoration(line) : stripLineDecoration(line);
            content = content.replaceAll("\\s+", " ").strip();
            if (containsLetterOrDigit(content)) {
                return abbreviate(content);
            }
        }
        return "";
    }

    private static String stripLineDecoration(String line) {
        String content = line.strip();
        if (content.startsWith("///") || content.startsWith("//!")) {
            return content.substring(3).strip();
        }
        if (content.startsWith("//")) {
            return content.substring(2).strip();
        }
        return content;
    }

    private static String stripBlockDecoration(String line) {
        String content = line.strip();
        if (content.startsWith("/*")) {
            content = content.substring(2).strip();
            while (content.startsWith("*") || content.startsWith("!")) {
                content = content.substring(1).strip();
            }
        } else {
            while (content.startsWith("*")) {
                content = content.substring(1).strip();
            }
        }
        if (content.endsWith("*/")) {
            content = content.substring(0, content.length() - 2).strip();
        }
        return content;
    }

    private static boolean containsLetterOrDigit(String text) {
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static String abbreviate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_SUMMARY_CODE_POINTS) {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_SUMMARY_CODE_POINTS);
        String prefix = text.substring(0, end).stripTrailing();
        int lastSpace = prefix.lastIndexOf(' ');
        if (lastSpace >= MAX_SUMMARY_CODE_POINTS / 2) {
            prefix = prefix.substring(0, lastSpace).stripTrailing();
        }
        return prefix;
    }

    private static boolean startsWith(CharSequence source, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > source.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (source.charAt(offset + index) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    record FoldRegion(int startOffset, int endOffset, @NotNull String placeholderText) {
    }

    private record Lexeme(@NotNull IElementType type, int startOffset, int endOffset) {
    }
}
