package dev.slang.intellij.preprocessor;

import dev.slang.intellij.settings.SlangProjectSettings;
import org.junit.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SlangIncludeGraphTest {
    private final Path base = Path.of("project").toAbsolutePath();

    @Test public void lexerSkipsCommentsStringsImportsAndMacroOperands() {
        var includes = SlangIncludeGraph.includes("""
                // #include "comment.slang"
                /* #include "block.slang" */
                string s = "#include \\\"string.slang\\\"";
                import module;
                #include MACRO
                #if 0
                # include /* header */ "real.slangh"
                #endif
                #include <sub/header.h>
                #include "unfinished
                """);
        assertEquals(List.of(new SlangIncludeGraph.Include("real.slangh", true),
                new SlangIncludeGraph.Include("sub/header.h", false)), includes);
    }

    @Test public void discoversDirectTransitiveAndSearchPathCandidatesWithoutCycles() {
        Path a = base.resolve("A.slang"), b = base.resolve("B.slang"), mid = base.resolve("mid.slangh"),
                header = base.resolve("inc/header.slangh"), unrelated = base.resolve("Other.slang");
        var sources = Map.of(a, "#include \"mid.slangh\"", b, "#include <header.slangh>",
                mid, "#include \"inc/header.slangh\"", header, "#include \"../mid.slangh\"",
                unrelated, "import A;");
        var graph = new SlangIncludeGraph(List.of(a, b, unrelated), List.of(base.resolve("inc")), sources::get, () -> {});
        var candidates = graph.candidates(header);
        assertEquals(List.of(a, b), candidates.stream().map(SlangIncludeGraph.Candidate::root).toList());
        assertEquals(List.of(a, mid, header), candidates.getFirst().chain());
        assertEquals(List.of(b, header), candidates.getLast().chain());
        assertTrue(graph.candidates(a).isEmpty());
    }

    @Test public void keepsPotentialAmbiguousPathsForCompilerConfirmation() {
        Path a = base.resolve("A.slang"), local = base.resolve("shared.slangh"), other = base.resolve("inc/shared.slangh");
        var sources = Map.of(a, "#include \"shared.slangh\"", local, "", other, "");
        var graph = new SlangIncludeGraph(List.of(a), List.of(base.resolve("inc")), sources::get, () -> {});
        assertEquals(a, graph.candidates(local).getFirst().root());
        assertEquals(a, graph.candidates(other).getFirst().root());
    }

    @Test public void contextSelectionsAreIndependentDeepCopiedAndClearable() {
        var settings = new SlangProjectSettings();
        settings.setPreprocessorContext("header", "A.slang");
        settings.setPreprocessorContext("other", "B.slang");
        var saved = settings.getState();
        saved.preprocessorContexts.put("header", "changed");
        assertEquals("A.slang", settings.getPreprocessorContext("header"));
        settings.loadState(saved);
        saved.preprocessorContexts.clear();
        assertEquals("changed", settings.getPreprocessorContext("header"));
        settings.setPreprocessorContext("header", null);
        assertNull(settings.getPreprocessorContext("header"));
        assertEquals("B.slang", settings.getPreprocessorContext("other"));
        saved.preprocessorContexts = null;
        settings.loadState(saved);
        assertNull(settings.getPreprocessorContext("other"));
    }

    @Test public void automaticModeDoesNotGuessAmongMultipleOrIncompleteCandidates() {
        Path target = base.resolve("header.slangh"), a = base.resolve("A.slang"), b = base.resolve("B.slang");
        var first = new SlangIncludeGraph.Candidate(a, List.of(a, target));
        var second = new SlangIncludeGraph.Candidate(b, List.of(b, target));
        assertEquals(a, SlangContextService.automaticRoot(target, new SlangContextService.Discovery(List.of(first), false)));
        assertEquals(target, SlangContextService.automaticRoot(target, new SlangContextService.Discovery(List.of(), false)));
        assertEquals(target, SlangContextService.automaticRoot(target, new SlangContextService.Discovery(List.of(first, second), false)));
        assertEquals(target, SlangContextService.automaticRoot(target, new SlangContextService.Discovery(List.of(first), true)));
    }

    @Test public void longChainsAreShortenedWithoutLosingRootDiscovery() {
        var sources = new java.util.HashMap<Path, String>();
        Path root = base.resolve("Root.slang"), target = base.resolve("100.slangh");
        sources.put(root, "#include \"0.slangh\"");
        for (int i = 0; i < 100; i++) sources.put(base.resolve(i + ".slangh"), "#include \"" + (i + 1) + ".slangh\"");
        sources.put(target, "");
        var graph = new SlangIncludeGraph(List.of(root), List.of(), sources::get, () -> {});
        var candidate = graph.candidates(target).getFirst();
        assertEquals(root, candidate.root());
        assertTrue(candidate.shortened());
        assertEquals(64, candidate.chain().size());
    }
}
