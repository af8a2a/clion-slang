package dev.slang.intellij.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/** A lightweight PSI file whose leaf nodes come directly from {@link SlangLexer}. */
public final class SlangFile extends PsiFileBase {
    public SlangFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, SlangLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return SlangFileType.INSTANCE;
    }

    @Override
    public @NotNull String toString() {
        return "Slang File";
    }
}
