package dev.slang.intellij.lsp;

import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer;
import org.jetbrains.annotations.NotNull;

/** Keeps Slang-specific LSP behavior behind the public customization surface. */
final class SlangLspCustomization extends LspCustomization {
    @Override
    public @NotNull LspDocumentHighlightsCustomizer getDocumentHighlightsCustomizer() {
        return SlangDocumentHighlightsSupport.INSTANCE;
    }

    @Override
    public @NotNull LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
        return SlangSemanticTokensSupport.INSTANCE;
    }
}
