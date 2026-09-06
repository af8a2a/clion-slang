package dev.slang.intellij.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import java.util.List;
import java.util.Map;

/** M4a wire model; positions are zero-based UTF-16 and indices refer to {@code directives}. */
public record SlangPreprocessorTrace(
        String uri, int version, List<Directive> directives, List<InactiveRegion> inactiveRegions) {
    public record Params(TextDocumentIdentifier textDocument) {}

    public record Directive(
            String kind, Range range, Range keywordRange,
            boolean evaluated, boolean value, boolean active,
            int depth, int parentDirective, int matchingIfDirective, int previousBranchDirective) {}

    public record InactiveRegion(Range range, int controllingDirective) {}

    /** Stock servers and unknown protocol versions remain on the standard LSP path. */
    public static boolean isSupported(ServerCapabilities capabilities) {
        if (capabilities == null) return false;
        Object experimental = capabilities.getExperimental();
        Object version = null;
        if (experimental instanceof Map<?, ?> map) {
            version = map.get("preprocessorTrace");
        } else if (experimental instanceof JsonObject object) {
            version = object.get("preprocessorTrace");
        }
        if (version instanceof JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return false;
            version = element.getAsNumber();
        }
        return version instanceof Number number && number.doubleValue() == 1.0;
    }
}
