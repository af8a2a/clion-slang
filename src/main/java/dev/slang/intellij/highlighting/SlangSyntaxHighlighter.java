package dev.slang.intellij.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;
import org.jetbrains.annotations.NotNull;

/** Maps the lightweight Slang lexer vocabulary to user-configurable editor colors. */
public final class SlangSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = key("SLANG.KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TYPE = key("SLANG.TYPE", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey NUMBER = key("SLANG.NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING = key("SLANG.STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey BOOLEAN = key("SLANG.BOOLEAN", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey LINE_COMMENT = key(
            "SLANG.LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = key(
            "SLANG.BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey DOC_COMMENT = key(
            "SLANG.DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT);
    public static final TextAttributesKey PREPROCESSOR = key(
            "SLANG.PREPROCESSOR", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey INCLUDE_PATH = key("SLANG.INCLUDE_PATH", STRING);
    public static final TextAttributesKey MODULE = key("SLANG.MODULE", SlangSemanticColors.NAMESPACE);
    public static final TextAttributesKey ATTRIBUTE = key(
            "SLANG.ATTRIBUTE", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey SEMANTIC = key(
            "SLANG.SEMANTIC", DefaultLanguageHighlighterColors.LABEL);
    public static final TextAttributesKey OPERATOR = key(
            "SLANG.OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BRACES = key(
            "SLANG.BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey BRACKETS = key(
            "SLANG.BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey PARENTHESES = key(
            "SLANG.PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey SEMICOLON = key(
            "SLANG.SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
    public static final TextAttributesKey COMMA = key("SLANG.COMMA", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = key("SLANG.DOT", DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey BAD_CHARACTER = key("SLANG.BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

    private static final TextAttributesKey[] EMPTY = TextAttributesKey.EMPTY_ARRAY;
    private static final TextAttributesKey[] KEYWORD_KEYS = pack(KEYWORD);
    private static final TextAttributesKey[] TYPE_KEYS = pack(TYPE);
    private static final TextAttributesKey[] STRUCTURED_BUFFER_KEYS = pack(SlangSemanticColors.STRUCTURED_BUFFER);
    private static final TextAttributesKey[] NUMBER_KEYS = pack(NUMBER);
    private static final TextAttributesKey[] STRING_KEYS = pack(STRING);
    private static final TextAttributesKey[] BOOLEAN_KEYS = pack(BOOLEAN);
    private static final TextAttributesKey[] LINE_COMMENT_KEYS = pack(LINE_COMMENT);
    private static final TextAttributesKey[] BLOCK_COMMENT_KEYS = pack(BLOCK_COMMENT);
    private static final TextAttributesKey[] DOC_COMMENT_KEYS = pack(DOC_COMMENT);
    private static final TextAttributesKey[] PREPROCESSOR_KEYS = pack(PREPROCESSOR);
    private static final TextAttributesKey[] INCLUDE_PATH_KEYS = pack(INCLUDE_PATH);
    private static final TextAttributesKey[] MODULE_KEYS = pack(MODULE);
    private static final TextAttributesKey[] NAMESPACE_KEYS = pack(SlangSemanticColors.NAMESPACE);
    private static final TextAttributesKey[] ATTRIBUTE_KEYS = pack(ATTRIBUTE);
    private static final TextAttributesKey[] SEMANTIC_KEYS = pack(SEMANTIC);
    private static final TextAttributesKey[] OPERATOR_KEYS = pack(OPERATOR);
    private static final TextAttributesKey[] BRACE_KEYS = pack(BRACES);
    private static final TextAttributesKey[] BRACKET_KEYS = pack(BRACKETS);
    private static final TextAttributesKey[] PARENTHESES_KEYS = pack(PARENTHESES);
    private static final TextAttributesKey[] SEMICOLON_KEYS = pack(SEMICOLON);
    private static final TextAttributesKey[] COMMA_KEYS = pack(COMMA);
    private static final TextAttributesKey[] DOT_KEYS = pack(DOT);
    private static final TextAttributesKey[] BAD_CHARACTER_KEYS = pack(BAD_CHARACTER);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new SlangLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == SlangTokenTypes.KEYWORD) return KEYWORD_KEYS;
        if (tokenType == SlangTokenTypes.MODULE_NAME) return MODULE_KEYS;
        if (tokenType == SlangTokenTypes.NAMESPACE_NAME) return NAMESPACE_KEYS;
        if (tokenType == SlangTokenTypes.TYPE_KEYWORD) return TYPE_KEYS;
        if (tokenType == SlangTokenTypes.STRUCTURED_BUFFER_TYPE) return STRUCTURED_BUFFER_KEYS;
        if (tokenType == SlangTokenTypes.NUMBER_LITERAL) return NUMBER_KEYS;
        if (tokenType == SlangTokenTypes.STRING_LITERAL || tokenType == SlangTokenTypes.CHARACTER_LITERAL) {
            return STRING_KEYS;
        }
        if (tokenType == SlangTokenTypes.BOOLEAN_LITERAL) return BOOLEAN_KEYS;
        if (tokenType == SlangTokenTypes.LINE_COMMENT) return LINE_COMMENT_KEYS;
        if (tokenType == SlangTokenTypes.BLOCK_COMMENT) return BLOCK_COMMENT_KEYS;
        if (tokenType == SlangTokenTypes.DOC_COMMENT) return DOC_COMMENT_KEYS;
        if (tokenType == SlangTokenTypes.PREPROCESSOR) return PREPROCESSOR_KEYS;
        if (tokenType == SlangTokenTypes.INCLUDE_PATH || tokenType == SlangTokenTypes.MODULE_PATH) return INCLUDE_PATH_KEYS;
        if (tokenType == SlangTokenTypes.ATTRIBUTE) return ATTRIBUTE_KEYS;
        if (tokenType == SlangTokenTypes.SEMANTIC) return SEMANTIC_KEYS;
        if (tokenType == SlangTokenTypes.OPERATOR || tokenType == SlangTokenTypes.COLON) return OPERATOR_KEYS;
        if (tokenType == SlangTokenTypes.LBRACE || tokenType == SlangTokenTypes.RBRACE) return BRACE_KEYS;
        if (tokenType == SlangTokenTypes.LBRACKET || tokenType == SlangTokenTypes.RBRACKET) return BRACKET_KEYS;
        if (tokenType == SlangTokenTypes.LPAREN || tokenType == SlangTokenTypes.RPAREN) return PARENTHESES_KEYS;
        if (tokenType == SlangTokenTypes.SEMICOLON) return SEMICOLON_KEYS;
        if (tokenType == SlangTokenTypes.COMMA) return COMMA_KEYS;
        if (tokenType == SlangTokenTypes.DOT) return DOT_KEYS;
        if (tokenType == SlangTokenTypes.BAD_CHARACTER) return BAD_CHARACTER_KEYS;
        return EMPTY;
    }

    private static TextAttributesKey key(String name, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(name, fallback);
    }
}
