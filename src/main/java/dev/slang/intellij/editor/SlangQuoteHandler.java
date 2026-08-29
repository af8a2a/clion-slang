package dev.slang.intellij.editor;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;
import dev.slang.intellij.lang.SlangTokenTypes;

/** Inserts and skips matching single/double quotes inside Slang files. */
public final class SlangQuoteHandler extends SimpleTokenSetQuoteHandler {
    public SlangQuoteHandler() {
        super(SlangTokenTypes.STRING_LITERAL, SlangTokenTypes.CHARACTER_LITERAL);
    }
}
