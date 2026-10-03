package dev.slang.intellij.lsp;

import com.google.gson.*;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.Assert.*;

public class SlangMacroHoverPresentationTest {
    private static final SlangStructHoverPresentation.Style PLAIN = new SlangStructHoverPresentation.Style(null,null,null,null,null);
    private static final String FOOTER = "\n---\n\n[AutoExposure.slang:15](<file:///E:/Shaders/AutoExposure.slang#L15>)\n";
    private static String sample(String definition, String expansion) {
        return "```slang\n#define " + definition + "\n```\n\n<!-- slang-macro-hover:1 -->\n\n"
                + "**Expansion preview**\n\n```slang\n" + expansion + "\n```\n\n" + FOOTER;
    }
    private static String format(String source, Locale locale) {
        return SlangMacroHoverPresentation.format(source, PLAIN, null, null, locale);
    }

    @Test public void riderStyleTitlePreviewAndCompactFileLink() {
        String html = format(sample("gHistory gParams . history . data", "gParams . history . data"), Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.contains("宏\n<b>gHistory</b> gParams.history.data"));
        assertTrue(html.contains("<h3 style=\"font-size:120%;\">展开预览</h3>\n<pre>gParams.history.data</pre>"));
        assertTrue(html.contains("href=\"file:///E:/Shaders/AutoExposure.slang#L15\""));
        assertTrue(html.contains("AutoExposure.slang</code>"));
        assertFalse(html.contains("slang-macro-hover"));
        assertEquals(html, SlangMacroHoverPresentation.format(html));
    }

    @Test public void parametersArePresentedWithoutInventingClientExpansion() {
        String html = format(sample("ADD(x, y) ( ( x ) + ( y ) )", "( ( 2 ) + ( 7 ) )"), Locale.ENGLISH);
        assertTrue(html.contains("<b>ADD</b>(x, y) ((x) + (y))"));
        assertTrue(html.contains("<h3 style=\"font-size:120%;\">Expansion preview</h3>\n<pre>((2) + (7))</pre>"));
    }

    @Test public void escapesMarkupAndPreservesStringLiteralContents() {
        String html = format(sample("TEXT \"<tag> & hello world\"", "\"<tag> & hello world\""), Locale.ENGLISH);
        assertTrue(html.contains("&quot;&lt;tag&gt; &amp; hello world&quot;"));
        assertFalse(html.contains("<tag>"));
    }

    @Test public void emptyUnavailableAndTruncatedHaveHonestLabels() {
        String empty = format(sample("EMPTY", ""), Locale.SIMPLIFIED_CHINESE);
        assertTrue(empty.contains("（空展开）"));
        String unavailable = "```slang\n#define F(x) x\n```\n\n<!-- slang-macro-hover:1 -->\n\nExpansion preview unavailable at this location.\n\n" + FOOTER;
        String html = format(unavailable, Locale.SIMPLIFIED_CHINESE);
        assertTrue(html.contains("请在宏调用处查看")); assertFalse(html.contains("<h3 style=\"font-size:120%;\">"));
        assertTrue(format(sample("BIG", "1").replace(FOOTER, "Expansion preview truncated after 512 tokens.\n\n" + FOOTER), Locale.SIMPLIFIED_CHINESE).contains("最多 512 个 token"));
    }

    @Test public void officialServerAndNonMacroHoversAreUnchanged() {
        for (String source : new String[]{"```\n#define F x\n```\nDefined in Test.slang(1)",
                sample("F x", "x").replace("<!-- slang-macro-hover:1 -->", ""),
                sample("F x", "x").replace("#define F", "struct F")})
            assertEquals(source, SlangMacroHoverPresentation.format(source));
    }

    @Test public void nativeRendererKeepsPreviewAsCodeAndSingleFooterSeparator() {
        String html = SlangStructHoverPresentationTest.convertedHtml(format(sample("gHistory BASE", "gParams . history . data"), Locale.ENGLISH));
        assertTrue(html.contains("Expansion preview"));
        assertTrue(html.contains("gParams.history.data"));
        assertEquals(1, html.split("<hr", -1).length - 1);
        assertTrue(html.contains("href=\"file:///E:/Shaders/AutoExposure.slang#L15\""));
    }

    @Test public void usesThemeColorsWithoutChangingGlobalScheme() {
        var style = new SlangStructHoverPresentation.Style("#cf8e6d",null,null,"#2aacb8","#bcbec4");
        String html = SlangMacroHoverPresentation.format(sample("VALUE 7", "7"), style,"#abcdef","#123456",Locale.ENGLISH);
        assertTrue(html.contains("color:#abcdef\">VALUE")); assertTrue(html.contains("color:#2aacb8\">7"));
        assertFalse(html.contains("background"));
    }

    @Test public void realServerSamplesPreserveRangesThroughCorrelatedAdapter() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/documentation/macro-hover-samples.json"), StandardCharsets.UTF_8)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                var result = entry.getValue().getAsJsonObject();
                JsonObject response = new JsonObject(); response.addProperty("id", 85); response.add("result", result.deepCopy());
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(body, SlangLspProtocolInputStream.normalizePayload(body, new SlangLspRequestTracker()));
                var tracker = new SlangLspRequestTracker();
                tracker.recordOutgoingPayload("{\"id\":85,\"method\":\"textDocument/hover\"}".getBytes(StandardCharsets.UTF_8));
                var actual = JsonParser.parseString(new String(SlangLspProtocolInputStream.normalizePayload(body, tracker), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("result");
                assertEquals(result.get("range"), actual.get("range"));
                String html = actual.getAsJsonObject("contents").get("value").getAsString();
                assertTrue(entry.getKey(), html.startsWith("<pre>"));
                assertFalse(html.contains("<!-- slang-macro-hover:"));
            }
        }
    }
}
