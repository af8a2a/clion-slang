package dev.slang.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/** A lexer token that is owned by {@link SlangLanguage}. */
public final class SlangTokenType extends IElementType {
    public SlangTokenType(@NonNls @NotNull String debugName) {
        super(debugName, SlangLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "SLANG_" + super.toString();
    }
}
