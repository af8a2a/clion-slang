package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.editor.markup.TextAttributes;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;
import java.awt.Color;
import com.intellij.platform.lsp.impl.features.documentation.LspDocumentationDataKt;
import org.eclipse.lsp4j.MarkupContent;
import org.junit.Test;

import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.Assert.*;

public class SlangStructHoverPresentationTest {
    static final SlangStructHoverPresentation.Style LIGHT =
            new SlangStructHoverPresentation.Style("#0f54d6", "#300073", "#6b2fba", "#ab2f6b", "#383838");
    static final String SOURCE = "```slang\nstruct InstanceData\n```\n\n(namespace `UnifiedRT`)\n\n"
            + "**Natural layout (bytes)**  \nSize: `176`  \nAlignment: `4`  \nPadding: `0`\n\n"
            + "\n\n---\n\n[CommonStructs.slang:148](<file:///E:/Shaders/CommonStructs.slang#L148>)\n";

    @Test public void chineseLayoutMatchesRiderRowsAndCompactSourceLink() {
        String html = SlangStructHoverPresentation.format(SOURCE, LIGHT, Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.startsWith("<pre><span style=\"color:#0f54d6\">struct</span> <b>"));
        assertTrue(html.contains("<span style=\"color:#300073\">InstanceData</span></b>"));
        assertTrue(html.contains("\n  (<span style=\"color:#0f54d6\">namespace</span> <span style=\"color:#6b2fba\">UnifiedRT</span> 中)"));
        assertTrue(html.contains("大小：<span style=\"color:#ab2f6b\">176</span><br/>对齐："));
        assertFalse(html.contains("填充"));
        assertFalse(html.contains("**Natural layout"));
        assertFalse(html.contains("`176`"));
        assertTrue(html.contains("href=\"file:///E:/Shaders/CommonStructs.slang#L148\""));
        assertTrue(html.contains("title=\"CommonStructs.slang:148\""));
        assertTrue(html.contains(">CommonStructs.slang</span></code></a>"));
        assertEquals(html, SlangStructHoverPresentation.format(html, LIGHT, Locale.SIMPLIFIED_CHINESE));
    }

    @Test public void preservesNonzeroPaddingStrideAndDocumentationWithCrLf() {
        String docs = "\nDocumentation with **emphasis**.\n```python\nprint('Size: `176`')\n```\n";
        String markdown = SOURCE.replace("Padding: `0`\n\n", "Padding: `3`\n\nArray stride: `180`\n\n" + docs)
                .replace("\n", "\r\n");
        String html = SlangStructHoverPresentation.format(markdown, LIGHT, Locale.ENGLISH);
        assertTrue(html.contains("<br/>Wasted padding: <span style=\"color:#ab2f6b\">3</span>"));
        assertTrue(html.contains("<br/>Array stride: <span style=\"color:#ab2f6b\">180</span>"));
        assertTrue(html.contains(docs.replace("\n", "\r\n")));
        assertFalse(html.contains(" 中)"));
    }

    @Test public void preservesUnknownLayoutAndEscapesGenericSignatures() {
        String source = SOURCE.replace("struct InstanceData", "struct Box<vector<float, 3>>")
                .replace("**Natural layout (bytes)**  \nSize: `176`  \nAlignment: `4`  \nPadding: `0`\n\n",
                        "Layout unavailable for this type.\n\n");
        String html = SlangStructHoverPresentation.format(source, LIGHT, Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.contains("Box&lt;vector&lt;float, 3&gt;&gt;"));
        assertTrue(html.contains("此类型的布局信息不可用。"));
        assertFalse(html.contains("大小："));
    }

    @Test public void sourceFileCharactersAreDecodedAndHtmlEscapedWithoutChangingTarget() {
        String url = "file:///E:/My%20Project/Hover%20%5B%E7%B1%BB%E5%9E%8B%5D%20%26%20%22x%22.slang#L3";
        String source = SOURCE.substring(0, SOURCE.indexOf("\n---"))
                + "\n---\n\n[Hover \\[类型\\] & \"x\".slang:3](<" + url + ">)\n";
        String html = SlangStructHoverPresentation.format(source, LIGHT, Locale.ENGLISH);
        assertTrue(html.contains("href=\"" + url + "\""));
        assertTrue(html.contains("Hover [类型] &amp; &quot;x&quot;.slang</span>"));
    }

