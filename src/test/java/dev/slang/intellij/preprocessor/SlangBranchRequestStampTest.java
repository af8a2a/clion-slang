package dev.slang.intellij.preprocessor;

import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SlangBranchRequestStampTest {
    @Test public void rejectsOldDocumentDependenciesServerAndForeignResponses() {
        Object server = new Object();
        var stamp = new SlangBranchRequestStamp(server, "file:///a.slang", 7, 42, 3);
        var result = new SlangPreprocessorTrace("file:///a.slang", 7, List.of(), List.of());
        assertTrue(stamp.accepts(result, server, 7, 42, 3));
        assertFalse(stamp.accepts(result, server, 8, 42, 3));
        assertFalse(stamp.accepts(result, server, 7, 43, 3));
        assertFalse(stamp.accepts(result, server, 7, 42, 4)); // include/config changed; root version did not
        assertFalse(stamp.accepts(result, new Object(), 7, 42, 3)); // restart
        assertFalse(stamp.accepts(null, server, 7, 42, 3));
        assertFalse(stamp.accepts(new SlangPreprocessorTrace("file:///b.slang", 7, List.of(), List.of()), server, 7, 42, 3));
        assertFalse(stamp.accepts(new SlangPreprocessorTrace("file:///a.slang", 6, List.of(), List.of()), server, 7, 42, 3));
    }

    @Test public void settingsAreEnabledByDefaultCopiedAndPersistedIndependently() {
        var settings = new SlangProjectSettings();
        assertTrue(settings.isShowPreprocessorBranches());
        assertTrue(settings.isShowPreprocessorBranchLabels());
        settings.setShowPreprocessorBranchLabels(false);
        assertTrue(settings.isShowPreprocessorBranches());
        var copy = settings.getState();
        copy.showPreprocessorBranches = false;
        assertTrue(settings.isShowPreprocessorBranches());
        settings.loadState(copy);
        assertFalse(settings.isShowPreprocessorBranches());
        assertFalse(settings.isShowPreprocessorBranchLabels());
        copy.showPreprocessorBranches = true;
        assertFalse(settings.isShowPreprocessorBranches());
    }

    @Test public void recognizesShaderDependencyAndConfigurationChanges() {
        for (String path : List.of("E:\\Shaders\\Common.SLANGH", "/external/config.h", "/project/slangdconfig.json",
                "/shared/utils.hlsli", "/project/defs.inc")) {
            assertTrue(path, SlangBranchDisplayService.isShaderDependency(path));
        }
        assertFalse(SlangBranchDisplayService.isShaderDependency("/project/build/output.obj"));
        assertFalse(SlangBranchDisplayService.isShaderDependency("/project/README.md"));
    }
}
