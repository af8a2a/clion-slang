package dev.slang.intellij.lsp;

import com.intellij.openapi.editor.impl.DocumentImpl;
import dev.slang.intellij.preprocessor.SlangBranchPresentation;
import dev.slang.intellij.preprocessor.SlangVariantCatalog;
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

            if (SlangPreprocessorTrace.supportsContexts(initialized.getCapabilities()))
                checkContexts(server, root);
            if (SlangPreprocessorTrace.supportsVariants(initialized.getCapabilities()))
                checkVariants(server, root);
            if (SlangPreprocessorTrace.supportsPreview(initialized.getCapabilities()))
                checkPreview(server, root);

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

    private static void checkPreview(SlangLanguageServer server, Path root) throws Exception {
        Path file = root.resolve("Preview.slang");
        String source = "#if MODE == 1\nfloat blue;\n#else\nfloat green; // 😀\n#endif\n";
        Files.writeString(file, source);
        var target = new TextDocumentIdentifier(file.toUri().toString());
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(target.getUri(), "slang", 30, source)));
        var build = SlangVariantCatalog.parse(root.resolve("variants.json"), root,
                "{\"version\":1,\"contexts\":[{\"id\":\"one\",\"root\":\"Preview.slang\",\"defines\":{\"MODE\":\"1\"}}]}")
                .variants().getFirst().buildContext();
        var preview = dev.slang.intellij.preprocessor.SlangMacroPreview.parse("MODE=2", "").wire();
        var normal = new SlangPreprocessorTrace.Params(target, target.getUri(), build);
        var baseline = server.preprocessorTrace(normal).get(30, TimeUnit.SECONDS);
        for (var overrides : List.of(preview, dev.slang.intellij.preprocessor.SlangMacroPreview.parse("", "MODE").wire())) {
            var result = server.preprocessorTrace(new SlangPreprocessorTrace.Params(target, target.getUri(), build, overrides)).get(30, TimeUnit.SECONDS);
            assertTrue(result.matchesPreview(overrides));
            assertTrue(result.matchesVariant(build));
            assertTrue(result.matchesContext(target.getUri(), 30));
            assertFalse(result.directives().getFirst().active());
            var presentation = SlangBranchPresentation.create(new DocumentImpl(source), result);
            assertNotNull(presentation);
            assertEquals(source.indexOf("float blue"), presentation.inactive().getFirst().getStartOffset());
            assertEquals(baseline, server.preprocessorTrace(normal).get(30, TimeUnit.SECONDS));
        }
        var rootPreview = server.preprocessorTrace(new SlangPreprocessorTrace.Params(target, null, null,
                dev.slang.intellij.preprocessor.SlangMacroPreview.parse("MODE=1", "").wire())).get(30, TimeUnit.SECONDS);
        assertTrue(rootPreview.directives().getFirst().active());
        assertFalse(server.preprocessorTrace(new SlangPreprocessorTrace.Params(target)).get(30, TimeUnit.SECONDS).directives().getFirst().active());
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(target));
    }

    private static void checkVariants(SlangLanguageServer server, Path root) throws Exception {
        Path file = root.resolve("Variant.slang");
        String source = "#if MODE == 1\nfloat blue;\n#else\nfloat green;\n#endif\n";
        Files.writeString(file, source);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(file.toUri().toString(), "slang", 20, source)));
        String manifest = """
                {"version":1,"contexts":[{"id":"compute","root":"Variant.slang","target":"spirv","profile":"spirv_1_5",
                 "variants":[{"id":"blue","defines":{"MODE":"1"}},{"id":"green","defines":{"MODE":"2"}}]}]}
                """;
        var catalog = SlangVariantCatalog.parse(root.resolve("slang-variants.json"), root, manifest);
        for (String id : List.of("compute/blue", "compute/green", "compute/blue")) {
            var variant = catalog.find(id);
            var trace = server.preprocessorTrace(new SlangPreprocessorTrace.Params(new TextDocumentIdentifier(file.toUri().toString()),
                    file.toUri().toString(), variant.buildContext())).get(30, TimeUnit.SECONDS);
            assertTrue(trace.matchesContext(file.toUri().toString(), 20));
            assertTrue(trace.matchesVariant(variant.buildContext()));
            assertEquals(id.endsWith("blue"), trace.directives().getFirst().active());
            var presentation = SlangBranchPresentation.create(new DocumentImpl(source), trace);
            assertNotNull(presentation);
            assertEquals(source.indexOf(id.endsWith("blue") ? "float green" : "float blue"), presentation.inactive().getFirst().getStartOffset());
        }
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(file.toUri().toString())));
    }

    private static void checkContexts(SlangLanguageServer server, Path root) throws Exception {
        Path header = root.resolve("Shared.slangh"), a = root.resolve("A.slang"), b = root.resolve("B.slang");
        String text = "#if FLAG\n// on 😀\n#else\n// off\n#endif\n";
        Files.writeString(header, text);
        Files.writeString(a, "#define FLAG 1\n#include \"Shared.slangh\"\nfloat a;\n");
        Files.writeString(b, "#define FLAG 0\n#include \"Bridge.slangh\"\nfloat b;\n");
        Files.writeString(root.resolve("Bridge.slangh"), "#include \"Shared.slangh\"\n");
        var target = new TextDocumentIdentifier(header.toUri().toString());
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(target.getUri(), "slang", 10, text)));
        var fromA = new SlangPreprocessorTrace.Params(target, a.toUri().toString());
        var fromB = new SlangPreprocessorTrace.Params(target, b.toUri().toString());
        var on = server.preprocessorTrace(fromA).get(30, TimeUnit.SECONDS);
        assertTrue(on.matchesContext(a.toUri().toString(), -1));
        assertEquals(10, on.version());
        assertTrue(on.directives().getFirst().active());
        assertNotNull(SlangBranchPresentation.create(new DocumentImpl(text), on));
        var off = server.preprocessorTrace(fromB).get(30, TimeUnit.SECONDS);
        assertTrue(off.matchesContext(b.toUri().toString(), -1));
        assertFalse(off.directives().getFirst().active());
        assertNotNull(SlangBranchPresentation.create(new DocumentImpl(text), off));
        assertTrue(server.preprocessorTrace(fromA).get(30, TimeUnit.SECONDS).directives().getFirst().active());
        // A saved root is not implicitly opened or installed into ordinary LSP state.
        assertNull(server.preprocessorTrace(new SlangPreprocessorTrace.Params(new TextDocumentIdentifier(a.toUri().toString())))
                .get(30, TimeUnit.SECONDS));
        String unsavedB = "#define FLAG 1\n#include \"Bridge.slangh\"\nfloat b;\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(b.toUri().toString(), "slang", 4, unsavedB)));
        var unsaved = server.preprocessorTrace(fromB).get(30, TimeUnit.SECONDS);
        assertTrue(unsaved.matchesContext(b.toUri().toString(), 4));
        assertTrue(unsaved.directives().getFirst().active());
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(target.getUri(), 11), List.of(new TextDocumentContentChangeEvent(
                new Range(new Position(0, 4), new Position(0, 8)), "!FLAG"))));
        var editedHeader = server.preprocessorTrace(fromA).get(30, TimeUnit.SECONDS);
        assertEquals(11, editedHeader.version());
        assertFalse(editedHeader.directives().getFirst().active());
        Path skipped = root.resolve("Skipped.slang"), repeated = root.resolve("Repeated.slang");
        Files.writeString(skipped, "#if 0\n#include \"Shared.slangh\"\n#endif\nfloat x;\n");
        Files.writeString(repeated, "#define FLAG 1\n#include \"Shared.slangh\"\n#include \"Shared.slangh\"\nfloat x;\n");
        var absent = server.preprocessorTrace(new SlangPreprocessorTrace.Params(target, skipped.toUri().toString())).get(30, TimeUnit.SECONDS);
        assertEquals("notIncluded", absent.status());
        assertEquals(0, absent.occurrenceCount());
        assertTrue(absent.directives().isEmpty());
        var duplicate = server.preprocessorTrace(new SlangPreprocessorTrace.Params(target, repeated.toUri().toString())).get(30, TimeUnit.SECONDS);
        assertEquals("ambiguous", duplicate.status());
        assertEquals(2, duplicate.occurrenceCount());
        assertTrue(duplicate.directives().isEmpty());
        assertNull(server.preprocessorTrace(new SlangPreprocessorTrace.Params(target, root.resolve("Missing.slang").toUri().toString()))
                .get(30, TimeUnit.SECONDS));
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(b.toUri().toString())));
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(target));
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
