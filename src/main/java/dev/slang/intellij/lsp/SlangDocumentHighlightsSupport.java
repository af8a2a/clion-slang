package dev.slang.intellij.lsp;

import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport;
import com.intellij.psi.PsiFile;
import dev.slang.intellij.lang.SlangLanguage;
import org.jetbrains.annotations.NotNull;

/** Enables the Native LSP occurrence-highlighting pipeline for Slang PSI files. */
final class SlangDocumentHighlightsSupport extends LspDocumentHighlightsSupport {
    static final SlangDocumentHighlightsSupport INSTANCE = new SlangDocumentHighlightsSupport();

    private SlangDocumentHighlightsSupport() {
    }

    @Override
    public boolean shouldAskServerForDocumentHighlights(@NotNull PsiFile psiFile) {
        return psiFile.getLanguage() == SlangLanguage.INSTANCE;
    }
}
