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
                "SemanticHighlighting.slang",
                "StructuredBufferHighlighting.slang"
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

    @Test
    public void separatesIncludeDirectivePathsAndComments() {
        String source = "#include \"ClusterLightGridCommon.slang\" // lighting\n"
                + "  #  include <lighting/Common.slangh>\n"
                + "#include /* comment */ \"dir\\header.slang\"\n"
                + "#include HEADER_MACRO\n#define HEADER_MACRO \"other.slang\"\n"
                + "bool result = left < right;";
        List<Token> tokens = significantTokens(source);
        assertToken(tokens, "#include", SlangTokenTypes.PREPROCESSOR);
        assertToken(tokens, "#  include", SlangTokenTypes.PREPROCESSOR);
        assertToken(tokens, "\"ClusterLightGridCommon.slang\"", SlangTokenTypes.INCLUDE_PATH);
        assertToken(tokens, "<lighting/Common.slangh>", SlangTokenTypes.INCLUDE_PATH);
        assertToken(tokens, "\"dir\\header.slang\"", SlangTokenTypes.INCLUDE_PATH);
        assertToken(tokens, "// lighting", SlangTokenTypes.LINE_COMMENT);
        assertToken(tokens, "/* comment */", SlangTokenTypes.BLOCK_COMMENT);
        assertToken(tokens, "HEADER_MACRO", SlangTokenTypes.IDENTIFIER);
        assertToken(tokens, "#define HEADER_MACRO \"other.slang\"", SlangTokenTypes.PREPROCESSOR);
        assertToken(tokens, "<", SlangTokenTypes.OPERATOR);
    }

    @Test
    public void includeHighlightingRestartsAndStopsAtNewlines() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String source = "#include \\" + newline + "  <continued.slang>" + newline
                    + "#include /* first" + newline + "second */ \"comment.slang\"" + newline
                    + "#include" + newline + "<ordinary>" + newline
                    + "#include \"unfinished" + newline + "float value;" + newline
                    + "#include_next <notAnInclude>";
            List<Token> tokens = lex(source);
            assertToken(tokens, "<continued.slang>", SlangTokenTypes.INCLUDE_PATH);
            assertToken(tokens, "\"comment.slang\"", SlangTokenTypes.INCLUDE_PATH);
            assertToken(tokens, "ordinary", SlangTokenTypes.IDENTIFIER);
            assertToken(tokens, "float", SlangTokenTypes.TYPE_KEYWORD);
            assertToken(tokens, "\"unfinished", SlangTokenTypes.INCLUDE_PATH);
            assertToken(tokens, "#include_next <notAnInclude>", SlangTokenTypes.PREPROCESSOR);
            int end = 0;
            for (Token expected : tokens) {
                assertEquals(end, expected.start());
                assertTrue(expected.end() > expected.start());
                end = expected.end();
                SlangLexer restarted = new SlangLexer();
                restarted.start(source, expected.start(), source.length(), expected.state());
                assertSame(expected.text(), expected.type(), restarted.getTokenType());
                assertEquals(expected.text(), expected.end(), restarted.getTokenEnd());
            }
            assertEquals(source.length(), end);
        }
    }

    private static List<Token> significantTokens(String source) {
        return lex(source).stream()
                .filter(token -> token.type() != SlangTokenTypes.WHITE_SPACE)
                .toList();
    }

    @Test public void recognizesStructuredBufferFamilyWithoutGuessingGenericOrComparisonIdentifiers() {
        String source = "StructuredBuffer<HitEntry> g_GBuffer; RWStructuredBuffer<uint> output; "
                + "AppendStructuredBuffer<Box<float4>> append; ConsumeStructuredBuffer<T> consume; "
                + "RasterizerOrderedStructuredBuffer<T> ordered; StructuredBufferOther value; "
                + "bool result = left < right && right > 0; /* RWStructuredBuffer<T> */ \"StructuredBuffer<X>\"";
        var tokens = significantTokens(source);
        for (String name : List.of("StructuredBuffer", "RWStructuredBuffer", "AppendStructuredBuffer",
                "ConsumeStructuredBuffer", "RasterizerOrderedStructuredBuffer"))
            assertToken(tokens, name, SlangTokenTypes.STRUCTURED_BUFFER_TYPE);
        for (String name : List.of("HitEntry", "Box", "T", "left", "right", "StructuredBufferOther"))
            assertToken(tokens, name, SlangTokenTypes.IDENTIFIER);
        assertToken(tokens, "uint", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "float4", SlangTokenTypes.TYPE_KEYWORD);
        assertToken(tokens, "/* RWStructuredBuffer<T> */", SlangTokenTypes.BLOCK_COMMENT);
        assertToken(tokens, "\"StructuredBuffer<X>\"", SlangTokenTypes.STRING_LITERAL);
        for (Token expected : lex(source)) {
            SlangLexer restarted = new SlangLexer();
            restarted.start(source, expected.start(), source.length(), expected.state());
            assertSame(expected.text(), expected.type(), restarted.getTokenType());
            assertEquals(expected.text(), expected.end(), restarted.getTokenEnd());
        }
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
