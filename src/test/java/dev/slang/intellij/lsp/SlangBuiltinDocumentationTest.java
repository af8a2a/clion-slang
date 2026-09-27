package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter;
import com.intellij.platform.lsp.impl.features.documentation.LspDocumentationDataKt;
import org.eclipse.lsp4j.MarkupContent;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.*;

public class SlangBuiltinDocumentationTest {
    private static String builtin(String name, String declaration) {
        return "```\n" + declaration + "\n```\n\nDocumentation 中文.\n\n"
                + "<!-- slang-builtin:core:" + name + " -->\n\nDefined in core(123)\n";
    }

    @Test public void linksHlslFunctionsAndPreservesDocumentation() {
        String source = builtin("abs", "func abs<float>(float x) -> float");
        String result = SlangBuiltinDocumentation.format(source);
        assertTrue(result.contains("<code>abs</code> on Microsoft Learn ↗</a>"));
        assertTrue(result.contains("href=\"https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/dx-graphics-hlsl-abs\""));
        assertTrue(result.contains("Documentation 中文."));
        assertTrue(result.startsWith("```\nfunc abs<float>"));
        assertFalse(result.contains("Defined in"));
        assertFalse(result.contains("slang-builtin"));
        assertEquals(result, SlangBuiltinDocumentation.format(result));
        assertEquals(result, SlangBuiltinDocumentation.format(source.replace("\n", "\r\n")).replace("\r\n", "\n"));
    }

    @Test public void linksConstantsSlangFunctionsAndOverloadGroups() {
        for (String[] sample : new String[][]{
                {"RAY_FLAG_NONE", "(global variable) static const uint RAY_FLAG_NONE = 0", "ray_flag_none-01245679abc.html"},
                {"detach", "func detach<float>(float x) -> float", "detach.html"},
                {"asuint", "func asuint(float x) -> uint", "asuint.html"}}) {
            String result = SlangBuiltinDocumentation.format(builtin(sample[0], sample[1]));
            assertTrue(result, result.contains("on Slang Documentation ↗</a>"));
            assertTrue(result, result.contains("/global-decls/" + sample[2]));
        }
    }

    @Test public void unknownSymbolsAndUnprovenUserDeclarationsStayUnchanged() {
        String source = builtin("abs", "func abs<float>(float x) -> float");
        for (String sample : new String[]{
                source.replace("<!-- slang-builtin:core:abs -->\n", ""),
                source.replace("func abs<float>", "func User.abs<float>"),
                source.replace("slang-builtin:core:abs", "slang-builtin:core:sin"),
                source.replace("Defined in core", "Defined in User.slang"),
                source.replace("```\n", "```cpp\n"),
                builtin("notDocumented", "func notDocumented() -> void"),
                builtin("RAY_FLAG_NONE", "(local variable) uint RAY_FLAG_NONE = 7")}) {
            assertEquals(sample, SlangBuiltinDocumentation.format(sample));
        }
    }

    @Test public void legacyAbsoluteSyntheticCoreWorksButRealFileNamedCoreDoesNot() throws Exception {
        Path dir = Files.createTempDirectory("slang-builtin-docs");
        Path core = dir.resolve("core");
        String source = builtin("abs", "func abs(float x) -> float")
                .replace("<!-- slang-builtin:core:abs -->\n", "").replace("Defined in core", "Defined in " + core);
        try {
            assertTrue(SlangBuiltinDocumentation.format(source).contains("on Microsoft Learn"));
            Files.writeString(core, "float abs(float x) { return x; }");
            assertEquals(source, SlangBuiltinDocumentation.format(source));
        } finally {
            Files.deleteIfExists(core);
            Files.delete(dir);
        }
    }

    @Test public void preservesExistingReferenceWithoutDuplication() {
        String source = builtin("abs", "func abs(float x) -> float")
                + "[Existing reference](https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/dx-graphics-hlsl-abs)\n";
        assertEquals(source, SlangBuiltinDocumentation.format(source));
    }

    @Test public void nativeLspRendererKeepsSignatureSeparateAndExternalLinkClickable() {
        String result = SlangBuiltinDocumentation.format(builtin("abs", "func abs<float>(float x) -> float"))
                .replaceFirst("```", "```slang");
        var data = LspDocumentationDataKt.createLspDocumentationData(new MarkupContent("markdown", result));
        assertNotNull(data.getDefinitionCodeBlock());
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class[]{Project.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        String html = DocMarkdownToHtmlConverter.convert(project, data.getDescription());
        assertTrue(html, html.contains("href=\"https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/dx-graphics-hlsl-abs\""));
        assertTrue(html, html.contains("Microsoft Learn ↗"));
        assertTrue(html, html.contains("<code>abs</code>"));
        assertFalse(html, html.contains("slang-builtin"));
    }

    @Test public void realServerSamplesRetainRangesAndAddOnlyBuiltinReferences() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/documentation/builtin-hover-samples.json"), StandardCharsets.UTF_8)) {
            for (var sample : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                JsonObject response = new JsonObject(); response.addProperty("id", 17); response.add("result", sample.getValue().deepCopy());
                var result = response.getAsJsonObject("result");
                var range = result.get("range").deepCopy();
                String before = result.getAsJsonObject("contents").get("value").getAsString();
                assertTrue(SlangHoverHighlighting.normalize(response));
                String after = result.getAsJsonObject("contents").get("value").getAsString();
                assertEquals(range, result.get("range"));
                assertTrue(after.startsWith("```slang\n"));
                assertEquals(sample.getKey(), before.contains("<!-- slang-builtin:"), after.contains(" ↗</a>"));
                assertFalse(SlangHoverHighlighting.normalize(response));
            }
        }
    }

    @Test public void wireAdapterKeepsUtf8LengthRangeAndRequestCorrelation() throws Exception {
        JsonObject contents = new JsonObject(); contents.addProperty("kind", "markdown");
        contents.addProperty("value", builtin("abs", "func abs(float x) -> float"));
        JsonObject result = new JsonObject(); result.add("contents", contents);
        result.add("range", JsonParser.parseString("{\"start\":{\"line\":0,\"character\":1},\"end\":{\"line\":0,\"character\":4}}"));
        JsonObject response = new JsonObject(); response.addProperty("id", 17); response.add("result", result);
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(body, SlangLspProtocolInputStream.normalizePayload(body, new SlangLspRequestTracker()));
        var tracker = new SlangLspRequestTracker();
        tracker.recordOutgoingPayload("{\"id\":17,\"method\":\"textDocument/hover\"}".getBytes(StandardCharsets.UTF_8));
        byte[] frame = ("Content-Length: " + body.length + "\r\n\r\n" + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String output = new String(new SlangLspProtocolInputStream(new ByteArrayInputStream(frame), tracker).readAllBytes(), StandardCharsets.UTF_8);
        int separator = output.indexOf("\r\n\r\n");
        String json = output.substring(separator + 4);
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, Integer.parseInt(output.substring("Content-Length: ".length(), separator)));
        var normalized = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("result");
        assertEquals(result.get("range"), normalized.get("range"));
        assertTrue(normalized.getAsJsonObject("contents").get("value").getAsString().contains("Microsoft Learn ↗"));
    }
}
