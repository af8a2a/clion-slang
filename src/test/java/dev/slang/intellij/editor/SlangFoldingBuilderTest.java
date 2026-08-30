package dev.slang.intellij.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import dev.slang.intellij.lang.SlangTokenTypes;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlangFoldingBuilderTest {
    @Test
    public void foldsRiderStyleSectionAndUsesFirstMeaningfulLine() {
        String source = """
                void before();
                    // ------------------------------------------------------------------
                    // Radiance cache integration (reference: RTXGI v2 SDK)
                    //
                    // The same entry point is compiled into several permutations:
                    // ------------------------------------------------------------------
                #if defined(SHARC_UPDATE)
                """;

        List<SlangFoldingBuilder.FoldRegion> regions = regions(source);

        assertEquals(1, regions.size());
        SlangFoldingBuilder.FoldRegion region = regions.getFirst();
        assertEquals(source.indexOf("// ---"), region.startOffset());
        assertEquals(source.lastIndexOf("// ---") + "// ------------------------------------------------------------------".length(),
                region.endOffset());
        assertEquals("// Radiance cache integration (reference: RTXGI v2 SDK) ...",
                region.placeholderText());
        assertEquals(source.substring(region.startOffset(), region.endOffset()),
                source.substring(source.indexOf("// ---"), region.endOffset()));
    }

    @Test
    public void supportsCrLfAndKeepsEmptyCommentLinesInsideTheBlock() {
        String source = "    // First paragraph\r\n"
                + "    //\r\n"
                + "    // Second paragraph\r\n"
                + "    uint value;\r\n";

        SlangFoldingBuilder.FoldRegion region = regions(source).getFirst();

        assertEquals(3, source.substring(region.startOffset(), region.endOffset()).split("//", -1).length - 1);
        assertEquals("// First paragraph ...", region.placeholderText());
        assertEquals(source.indexOf("// First"), region.startOffset());
        assertEquals(source.indexOf("\r\n    uint value"), region.endOffset());
    }

    @Test
    public void blankLinesDifferentIndentationAndCommentKindsSplitRuns() {
        String source = """
                // one
                // two

                // three
                // four
                    // nested one
                    // nested two
                /// docs one
                /// docs two
                """;

        List<SlangFoldingBuilder.FoldRegion> regions = regions(source);

        assertEquals(4, regions.size());
        assertEquals("// one ...", regions.get(0).placeholderText());
        assertEquals("// three ...", regions.get(1).placeholderText());
        assertEquals("// nested one ...", regions.get(2).placeholderText());
        assertEquals("/// docs one ...", regions.get(3).placeholderText());
    }

    @Test
    public void ignoresSingleAndTrailingCommentsAndCommentMarkersInStrings() {
        String source = """
                // single
                float value = 1.0; // trailing one
                float other = 2.0; // trailing two
                string url = "https://example.invalid//shader";
                string raw = R"tag(
                // raw string content, not a comment
                // still raw string content
                )tag";
                """;

        assertTrue(regions(source).isEmpty());
    }

    @Test
    public void foldsMultilineBlockAndDocCommentsButNotSingleLineBlocks() {
        String source = """
                /* one line */
                /* --------------------
                 * Resource binding contract
                 * -------------------- */
                /**
                 * Returns the sampled radiance.
                 * @return RGB radiance.
                 */
                /** one line */
                """;

        List<SlangFoldingBuilder.FoldRegion> regions = regions(source);

        assertEquals(2, regions.size());
        assertEquals("/* Resource binding contract ... */", regions.get(0).placeholderText());
        assertEquals("/** Returns the sampled radiance. ... */", regions.get(1).placeholderText());
        assertTrue(source.substring(regions.get(0).startOffset(), regions.get(0).endOffset()).endsWith("*/"));
        assertTrue(source.substring(regions.get(1).startOffset(), regions.get(1).endOffset()).endsWith("*/"));
    }

    @Test
    public void foldsUnterminatedMultilineBlockToEndOfFile() {
        String source = "/* Header\n * unfinished body";

        SlangFoldingBuilder.FoldRegion region = regions(source).getFirst();

        assertEquals(0, region.startOffset());
        assertEquals(source.length(), region.endOffset());
        assertEquals("/* Header ... */", region.placeholderText());
    }

    @Test
    public void abbreviatesLongUnicodeSummariesWithoutSplittingSurrogates() {
        String summary = "渲染路径".repeat(20) + " 😀 suffix";
        String source = "// " + summary + "\n// details";

        String placeholder = regions(source).getFirst().placeholderText();

        assertTrue(placeholder.startsWith("// 渲染路径"));
        assertTrue(placeholder.endsWith(" ..."));
        assertTrue(placeholder.codePointCount(0, placeholder.length()) <= 67);
        assertFalse(Character.isHighSurrogate(placeholder.charAt(placeholder.length() - 5)));
    }

    @Test
    public void builderPublishesExplicitExpandedByDefaultDescriptors() {
        String source = "// Summary\n// details\nuint value;";
        ASTNode commentNode = commentNode();
        ASTNode rootNode = rootNode(source.length(), commentNode);
        PsiElement root = proxy(PsiElement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getNode" -> rootNode;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(method.toString());
        });
        Document document = proxy(Document.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getImmutableCharSequence" -> source;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(method.toString());
        });

        SlangFoldingBuilder builder = new SlangFoldingBuilder();
        FoldingDescriptor[] descriptors = builder.buildFoldRegions(root, document, false);

        assertEquals(1, descriptors.length);
        assertSame(commentNode, descriptors[0].getElement());
        assertEquals("// Summary ...", descriptors[0].getPlaceholderText());
        assertEquals(source.indexOf("\nuint value"), descriptors[0].getRange().getEndOffset());
        assertFalse(builder.isCollapsedByDefault(rootNode));
    }

    @Test
    public void skipsDescriptorsWhenTheDocumentIsAheadOfPsi() {
        String source = "// Summary\n// details";
        ASTNode staleLeaf = proxy(ASTNode.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getStartOffset" -> 0;
            case "getElementType" -> SlangTokenTypes.IDENTIFIER;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(method.toString());
        });
        ASTNode rootNode = rootNode(source.length(), staleLeaf);
        PsiElement root = proxy(PsiElement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getNode" -> rootNode;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(method.toString());
        });
        Document document = proxy(Document.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getImmutableCharSequence" -> source;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException(method.toString());
        });

        assertEquals(0, new SlangFoldingBuilder().buildFoldRegions(root, document, true).length);
    }

    private static List<SlangFoldingBuilder.FoldRegion> regions(String source) {
        return SlangFoldingBuilder.findCommentFoldRegions(source);
    }

    private static ASTNode commentNode() {
        return proxy(ASTNode.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getStartOffset" -> 0;
            case "getElementType" -> SlangTokenTypes.LINE_COMMENT;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Slang test comment";
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static ASTNode rootNode(int textLength, ASTNode firstLeaf) {
        return proxy(ASTNode.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getStartOffset" -> 0;
            case "getTextRange" -> new TextRange(0, textLength);
            case "findLeafElementAt" -> firstLeaf;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Slang test root";
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
