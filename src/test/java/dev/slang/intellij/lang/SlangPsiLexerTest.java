package dev.slang.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.psi.tree.IElementType;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SlangPsiLexerTest {
    private record Token(int start, int end, IElementType type, String text) {}
    private static List<Token> tokens(Lexer lexer, String source) {
        lexer.start(source); List<Token> result = new ArrayList<>();
        int previous = 0;
        while (lexer.getTokenType() != null) {
            assertEquals(previous, lexer.getTokenStart());
            assertTrue(lexer.getTokenEnd() > previous);
            result.add(new Token(lexer.getTokenStart(), lexer.getTokenEnd(), lexer.getTokenType(),
                    source.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
            previous = lexer.getTokenEnd(); lexer.advance();
        }
        assertEquals(source.length(), previous); return result;
    }
    private static void assertLeaf(List<Token> tokens, String source, String symbol) {
        int start = source.indexOf(symbol);
        Token leaf = tokens.stream().filter(t -> t.start <= start && start < t.end).findFirst().orElseThrow();
        assertEquals(symbol, leaf.text); assertEquals(start, leaf.start); assertEquals(start + symbol.length(), leaf.end);
    }

    @Test public void parserSplitsRealMacroNamesCalleesAndMembersIntoExactRanges() {
        String source = "#define gDlssRrAlbedo resolveDescriptor(gOpenPBRParameters.albedo)\n";
        var list = tokens(new SlangParserDefinition().createLexer(null), source);
        for (String word : List.of("define", "gDlssRrAlbedo", "resolveDescriptor", "gOpenPBRParameters", "albedo"))
            assertLeaf(list, source, word);
    }

    @Test public void continuedMacrosAndCrLfKeepUtf16RangesWithoutGaps() {
        String source = "// 中文 😀\r\n#define SAMPLE(x) \\\r\n resolveDescriptor(parameters.source) + x\r\nfloat value;\r\n";
        var list = tokens(new SlangPsiLexer(), source);
        for (String word : List.of("SAMPLE", "resolveDescriptor", "parameters", "source", "value"))
            assertLeaf(list, source, word);
    }

    @Test public void positionRestoresInsideExpandedDirective() {
        String source = "#define MACRO resolveDescriptor(params.source)\nfloat value;";
        Lexer lexer = new SlangPsiLexer(); lexer.start(source);
        while (lexer.getTokenType() != null) {
            var position = lexer.getCurrentPosition();
            int start = lexer.getTokenStart(), end = lexer.getTokenEnd(); var type = lexer.getTokenType();
            for (int i = 0; i < 4 && lexer.getTokenType() != null; i++) lexer.advance();
            lexer.restore(position);
            assertEquals(start, lexer.getTokenStart()); assertEquals(end, lexer.getTokenEnd()); assertSame(type, lexer.getTokenType());
            lexer.advance();
        }
    }

    @Test public void everyFragmentCanRestartWithSavedState() {
        for (String source : List.of("#define MACRO resolveDescriptor(params.source)\nfloat value;",
                "#define MACRO(x) \\\r\n resolveDescriptor(x)\r\n", "#include \"Common.slang\"\n")) {
            Lexer lexer = new SlangPsiLexer(); lexer.start(source);
            while (lexer.getTokenType() != null) {
                Lexer restarted = new SlangPsiLexer();
                restarted.start(source, lexer.getTokenStart(), source.length(), lexer.getState());
                assertEquals(lexer.getTokenStart(), restarted.getTokenStart());
                assertEquals(lexer.getTokenEnd(), restarted.getTokenEnd());
                assertSame(lexer.getTokenType(), restarted.getTokenType());
                lexer.advance();
            }
        }
    }

    @Test public void ordinaryTokensAndIncludePathsKeepExistingRanges() {
        for (String source : List.of("float f(float x) { return sin(x); }", "import ShaderCore;\nusing Metallic;",
                "// comment\nfloat x; /* comment */", "#include \"Common.slang\"\nfloat x;")) {
            var original = tokens(new SlangLexer(), source);
            var actual = tokens(new SlangPsiLexer(), source);
            for (Token token : original) {
                if (token.type != SlangTokenTypes.PREPROCESSOR) assertTrue(actual.contains(token));
            }
        }
    }

    @Test public void highlightingLexerStillReturnsWholeDirective() {
        String source = "#define gSource resolveDescriptor(gParams.source)\n";
        var original = tokens(new SlangLexer(), source);
        assertEquals(source.stripTrailing(), original.getFirst().text);
        assertSame(SlangTokenTypes.PREPROCESSOR, original.getFirst().type);
    }
}
