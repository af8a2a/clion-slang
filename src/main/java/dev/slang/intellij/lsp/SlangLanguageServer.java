package dev.slang.intellij.lsp;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.concurrent.CompletableFuture;

/** Optional Slang extensions. Registering this interface does not send any extra requests. */
public interface SlangLanguageServer extends LanguageServer {
    /** Call only after {@link SlangPreprocessorTrace#isSupported} succeeds. */
    @JsonRequest("slang/textDocument/preprocessorTrace")
    CompletableFuture<SlangPreprocessorTrace> preprocessorTrace(SlangPreprocessorTrace.Params params);
}
