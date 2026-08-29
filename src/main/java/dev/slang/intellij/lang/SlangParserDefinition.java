package dev.slang.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a deliberately flat PSI tree from the existing tolerant lexer.
 *
 * <p>Semantic parsing remains slangd's responsibility. The leaf tree gives editor
 * actions precise token ranges, which is required for Ctrl+hover to underline only
 * the identifier under the mouse instead of the complete plain-text file.</p>
 */
public final class SlangParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(SlangLanguage.INSTANCE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new SlangLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new FlatParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(TokenType.WHITE_SPACE);
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return SlangTokenTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return SlangTokenTypes.STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new SlangFile(viewProvider);
    }

    private static final class FlatParser implements PsiParser {
        @Override
        public @NotNull ASTNode parse(@NotNull com.intellij.psi.tree.IElementType root, @NotNull PsiBuilder builder) {
            PsiBuilder.Marker file = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            file.done(root);
            return builder.getTreeBuilt();
        }
    }
}
