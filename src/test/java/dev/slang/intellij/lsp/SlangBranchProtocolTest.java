package dev.slang.intellij.lsp;

import com.intellij.openapi.editor.impl.DocumentImpl;
import dev.slang.intellij.preprocessor.SlangBranchPresentation;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Opt-in real LSP4J/stdio integration: -Dslang.test.slangd=<M4a executable>. */
public class SlangBranchProtocolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void compilerTraceSurvivesThePluginTransportAndBecomesBranchDecorations() throws Exception {
        String executable = System.getProperty("slang.test.slangd", "");
        Assume.assumeFalse("Supply -Dslang.test.slangd to run against a real server", executable.isBlank());
        Path root = temporary.getRoot().toPath();
        Path sourceFile = root.resolve("Branch.slang");
        String source = "#define BLUE 0\n#if BLUE\nfloat blue;\n#else\nfloat green; // 😀\n#endif\n";
        Files.writeString(sourceFile, source);
        Process process = new SlangLspProtocolProcess(new ProcessBuilder(executable).start());
        Thread drain = new Thread(() -> {
            try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) { }
        }, "slangd-test-stderr");
        drain.setDaemon(true);
        drain.start();
        var launcher = new Launcher.Builder<SlangLanguageServer>()
                .setRemoteInterface(SlangLanguageServer.class).setLocalService(new Client())
                .setInput(process.getInputStream()).setOutput(process.getOutputStream()).create();
        var listening = launcher.startListening();
        try {
            SlangLanguageServer server = launcher.getRemoteProxy();
            InitializeParams initialize = new InitializeParams();
            initialize.setCapabilities(new ClientCapabilities());
            initialize.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "branches")));
            var initialized = server.initialize(initialize).get(30, TimeUnit.SECONDS);
            assertTrue(SlangPreprocessorTrace.isSupported(initialized.getCapabilities()));
            server.initialized(new InitializedParams());
            String uri = sourceFile.toUri().toString();
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "slang", 1, source)));
            var params = new SlangPreprocessorTrace.Params(new TextDocumentIdentifier(uri));
            var trace = server.preprocessorTrace(params).get(30, TimeUnit.SECONDS);
            assertEquals(1, trace.version());
            var model = SlangBranchPresentation.create(new DocumentImpl(source), trace);
            assertNotNull(model);
            assertEquals(List.of("#if BLUE", "#if BLUE #else"),
                    model.labels().stream().map(SlangBranchPresentation.Label::text).toList());
            assertEquals(source.indexOf("float blue"), model.inactive().getFirst().getStartOffset());

            var edit = new TextDocumentContentChangeEvent(new Range(new Position(0, 13), new Position(0, 14)), "1");
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 2), List.of(edit)));
            var updated = server.preprocessorTrace(params).get(30, TimeUnit.SECONDS);
            assertEquals(2, updated.version());
            assertTrue(updated.directives().getFirst().active());
            var changed = SlangBranchPresentation.create(new DocumentImpl(source.replace("BLUE 0", "BLUE 1")), updated);
            assertNotNull(changed);
            assertEquals(source.indexOf("float green"), changed.inactive().getFirst().getStartOffset());

            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
            assertNull(server.preprocessorTrace(params).get(30, TimeUnit.SECONDS));
            server.shutdown().get(10, TimeUnit.SECONDS);
            server.exit();
        } finally {
            listening.cancel(true);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static final class Client implements LanguageClient {
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams params) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public void logMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            return CompletableFuture.completedFuture(Collections.nCopies(params.getItems().size(), null));
        }
        @Override public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> refreshSemanticTokens() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> refreshInlayHints() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
