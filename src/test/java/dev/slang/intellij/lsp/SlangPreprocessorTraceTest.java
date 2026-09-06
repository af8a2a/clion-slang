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
