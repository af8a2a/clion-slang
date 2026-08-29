package dev.slang.intellij.editor;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import dev.slang.intellij.lang.SlangTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Matching and automatic closing for Slang's three unambiguous brace pairs. */
public final class SlangBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = {
            new BracePair(SlangTokenTypes.LBRACE, SlangTokenTypes.RBRACE, true),
            new BracePair(SlangTokenTypes.LBRACKET, SlangTokenTypes.RBRACKET, false),
            new BracePair(SlangTokenTypes.LPAREN, SlangTokenTypes.RPAREN, false)
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(
            @NotNull IElementType leftBraceType,
            @Nullable IElementType contextType
    ) {
        return true;
    }

    @Override
    public int getCodeConstructStart(@NotNull PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
