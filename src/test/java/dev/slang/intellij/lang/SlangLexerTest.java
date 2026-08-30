package dev.slang.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlangLexerTest {
    @Test
    public void coversRepresentativeSlangWithoutGaps() throws IOException {
        for (String fileName : List.of(
                "Basic.slang",
                "Interface.slang",
                "ModernSyntax.slang",
                "BrokenSyntax.slang",
                "SemanticHighlighting.slang"
        )) {
            String source = Files.readString(Path.of("src", "test", "testData", "slang", fileName));
            List<Token> tokens = lex(source);

            assertFalse(fileName + " must produce tokens", tokens.isEmpty());
            assertEquals(fileName + " must start at zero", 0, tokens.getFirst().start());
            assertEquals(fileName + " must consume the whole input", source.length(), tokens.getLast().end());

            int expectedStart = 0;
            for (Token token : tokens) {
                assertEquals(fileName + " has a token gap", expectedStart, token.start());
                assertTrue(fileName + " has an empty token", token.end() > token.start());
                expectedStart = token.end();
            }
        }
    }

    @Test
    public void classifiesSlangSpecificConstructs() {
        String source = "interface I { associatedtype T; } extension<T> T { } "
                + "$for __include spirv_asm float3 Texture2D true SV_Position";
        List<Token> tokens = significantTokens(source);

        assertToken(tokens, "interface", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "associatedtype", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "extension", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "$for", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "__include", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "spirv_asm", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "float3", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "Texture2D", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "true", SlangTokenTypes.BOOLEAN_LITERAL);
        assertToken(tokens, "SV_Position", SlangTokenTypes.SEMANTIC);
    }

    @Test
    public void keepsArrayIdentifiersDistinctFromAttributes() {
        String source = "[shader(\"compute\")] float values[threadIndex];";
        List<Token> tokens = significantTokens(source);

        assertToken(tokens, "shader", SlangTokenTypes.ATTRIBUTE);
        assertToken(tokens, "threadIndex", SlangTokenTypes.IDENTIFIER);
    }

    @Test
    public void classifiesMetallicStyleResourceAndEntryPointSyntax() {
        String source = """
                [[vk::binding(1, 0)]] RWTexture2D<float4> gOutput;
                [shader("compute")]
                [numthreads(8, 8, 1)]
                void main(uint3 dispatchThreadID : SV_DispatchThreadID, uniform Push push) {}
                """;
        List<Token> tokens = significantTokens(source);

        assertToken(tokens, "vk", SlangTokenTypes.ATTRIBUTE);
        assertToken(tokens, "binding", SlangTokenTypes.ATTRIBUTE);
        assertToken(tokens, "RWTexture2D", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "float4", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "shader", SlangTokenTypes.ATTRIBUTE);
        assertToken(tokens, "numthreads", SlangTokenTypes.ATTRIBUTE);
        assertToken(tokens, "uniform", SlangTokenTypes.KEYWORD);
        assertToken(tokens, "SV_DispatchThreadID", SlangTokenTypes.SEMANTIC);
    }

    @Test
    public void supportsMultilineCommentsAndRawStringRestartStates() {
        String source = "/* first\nsecond\nthird */ R\"tag(line 1\nline 2)tag\" let value = 1;";
        List<Token> tokens = lex(source);

        assertTrue(tokens.stream().filter(token -> token.type() == SlangTokenTypes.BLOCK_COMMENT).count() >= 3);
        assertTrue(tokens.stream().filter(token -> token.type() == SlangTokenTypes.STRING_LITERAL).count() >= 2);

        for (Token expected : tokens) {
            SlangLexer restarted = new SlangLexer();
            restarted.start(source, expected.start(), source.length(), expected.state());
            assertSame("token type differs after restart at " + expected.start(), expected.type(), restarted.getTokenType());
            assertEquals("token end differs after restart at " + expected.start(), expected.end(), restarted.getTokenEnd());
        }
    }

    @Test
    public void consumesUnknownCharactersAsBadCharacters() {
        String source = "let value = `unexpected`;";
        List<Token> tokens = lex(source);
        assertTrue(tokens.stream().anyMatch(token -> token.type() == SlangTokenTypes.BAD_CHARACTER));
        assertEquals(source.length(), tokens.getLast().end());
    }

    private static List<Token> significantTokens(String source) {
        return lex(source).stream()
                .filter(token -> token.type() != SlangTokenTypes.WHITE_SPACE)
                .toList();
    }

    private static List<Token> lex(String source) {
        SlangLexer lexer = new SlangLexer();
        lexer.start(source, 0, source.length(), 0);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new Token(
                    lexer.getTokenType(),
                    lexer.getTokenStart(),
                    lexer.getTokenEnd(),
                    lexer.getState(),
                    source.substring(lexer.getTokenStart(), lexer.getTokenEnd())
            ));
            lexer.advance();
        }
        return tokens;
    }

    private static void assertToken(List<Token> tokens, String text, IElementType expectedType) {
        Token token = tokens.stream()
                .filter(candidate -> candidate.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing token: " + text));
        assertSame(text, expectedType, token.type());
    }

    private record Token(IElementType type, int start, int end, int state, String text) {
    }
}