    @Test public void usesSuppliedDarkPaletteWithoutBackgroundOrFixedLightColors() {
        var dark = new SlangStructHoverPresentation.Style("#cf8e6d", "#bcbec4", "#bcbec4", "#2aacb8", "#bcbec4");
        String html = SlangStructHoverPresentation.format(SOURCE, dark, Locale.ENGLISH);
        assertTrue(html.contains("color:#2aacb8\">176"));
        assertTrue(html.contains("color:#cf8e6d\">struct"));
        assertFalse(html.contains("#ab2f6b"));
        assertFalse(html.contains("background"));
    }

    @Test public void readsCustomColorsFromCurrentEditorScheme() {
        var scheme = new EditorColorsSchemeImpl(null);
        var color = new TextAttributes();
        color.setForegroundColor(new Color(0x123456));
        for (var key : new com.intellij.openapi.editor.colors.TextAttributesKey[]{SlangSyntaxHighlighter.KEYWORD,
                SlangSyntaxHighlighter.NUMBER, SlangSemanticColors.STRUCT, SlangSemanticColors.NAMESPACE, HighlighterColors.TEXT}) {
            scheme.setAttributes(key, color);
        }
        var style = SlangStructHoverPresentation.Style.from(scheme);
        assertEquals("#123456", style.keyword());
        assertEquals("#123456", style.struct());
        assertEquals("#123456", style.namespace());
        assertEquals("#123456", style.number());
        assertEquals("#123456", style.text());
    }

    @Test public void unknownFormatsAndNonStructHoversStayUnchanged() {
        for (String source : new String[]{"```slang\nstruct Hit\n```\n\nDefined in file.slang(3)",
                SOURCE.replace("struct InstanceData", "typedef InstanceData"),
                SOURCE.replace("Alignment: `4`", "Alignment: unknown"),
                SOURCE.replace("```slang", "```cpp"), "prose\n" + SOURCE}) {
            assertEquals(source, SlangStructHoverPresentation.format(source, LIGHT, Locale.ENGLISH));
        }
    }

    @Test public void nativeLspConversionKeepsLineBreaksColorsAndSingleFooterSeparator() throws Exception {
        String html = convertedHtml(SlangStructHoverPresentation.format(SOURCE, LIGHT, Locale.SIMPLIFIED_CHINESE));
        assertTrue(html.contains("<br/>"));
        assertTrue(html.contains("color:#ab2f6b"));
        assertEquals(1, html.split("<hr", -1).length - 1);
        assertFalse(html.contains("class=\"definition\""));
        // Inspect actual Swing layout after the IDE's Markdown conversion, not just the source markup.
        SwingUtilities.invokeAndWait(() -> {
            try {
                JEditorPane pane = new JEditorPane("text/html", html);
                pane.setSize(500, 500);
                String text = pane.getDocument().getText(0, pane.getDocument().getLength());
                double sizeY = pane.modelToView2D(text.indexOf("176")).getY();
                double alignmentY = pane.modelToView2D(text.indexOf("对齐")).getY();
                assertTrue("Each layout property must occupy its own row", alignmentY > sizeY);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void wireAdapterOnlyChangesCorrelatedHoverAndPreservesRange() {
        JsonObject contents = new JsonObject();
        contents.addProperty("kind", "markdown"); contents.addProperty("value", SOURCE);
        JsonObject result = new JsonObject(); result.add("contents", contents);
        var range = JsonParser.parseString("{\"start\":{\"line\":177,\"character\":18},\"end\":{\"line\":177,\"character\":40}}");
        result.add("range", range);
        JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.addProperty("id", 41); response.add("result", result);
        byte[] payload = response.toString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, SlangLspProtocolInputStream.normalizePayload(payload, new SlangLspRequestTracker()));
        var tracker = new SlangLspRequestTracker();
        tracker.recordOutgoingPayload("{\"id\":41,\"method\":\"textDocument/hover\"}".getBytes(StandardCharsets.UTF_8));
        var normalized = JsonParser.parseString(new String(SlangLspProtocolInputStream.normalizePayload(payload, tracker),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("result");
        assertEquals(range, normalized.get("range"));
        assertTrue(normalized.getAsJsonObject("contents").get("value").getAsString().startsWith("<pre>"));
    }

    static String convertedHtml(String markdown) {
        var data = LspDocumentationDataKt.createLspDocumentationData(new MarkupContent("markdown", markdown));
        assertNull(data.getDefinitionCodeBlock());
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class[]{Project.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        return DocMarkdownToHtmlConverter.convert(project, data.getDescription());
    }
}
