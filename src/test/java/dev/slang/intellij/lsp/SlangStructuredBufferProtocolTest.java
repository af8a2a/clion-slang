package dev.slang.intellij.lsp;

import dev.slang.intellij.highlighting.SlangSemanticColors;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Same advertised vocabulary, LSP4J transport and color mapping as the native client adapter. */
public class SlangStructuredBufferProtocolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void negotiatedResourceRolesReachTheDedicatedPluginColors() throws Exception {
        String executable = System.getProperty("slang.test.slangd", "");
        Assume.assumeFalse("Supply slang.test.slangd to enable real-server tests", executable.isBlank());
        String source = Files.readString(Path.of("src/test/testData/slang/StructuredBufferHighlighting.slang"));
        Path file = temporary.getRoot().toPath().resolve("Buffers.slang");
        Files.writeString(file, source);
        Process process = new SlangLspProtocolProcess(new ProcessBuilder(executable).start());
        var drain = new Thread(() -> {
            try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) { }
        }, "slang-buffer-stderr");
        drain.setDaemon(true);
        drain.start();
        var launcher = new Launcher.Builder<SlangLanguageServer>().setRemoteInterface(SlangLanguageServer.class)
                .setLocalService(new Client()).setInput(process.getInputStream()).setOutput(process.getOutputStream()).create();
        var listening = launcher.startListening();
        try {
            var support = SlangSemanticTokensSupport.INSTANCE;
            var semantics = new SemanticTokensCapabilities();
            semantics.setTokenTypes(support.getTokenTypes());
            semantics.setTokenModifiers(support.getTokenModifiers());
            semantics.setFormats(List.of(TokenFormat.Relative));
            var requests = new SemanticTokensClientCapabilitiesRequests();
            requests.setFull(true);
            semantics.setRequests(requests);
            var text = new TextDocumentClientCapabilities();
            text.setSemanticTokens(semantics);
            var capabilities = new ClientCapabilities();
            capabilities.setTextDocument(text);
            var initialize = new InitializeParams();
            initialize.setCapabilities(capabilities);
            initialize.setWorkspaceFolders(List.of());
            var server = launcher.getRemoteProxy();
            var result = server.initialize(initialize).get(30, TimeUnit.SECONDS);
            var legend = result.getCapabilities().getSemanticTokensProvider().getLegend().getTokenTypes();
            Assume.assumeTrue("Requires optional structured-buffer semantic patch 0005", legend.contains("slangStructuredBuffer"));
            assertTrue(legend.contains("slangTypeArgument"));
            server.initialized(new InitializedParams());
            var target = new TextDocumentIdentifier(file.toUri().toString());
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(target.getUri(), "slang", 1, source)));
            var tokens = server.getTextDocumentService().semanticTokensFull(new SemanticTokensParams(target)).get(30, TimeUnit.SECONDS);
            var decoded = decode(source, tokens.getData(), legend);
            assertToken(decoded, "StructuredBuffer", "slangStructuredBuffer");
            assertToken(decoded, "RWStructuredBuffer", "slangStructuredBuffer");
            assertToken(decoded, "uint", "slangTypeArgument");
            assertToken(decoded, "HitEntry", "slangTypeArgument");
            assertToken(decoded, "TElement", "typeParameter");
            for (var token : decoded) {
                var color = support.getTextAttributesKey(token.role, List.of());
                if (token.role.equals("slangStructuredBuffer")) assertSame(SlangSemanticColors.STRUCTURED_BUFFER, color);
                if (token.role.equals("slangTypeArgument")) assertSame(SlangSemanticColors.TYPE_ARGUMENT, color);
            }
            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(target));
            server.shutdown().get(10, TimeUnit.SECONDS);
            server.exit();
        } finally {
            listening.cancel(true);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private record Token(String text, String role) {}
    private static List<Token> decode(String source, List<Integer> data, List<String> legend) {
        assertEquals(0, data.size() % 5);
        var lines = source.split("\n", -1);
        var result = new ArrayList<Token>();
        int line = 0, column = 0, endLine = -1, endColumn = -1;
        for (int i = 0; i < data.size(); i += 5) {
            int delta = data.get(i), start = data.get(i + 1), length = data.get(i + 2);
            line += delta;
            column = delta == 0 ? column + start : start;
            assertTrue(line >= 0 && line < lines.length && column >= 0 && length > 0 && column + length <= lines[line].length());
            assertTrue(line > endLine || line == endLine && column >= endColumn);
            assertEquals(0, (int) data.get(i + 4));
            result.add(new Token(lines[line].substring(column, column + length), legend.get(data.get(i + 3))));
            endLine = line; endColumn = column + length;
        }
        return result;
    }
    private static void assertToken(List<Token> tokens, String text, String role) {
        assertTrue("Missing " + role + " for " + text, tokens.contains(new Token(text, role)));
    }

    private static final class Client implements LanguageClient {
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams params) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public void logMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            return CompletableFuture.completedFuture(Collections.nCopies(params.getItems().size(), null));
        }
        @Override public CompletableFuture<Void> registerCapability(RegistrationParams params) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> refreshSemanticTokens() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> refreshInlayHints() { return CompletableFuture.completedFuture(null); }
    }
}
