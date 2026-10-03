package dev.slang.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.lexer.LexerPosition;
import com.intellij.psi.tree.IElementType;

/** Fine-grained PSI ranges without changing the highlighting/indexing lexer contract. */
final class SlangPsiLexer extends LexerBase {
    private final SlangLexer base = new SlangLexer();
    private int start;
    private int end;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        // Negative states encode the distance back to the directive's original start.
        // This allows a restart at any fragment without losing preprocessor context.
        base.start(buffer, startOffset + Math.min(initialState, 0), endOffset, Math.max(initialState, 0));
        start = startOffset;
        findEnd();
    }

    private void findEnd() {
        end = base.getTokenEnd();
        if (base.getTokenType() != SlangTokenTypes.PREPROCESSOR || start >= end) return;
        CharSequence text = base.getBufferSequence();
        int limit = end;
        end = start + 1;
        if (Character.isJavaIdentifierPart(text.charAt(start))) {
            while (end < limit && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        }
    }

    @Override public int getState() {
        return start > base.getTokenStart() ? base.getTokenStart() - start : base.getState();
    }
    @Override public IElementType getTokenType() { return base.getTokenType(); }
    @Override public int getTokenStart() { return start; }
    @Override public int getTokenEnd() { return end; }
    @Override public CharSequence getBufferSequence() { return base.getBufferSequence(); }
    @Override public int getBufferEnd() { return base.getBufferEnd(); }
    @Override public void advance() {
        start = end;
        if (end >= base.getTokenEnd()) {
            base.advance();
            start = base.getTokenStart();
        }
        findEnd();
    }
    @Override public LexerPosition getCurrentPosition() {
        return new Position(start, end, getState(), base.getCurrentPosition());
    }
    @Override public void restore(LexerPosition position) {
        Position saved = (Position) position;
        base.restore(saved.basePosition()); start = saved.offset(); end = saved.end();
    }
    private record Position(int offset, int end, int state, LexerPosition basePosition) implements LexerPosition {
        @Override public int getOffset() { return offset; }
        @Override public int getState() { return state; }
    }
}
