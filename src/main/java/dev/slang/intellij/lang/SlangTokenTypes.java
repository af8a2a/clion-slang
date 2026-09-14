package dev.slang.intellij.lang;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/** Token vocabulary shared by the handwritten lexer and editor integrations. */
public final class SlangTokenTypes {
    private SlangTokenTypes() {
    }

    public static final IElementType WHITE_SPACE = TokenType.WHITE_SPACE;
    public static final IElementType BAD_CHARACTER = TokenType.BAD_CHARACTER;

    public static final IElementType IDENTIFIER = token("IDENTIFIER");
    public static final IElementType MODULE_NAME = token("MODULE_NAME");
    public static final IElementType MODULE_PATH = token("MODULE_PATH");
    public static final IElementType NAMESPACE_NAME = token("NAMESPACE_NAME");
    public static final IElementType KEYWORD = token("KEYWORD");
    public static final IElementType TYPE_KEYWORD = token("TYPE_KEYWORD");
    public static final IElementType STRUCTURED_BUFFER_TYPE = token("STRUCTURED_BUFFER_TYPE");
    public static final IElementType BOOLEAN_LITERAL = token("BOOLEAN_LITERAL");
    public static final IElementType NUMBER_LITERAL = token("NUMBER_LITERAL");
    public static final IElementType STRING_LITERAL = token("STRING_LITERAL");
    public static final IElementType CHARACTER_LITERAL = token("CHARACTER_LITERAL");
    public static final IElementType PREPROCESSOR = token("PREPROCESSOR");
    public static final IElementType INCLUDE_PATH = token("INCLUDE_PATH");
    public static final IElementType ATTRIBUTE = token("ATTRIBUTE");
    public static final IElementType SEMANTIC = token("SEMANTIC");

    public static final IElementType LINE_COMMENT = token("LINE_COMMENT");
    public static final IElementType BLOCK_COMMENT = token("BLOCK_COMMENT");
    public static final IElementType DOC_COMMENT = token("DOC_COMMENT");

    public static final IElementType OPERATOR = token("OPERATOR");
    public static final IElementType LBRACE = token("LBRACE");
    public static final IElementType RBRACE = token("RBRACE");
    public static final IElementType LBRACKET = token("LBRACKET");
    public static final IElementType RBRACKET = token("RBRACKET");
    public static final IElementType LPAREN = token("LPAREN");
    public static final IElementType RPAREN = token("RPAREN");
    public static final IElementType SEMICOLON = token("SEMICOLON");
    public static final IElementType COMMA = token("COMMA");
    public static final IElementType DOT = token("DOT");
    public static final IElementType COLON = token("COLON");

    public static final TokenSet COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT);
    public static final TokenSet STRINGS = TokenSet.create(STRING_LITERAL, CHARACTER_LITERAL, MODULE_PATH);
    public static final TokenSet BRACES = TokenSet.create(LBRACE, RBRACE);
    public static final TokenSet BRACKETS = TokenSet.create(LBRACKET, RBRACKET);
    public static final TokenSet PARENTHESES = TokenSet.create(LPAREN, RPAREN);

    private static IElementType token(String name) {
        return new SlangTokenType(name);
    }
}
