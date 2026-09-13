package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.Assert.*;

public class SlangFieldHoverPresentationTest {
    private static final SlangStructHoverPresentation.Style LIGHT = SlangStructHoverPresentationTest.LIGHT;
    private static final String SOURCE = "```slang\npublic field\nuint instanceID\n```\n\n(in struct `UnifiedRT::Hit`)\n\n"
            + "**Natural field layout (bytes)**  \nSize: `4`  \nAlignment: `4`  \nOffset: `0`\n\n"
            + "\nIdentifier documentation.\n\n---\n\n[Hit.slang:18](<file:///E:/Shaders/Hit.slang#L18>)\n";

    private String format(String source, Locale locale) {
        return SlangFieldHoverPresentation.format(source, LIGHT, "#6b2fba", "#123456", locale);
    }

    @Test public void chineseFieldShowsDeclarationOwnerAndEveryLayoutRow() {
        String html = format(SOURCE, Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.startsWith("<pre><span style=\"color:#0f54d6\">public</span> 字段\n"));
        assertTrue(html.contains("<span style=\"color:#6b2fba\">uint</span> <b><span style=\"color:#123456\">instanceID</span></b>"));
        assertTrue(html.contains("\n  (<span style=\"color:#0f54d6\">struct</span> <span style=\"color:#300073\">UnifiedRT::Hit</span> 中)"));
        for (String label : new String[]{"大小：", "对齐：", "偏移："}) assertTrue(html.contains(label));
        assertTrue(html.contains("偏移：<span style=\"color:#ab2f6b\">0</span>"));
        assertTrue(html.contains("Identifier documentation."));
        assertTrue(html.contains("href=\"file:///E:/Shaders/Hit.slang#L18\""));
        assertTrue(html.contains(">Hit.slang</span></code></a>"));
        assertFalse(html.contains("`4`"));
        assertEquals(html, format(html, Locale.SIMPLIFIED_CHINESE));
    }

    @Test public void englishStaticConstKeepsInitializerAndDoesNotInventInstanceOffset() {
        String source = SOURCE.replace("public field\nuint instanceID", "private static field\nconst uint count = 3")
                .replace("Offset: `0`", "Offset: not applicable (static field)");
        String html = format(source, Locale.ENGLISH);
        assertTrue(html.contains(">private</span> <span style=\"color:#0f54d6\">static</span> field"));
        assertTrue(html.contains(">const</span>"));
        assertTrue(html.contains(">count</span></b> = <span style=\"color:#ab2f6b\">3</span>"));
        assertTrue(html.contains("Offset: not applicable (static field)"));
        assertFalse(html.contains("字段"));
    }

    @Test public void unknownOffsetDoesNotSuppressKnownSizeOrInventZero() {
        String html = format(SOURCE.replace("Offset: `0`", "Offset: unavailable"), Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.contains("大小：<span style=\"color:#ab2f6b\">4</span>"));
        assertTrue(html.contains("偏移：不可用"));
        String allUnknown = SOURCE.replace("Size: `4`", "Size: unavailable")
                .replace("Alignment: `4`", "Alignment: unavailable").replace("Offset: `0`", "Offset: unavailable");
        html = format(allUnknown, Locale.ENGLISH);
        assertTrue(html.contains("Size: unavailable<br/>Alignment: unavailable<br/>Offset: unavailable"));
    }

    @Test public void arraysGenericOwnersAndDocumentationAreEscapedAndPreserved() {
        String docs = "\nExample:\n```slang\nfield.value = 4;\n```\n";
        String source = SOURCE.replace("uint instanceID", "vector<float, 3>[2] vectors")
                .replace("UnifiedRT::Hit", "Box<vector<float, 3>>").replace("Identifier documentation.", docs).replace("\n", "\r\n");
        String html = format(source, Locale.ENGLISH);
        assertTrue(html.contains("vector</span>&lt;"));
        assertTrue(html.contains("color:#ab2f6b\">3</span>"));
        assertTrue(html.contains("[<span style=\"color:#ab2f6b\">2</span>]"));
        assertTrue(html.contains("Box&lt;vector&lt;float, 3&gt;&gt;"));
        assertTrue(html.contains(docs.replace("\n", "\r\n")));
    }

    @Test public void colorsComeFromSuppliedThemeIncludingDistinctFieldRole() {
        var dark = new SlangStructHoverPresentation.Style("#cf8e6d", "#bcbec4", "#bcbec4", "#2aacb8", "#bcbec4");
        String html = SlangFieldHoverPresentation.format(SOURCE, dark, "#f0f0f0", "#ff9966", Locale.ENGLISH);
        assertTrue(html.contains("color:#ff9966\">instanceID"));
        assertTrue(html.contains("color:#2aacb8\">0"));
        assertFalse(html.contains("#123456"));
        assertFalse(html.contains("background"));
    }

    @Test public void stockMalformedAndOtherHoverKindsStayUnchanged() {
        for (String source : new String[]{"```slang\n(field) uint Hit.instanceID\n```\n\nDefined in Hit.slang(18)",
                SOURCE.replace("public field", "public property"), SOURCE.replace("Offset: `0`", "Offset: unknown"),
                SOURCE.replace("```slang", "```cpp"), "prose\n" + SOURCE}) {
            assertEquals(source, format(source, Locale.ENGLISH));
        }
        assertEquals(SOURCE, SlangStructHoverPresentation.format(SOURCE, LIGHT, Locale.ENGLISH));
    }

    @Test public void ideConversionAndSwingKeepSizeAlignmentOffsetOnSeparateRows() throws Exception {
        String html = SlangStructHoverPresentationTest.convertedHtml(format(SOURCE, Locale.SIMPLIFIED_CHINESE));
        assertEquals(1, html.split("<hr", -1).length - 1);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var pane = new JEditorPane("text/html", html); pane.setSize(500, 500);
                String text = pane.getDocument().getText(0, pane.getDocument().getLength());
                double size = pane.modelToView2D(text.indexOf("大小")).getY();
                double alignment = pane.modelToView2D(text.indexOf("对齐")).getY();
                double offset = pane.modelToView2D(text.indexOf("偏移")).getY();
                assertTrue(size < alignment && alignment < offset);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void protocolFramingPreservesUtf8LengthRangeAndRequestCorrelation() throws Exception {
        var contents = new JsonObject(); contents.addProperty("kind", "markdown"); contents.addProperty("value", SOURCE + "\n说明");
        var result = new JsonObject(); result.add("contents", contents);
        var range = JsonParser.parseString("{\"start\":{\"line\":18,\"character\":9},\"end\":{\"line\":18,\"character\":19}}");
        result.add("range", range);
        var response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.addProperty("id", 9); response.add("result", result);
        byte[] payload = response.toString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, SlangLspProtocolInputStream.normalizePayload(payload, new SlangLspRequestTracker()));
        var tracker = new SlangLspRequestTracker();
        tracker.recordOutgoingPayload("{\"id\":9,\"method\":\"textDocument/hover\"}".getBytes(StandardCharsets.UTF_8));
        String frame = "Content-Length: " + payload.length + "\r\n\r\n" + new String(payload, StandardCharsets.UTF_8);
        String output = new String(new SlangLspProtocolInputStream(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)), tracker)
                .readAllBytes(), StandardCharsets.UTF_8);
        int separator = output.indexOf("\r\n\r\n"); String body = output.substring(separator + 4);
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, Integer.parseInt(output.substring("Content-Length: ".length(), separator)));
        var updated = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertEquals(range, updated.get("range"));
        assertTrue(updated.getAsJsonObject("contents").get("value").getAsString().startsWith("<pre>"));
    }
}
