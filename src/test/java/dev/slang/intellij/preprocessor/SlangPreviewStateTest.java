package dev.slang.intellij.preprocessor;

import com.google.gson.Gson;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.junit.Test;

import static org.junit.Assert.*;

public class SlangPreviewStateTest {
    private final SlangPreviewState state = new SlangPreviewState();
    private final Object server = new Object();
    private final SlangPreviewState.Baseline root = new SlangPreviewState.Baseline("auto", "/Root.slang", null);
    private final SlangMacroPreview macros = SlangMacroPreview.parse("MODE=2", "REMOVED");

    @Test public void stopAndRestartRejectEvenIdenticalLateResponses() {
        var first = state.start("file", root, server, macros);
        assertSame(first, state.current("file", root, server));
        state.stop("file");
        assertFalse(state.isCurrent("file", first));
        assertTrue(state.isCurrent("file", null));
        var second = state.start("file", root, server, macros);
        assertEquals(first, second);
        assertFalse(state.isCurrent("file", first));
        assertTrue(state.isCurrent("file", second));
        assertNull(state.current("file", first, new SlangPreviewState.Baseline("root:old", "/Old.slang", null), server));
        assertSame(second, state.peek("file")); // Obsolete task cannot clear the new session.
    }

    @Test public void changedRootSelectionVariantFingerprintOrServerClearsPreview() {
        for (var changed : new SlangPreviewState.Baseline[]{
                new SlangPreviewState.Baseline("root:chosen", "/Root.slang", null),
                new SlangPreviewState.Baseline("auto", "/Other.slang", null),
                new SlangPreviewState.Baseline("auto", "/Root.slang", "changed")}) {
            state.start("file", root, server, macros);
            assertNull(state.current("file", changed, server));
            assertNull(state.peek("file"));
        }
        state.start("file", root, server, macros);
        assertNull(state.current("file", root, new Object()));
        var variant = new SlangPreviewState.Baseline("variant:compute/blue", "/Root.slang", "v1");
        state.start("file", variant, server, macros);
        assertNull(state.current("file", new SlangPreviewState.Baseline(variant.selection(), variant.root(), "v2"), server));
    }

    @Test public void obsoleteDiscoveryAfterAnEditCannotEraseStillActiveInput() {
        var session = state.start("file", root, server, macros);
        long oldRevision = state.requestRevision();
        state.invalidateRequests();
        assertNull(state.current("file", session, new SlangPreviewState.Baseline("auto", "/Old.slang", null), server, oldRevision));
        assertSame(session, state.peek("file"));
        assertSame(session, state.current("file", session, root, server, state.requestRevision()));
    }

    @Test public void closingOneSplitKeepsSessionButLastTargetOrRootCloseClearsIt() {
        var session = state.start("file", root, server, macros);
        state.closeFile("file", "/Header.slangh", true);
        assertSame(session, state.peek("file"));
        state.closeFile("other", "/Other.slang", false);
        assertSame(session, state.peek("file"));
        state.closeFile("file", "/Header.slangh", false);
        assertNull(state.peek("file"));
        state.start("file", root, server, macros);
        state.closeFile("root-file", root.root(), false);
        assertNull(state.peek("file"));
        if (System.getProperty("os.name").startsWith("Windows")) {
            state.start("file", root, server, macros);
            state.closeFile("root-file", "/root.slang", false);
            assertNull(state.peek("file"));
        }
    }

    @Test public void refreshRetainsInputAndGlobalCleanupLeavesNoSessions() {
        var session = state.start("file", root, server, macros);
        // Edits / colors / tab selection do not alter baseline identity.
        assertSame(session, state.current("file", root, server));
        state.start("other", root, server, macros);
        state.clear();
        assertNull(state.peek("file"));
        assertNull(state.peek("other"));
    }

    @Test public void settingsSerializationNeverStoresPreviewOrRewritesSelections() {
        var settings = new SlangProjectSettings();
        settings.setPreprocessorContext("file", "/Root.slang");
        settings.setShaderVariant("file", "compute/blue");
        settings.setShaderVariantsPath("build/variants.json");
        var gson = new Gson();
        String saved = gson.toJson(settings.getState());
        state.start("file", root, server, macros);
        assertEquals(saved, gson.toJson(settings.getState()));
        state.stop("file");
        assertEquals(saved, gson.toJson(settings.getState()));
        var reopened = new SlangProjectSettings();
        reopened.loadState(gson.fromJson(saved, SlangProjectSettings.SettingsState.class));
        assertEquals("compute/blue", reopened.getShaderVariant("file"));
        assertEquals("/Root.slang", reopened.getPreprocessorContext("file"));
        assertNull(new SlangPreviewState().peek("file"));
        assertFalse(saved.contains("preview"));
        assertFalse(saved.contains("MODE"));
    }
}
