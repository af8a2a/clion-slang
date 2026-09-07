package dev.slang.intellij.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import java.util.List;
import java.util.Map;

/** M4a trace plus optional M4c context metadata; positions are zero-based UTF-16. */
public record SlangPreprocessorTrace(
        String uri, int version, List<Directive> directives, List<InactiveRegion> inactiveRegions,
        String contextUri, int contextVersion, String status, int occurrenceCount,
        String contextFingerprint, String contextError, String previewFingerprint) {
    public SlangPreprocessorTrace(String uri, int version, List<Directive> directives, List<InactiveRegion> inactiveRegions,
                                  String contextUri, int contextVersion, String status, int occurrenceCount,
                                  String contextFingerprint, String contextError) {
        this(uri, version, directives, inactiveRegions, contextUri, contextVersion, status, occurrenceCount,
                contextFingerprint, contextError, null);
    }
    public SlangPreprocessorTrace(String uri, int version, List<Directive> directives, List<InactiveRegion> inactiveRegions,
                                  String contextUri, int contextVersion, String status, int occurrenceCount) {
        this(uri, version, directives, inactiveRegions, contextUri, contextVersion, status, occurrenceCount, null, null);
    }
    public SlangPreprocessorTrace(String uri, int version, List<Directive> directives, List<InactiveRegion> inactiveRegions) {
        this(uri, version, directives, inactiveRegions, null, -1, null, 0);
    }
    public record Params(TextDocumentIdentifier textDocument, String contextUri, BuildContext buildContext, Preview preview) {
        public Params(TextDocumentIdentifier textDocument, String contextUri, BuildContext buildContext) {
            this(textDocument, contextUri, buildContext, null);
        }
        public Params(TextDocumentIdentifier textDocument) { this(textDocument, null, null); }
        public Params(TextDocumentIdentifier textDocument, String contextUri) { this(textDocument, contextUri, null); }
    }

    public record Macro(String name, String value) {}
    public record Preview(int version, String fingerprint, List<Macro> defines, List<String> undefines) {}
    public record BuildContext(int version, String fingerprint, boolean inheritWorkspace,
                               List<Macro> defines, List<String> undefines, List<String> includePaths,
                               String target, String profile) {}

    public boolean matchesVariant(BuildContext requested) {
        return requested == null || requested.fingerprint().equals(contextFingerprint);
    }

    public boolean matchesPreview(Preview requested) {
        return requested == null ? previewFingerprint == null || previewFingerprint.isEmpty()
                : requested.fingerprint().equals(previewFingerprint);
    }

    public boolean matchesContext(String requestedUri, int requestedVersion) {
        return requestedUri.equals(contextUri) && requestedVersion == contextVersion
                && "ok".equals(status) && occurrenceCount == 1;
    }

    public record Directive(
            String kind, Range range, Range keywordRange,
            boolean evaluated, boolean value, boolean active,
            int depth, int parentDirective, int matchingIfDirective, int previousBranchDirective) {}

    public record InactiveRegion(Range range, int controllingDirective) {}

    /** Stock servers and unknown protocol versions remain on the standard LSP path. */
    public static boolean isSupported(ServerCapabilities capabilities) {
        return supports(capabilities, "preprocessorTrace");
    }

    public static boolean supportsContexts(ServerCapabilities capabilities) {
        return isSupported(capabilities) && supports(capabilities, "preprocessorContexts");
    }

    public static boolean supportsVariants(ServerCapabilities capabilities) {
        return supportsContexts(capabilities) && supports(capabilities, "preprocessorVariants");
    }

    public static boolean supportsPreview(ServerCapabilities capabilities) {
        return supportsContexts(capabilities) && supports(capabilities, "preprocessorPreview");
    }

    private static boolean supports(ServerCapabilities capabilities, String capability) {
        if (capabilities == null) return false;
        Object experimental = capabilities.getExperimental();
        Object version = null;
        if (experimental instanceof Map<?, ?> map) {
            version = map.get(capability);
        } else if (experimental instanceof JsonObject object) {
            version = object.get(capability);
        }
        if (version instanceof JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return false;
            version = element.getAsNumber();
        }
        return version instanceof Number number && number.doubleValue() == 1.0;
    }
}
