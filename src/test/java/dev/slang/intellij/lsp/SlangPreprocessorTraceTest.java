package dev.slang.intellij.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class SlangPreprocessorTraceTest {
    @Test public void previewIsCapabilityGatedAndHasIndependentResponseIdentity() {
        var caps = new ServerCapabilities();
        caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1, "preprocessorVariants", 1));
        assertFalse(SlangPreprocessorTrace.supportsPreview(caps));
        caps.setExperimental(JsonParser.parseString("{\"preprocessorTrace\":1,\"preprocessorContexts\":1,\"preprocessorPreview\":1}"));
        assertTrue(SlangPreprocessorTrace.supportsPreview(caps)); // M4e isn't needed for root-only preview.
        for (Object invalid : new Object[]{true, "1", 0, 2, 1.5}) {
            caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1, "preprocessorPreview", invalid));
            assertFalse(SlangPreprocessorTrace.supportsPreview(caps));
        }
        caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorPreview", 1));
        assertFalse(SlangPreprocessorTrace.supportsPreview(caps));
        var preview = dev.slang.intellij.preprocessor.SlangMacroPreview.parse("MODE=2", "FLAG").wire();
        var trace = new SlangPreprocessorTrace("target", 1, java.util.List.of(), java.util.List.of(), "root", -1, "ok", 1,
                "variant", "", preview.fingerprint());
        assertTrue(trace.matchesPreview(preview));
        assertFalse(trace.matchesPreview(null));
        var legacy = new SlangPreprocessorTrace("target", 1, java.util.List.of(), java.util.List.of());
        assertFalse(legacy.matchesPreview(preview));
        assertTrue(legacy.matchesPreview(null));
        assertFalse(trace.matchesPreview(dev.slang.intellij.preprocessor.SlangMacroPreview.parse("MODE=1", "FLAG").wire()));
        var gson = new Gson();
        var params = new SlangPreprocessorTrace.Params(new TextDocumentIdentifier("file:///test.slang"), "file:///root.slang", null, preview);
        assertEquals(params, gson.fromJson(gson.toJson(params), SlangPreprocessorTrace.Params.class));
        assertFalse(gson.toJson(params).contains("buildContext"));
        assertEquals(trace, gson.fromJson(gson.toJson(trace), SlangPreprocessorTrace.class));
    }
    @Test public void variantCapabilityAndFingerprintAreRequiredIndependently() {
        var caps = new ServerCapabilities();
        caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1));
        assertFalse(SlangPreprocessorTrace.supportsVariants(caps));
        caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1, "preprocessorVariants", 1));
        assertTrue(SlangPreprocessorTrace.supportsVariants(caps));
        for (Object invalid : new Object[]{true, "1", 0, 2, 1.5}) {
            caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1, "preprocessorVariants", invalid));
            assertFalse(SlangPreprocessorTrace.supportsVariants(caps));
        }
        var build = new SlangPreprocessorTrace.BuildContext(1, "fingerprint", false,
                java.util.List.of(new SlangPreprocessorTrace.Macro("MODE", "1")), java.util.List.of(), java.util.List.of(), "spirv", "spirv_1_5");
        var trace = new SlangPreprocessorTrace("target", 1, java.util.List.of(), java.util.List.of(), "root", -1, "ok", 1,
                "fingerprint", "");
        assertTrue(trace.matchesVariant(build));
        assertFalse(new SlangPreprocessorTrace("target", 1, java.util.List.of(), java.util.List.of(), "root", -1, "ok", 1,
                "different", "").matchesVariant(build));
        assertFalse(new SlangPreprocessorTrace("target", 1, java.util.List.of(), java.util.List.of()).matchesVariant(build));
        var gson = new Gson();
        assertEquals(build, gson.fromJson(gson.toJson(build), SlangPreprocessorTrace.BuildContext.class));
    }
    @Test public void contextsRequireTheirOwnCapabilityAndExactResponseIdentity() {
        var caps = new ServerCapabilities();
        caps.setExperimental(Map.of("preprocessorTrace", 1));
        assertFalse(SlangPreprocessorTrace.supportsContexts(caps));
        caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", 1));
        assertTrue(SlangPreprocessorTrace.supportsContexts(caps));
        for (Object invalid : new Object[]{true, "1", 0, 2, 1.5}) {
            caps.setExperimental(Map.of("preprocessorTrace", 1, "preprocessorContexts", invalid));
            assertFalse(SlangPreprocessorTrace.supportsContexts(caps));
        }
        var trace = new SlangPreprocessorTrace("target", 3, java.util.List.of(), java.util.List.of(), "root", 7, "ok", 1);
        assertTrue(trace.matchesContext("root", 7));
        assertFalse(trace.matchesContext("other", 7));
        assertFalse(trace.matchesContext("root", 8));
        assertFalse(new SlangPreprocessorTrace("target", 3, java.util.List.of(), java.util.List.of(), "root", 7, "ambiguous", 2)
                .matchesContext("root", 7));
        assertFalse(new SlangPreprocessorTrace("target", 3, java.util.List.of(), java.util.List.of()).matchesContext("root", 0));
    }
    @Test
    public void capabilityIsOptionalAndVersioned() {
        assertFalse(SlangPreprocessorTrace.isSupported(null));
        ServerCapabilities capabilities = new ServerCapabilities();
        assertFalse(SlangPreprocessorTrace.isSupported(capabilities));
        for (Object invalid : new Object[]{true, "1", 0, 2, 1.5}) {
            capabilities.setExperimental(Map.of("preprocessorTrace", invalid));
            assertFalse(SlangPreprocessorTrace.isSupported(capabilities));
        }
        capabilities.setExperimental(Map.of("preprocessorTrace", 1));
        assertTrue(SlangPreprocessorTrace.isSupported(capabilities));
        capabilities.setExperimental(JsonParser.parseString("{\"preprocessorTrace\":1}"));
        assertTrue(SlangPreprocessorTrace.isSupported(capabilities));
        capabilities.setExperimental(JsonParser.parseString("{\"preprocessorTrace\":true}"));
        assertFalse(SlangPreprocessorTrace.isSupported(capabilities));
    }

    @Test
    public void wireModelPreservesRangesAndLinks() {
        Gson gson = new Gson();
        SlangPreprocessorTrace result = gson.fromJson("""
                {"uri":"file:///Trace.slang","version":7,"directives":[
                  {"kind":"if","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},
                   "keywordRange":{"start":{"line":0,"character":1},"end":{"line":0,"character":3}},
                   "evaluated":true,"value":false,"active":false,"depth":0,
                   "parentDirective":-1,"matchingIfDirective":0,"previousBranchDirective":-1}],
                 "inactiveRegions":[{"range":{"start":{"line":1,"character":0},"end":{"line":2,"character":0}},
                                      "controllingDirective":0}]}
                """, SlangPreprocessorTrace.class);
        assertEquals(7, result.version());
        assertTrue(result.directives().getFirst().evaluated());
        assertFalse(result.directives().getFirst().active());
        assertEquals(-1, result.directives().getFirst().parentDirective());
        assertEquals(3, result.directives().getFirst().keywordRange().getEnd().getCharacter());
        assertEquals(0, result.inactiveRegions().getFirst().controllingDirective());
        assertEquals(2, result.inactiveRegions().getFirst().range().getEnd().getLine());
        assertEquals(result, gson.fromJson(gson.toJson(result), SlangPreprocessorTrace.class));
    }

    @Test
    public void requestUsesDocumentIdentifierAndNamespacedMethod() throws Exception {
        Gson gson = new Gson();
        var params = new SlangPreprocessorTrace.Params(new TextDocumentIdentifier("file:///Trace.slang"));
        assertEquals(JsonParser.parseString("{\"textDocument\":{\"uri\":\"file:///Trace.slang\"}}"),
                gson.toJsonTree(params));
        assertEquals("slang/textDocument/preprocessorTrace", SlangLanguageServer.class
                .getMethod("preprocessorTrace", SlangPreprocessorTrace.Params.class)
                .getAnnotation(JsonRequest.class).value());
    }
}
