package dev.slang.intellij.lsp;

import com.intellij.lang.Language;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.platform.lsp.api.customization.LspFindReferencesSupport;
import com.intellij.psi.PsiFile;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;
import dev.slang.intellij.lang.SlangLanguage;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlangSemanticTokensSupportTest {
    private final SlangSemanticTokensSupport support = SlangSemanticTokensSupport.INSTANCE;
    private final SlangDocumentHighlightsSupport documentHighlights =
            SlangDocumentHighlightsSupport.INSTANCE;

    @Test
    public void advertisesTheStandardStockAndEnhancedVocabulary() {
        List<String> tokenTypes = support.getTokenTypes();
        List<String> tokenModifiers = support.getTokenModifiers();

        assertEquals(25, tokenTypes.size());
        assertEquals(tokenTypes.size(), new HashSet<>(tokenTypes).size());
        assertTrue(tokenTypes.containsAll(List.of(
                "type", "enumMember", "variable", "parameter", "function", "property",
                "namespace", "keyword", "macro", "string",
                "class", "struct", "interface", "enum", "typeParameter", "method", "decorator",
                "slangSemantic", "slangSwizzle"
        )));
        assertEquals(List.of("slangSemantic", "slangSwizzle"), tokenTypes.subList(23, 25));

        assertEquals(10, tokenModifiers.size());
        assertEquals(tokenModifiers.size(), new HashSet<>(tokenModifiers).size());
        assertTrue(tokenModifiers.containsAll(List.of(
                "declaration", "definition", "readonly", "static", "defaultLibrary"
        )));
    }

    @Test
    public void requestsSemanticTokensOnlyForSlangPsi() {
        assertTrue(support.shouldAskServerForSemanticTokens(psiFile(SlangLanguage.INSTANCE)));
        assertFalse(support.shouldAskServerForSemanticTokens(psiFile(Language.ANY)));
    }

    @Test
    public void customizationPublishesTheSlangSemanticSupport() {
        assertSame(support, new SlangLspCustomization().getSemanticTokensCustomizer());
    }

    @Test
    public void customizationRetainsTheDefaultFindReferencesSupport() {
        assertTrue(
                new SlangLspCustomization().getFindReferencesCustomizer()
                        instanceof LspFindReferencesSupport
        );
    }

    @Test
    public void requestsDocumentHighlightsOnlyForSlangPsi() {
        assertTrue(documentHighlights.shouldAskServerForDocumentHighlights(
                psiFile(SlangLanguage.INSTANCE)));
        assertFalse(documentHighlights.shouldAskServerForDocumentHighlights(psiFile(Language.ANY)));
        assertSame(
                documentHighlights,
                new SlangLspCustomization().getDocumentHighlightsCustomizer()
        );
    }

    @Test
    public void descriptorOverridesTheCurrentCustomizationEntryPoint() throws Exception {
        assertSame(
                SlangLspServerDescriptor.class,
                SlangLspServerDescriptor.class.getMethod("getLspCustomization").getDeclaringClass()
        );
    }

    @Test
    public void mapsStockTokenTypesToSlangColors() {
        Map<String, TextAttributesKey> expected = Map.ofEntries(
                Map.entry("type", SlangSemanticColors.TYPE),
                Map.entry("enumMember", SlangSemanticColors.ENUM_MEMBER),
                Map.entry("variable", SlangSemanticColors.VARIABLE),
                Map.entry("parameter", SlangSemanticColors.PARAMETER),
                Map.entry("function", SlangSemanticColors.FUNCTION),
                Map.entry("property", SlangSemanticColors.PROPERTY),
                Map.entry("namespace", SlangSemanticColors.NAMESPACE),
                Map.entry("keyword", SlangSyntaxHighlighter.KEYWORD),
                Map.entry("macro", SlangSemanticColors.MACRO),
                Map.entry("string", SlangSyntaxHighlighter.STRING)
        );

        expected.forEach((type, color) ->
                assertSame(type, color, support.getTextAttributesKey(type, List.of())));
    }

    @Test
    public void mapsEnhancedTypesAndModifierCombinations() {
        assertSame(SlangSemanticColors.CLASS, color("class"));
        assertSame(SlangSemanticColors.STRUCT, color("struct"));
        assertSame(SlangSemanticColors.INTERFACE, color("interface"));
        assertSame(SlangSemanticColors.ENUM, color("enum"));
        assertSame(SlangSemanticColors.TYPE_PARAMETER, color("typeParameter"));
        assertSame(SlangSemanticColors.DECORATOR, color("decorator"));

        assertSame(SlangSemanticColors.BUILTIN_TYPE, color("struct", "defaultLibrary"));
        assertSame(SlangSemanticColors.INTRINSIC, color("function", "defaultLibrary"));
        assertSame(SlangSemanticColors.BUILTIN_SYMBOL, color("variable", "defaultLibrary"));
        assertSame(SlangSemanticColors.READONLY_VARIABLE, color("variable", "readonly", "static"));
        assertSame(SlangSemanticColors.STATIC_VARIABLE, color("variable", "static"));
        assertSame(SlangSemanticColors.READONLY_PROPERTY, color("property", "readonly"));
        assertSame(SlangSemanticColors.STATIC_PROPERTY, color("property", "static"));
        assertSame(SlangSemanticColors.STATIC_METHOD, color("method", "static"));
        assertSame(SlangSemanticColors.IDENTIFIER, color("slangFutureToken"));
    }

    @Test
    public void mapsM3ShaderRolesToDedicatedColors() {
        assertSame(SlangSemanticColors.SHADER_SEMANTIC, color("slangSemantic"));
        assertSame(SlangSemanticColors.SWIZZLE, color("slangSwizzle"));
    }

    private TextAttributesKey color(String tokenType, String... modifiers) {
        return support.getTextAttributesKey(tokenType, List.of(modifiers));
    }

    private static PsiFile psiFile(Language language) {
        return (PsiFile) Proxy.newProxyInstance(
                SlangSemanticTokensSupportTest.class.getClassLoader(),
                new Class<?>[]{PsiFile.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getLanguage" -> language;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PsiFile(" + language.getID() + ")";
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
    }

}
