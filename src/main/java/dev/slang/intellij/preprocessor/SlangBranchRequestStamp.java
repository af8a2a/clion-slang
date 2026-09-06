package dev.slang.intellij.preprocessor;

import dev.slang.intellij.lsp.SlangPreprocessorTrace;

/** A URI/version alone cannot detect include edits or a server restart. */
record SlangBranchRequestStamp(Object server, String uri, int version, long documentStamp, long generation) {
    boolean accepts(SlangPreprocessorTrace trace, Object currentServer, int currentVersion,
                    long currentDocumentStamp, long currentGeneration) {
        return trace != null && server == currentServer && generation == currentGeneration
                && documentStamp == currentDocumentStamp && version == currentVersion
                && uri.equals(trace.uri()) && version == trace.version();
    }
}
