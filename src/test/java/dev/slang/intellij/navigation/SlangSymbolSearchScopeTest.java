package dev.slang.intellij.navigation;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.junit.Test;

import java.util.List;
import java.util.stream.IntStream;
import static org.junit.Assert.*;

public class SlangSymbolSearchScopeTest {
    @Test public void actualSnapshotPatternKeepsOnlyOriginalWithoutMergingSymbolsAcrossFiles() {
        var base = allFiles();
        var scope = new SlangSymbolSearchScope(base, List.of("E:/metallic"));
        String suffix = "/Shaders/Features/VisibilityBuffer/VisibilityMaterialBinning.slang";
        var files = new java.util.ArrayList<VirtualFile>();
        files.add(file("E:/metallic" + suffix));
        IntStream.range(0, 30).forEach(i -> files.add(file("E:/metallic/build/workload-" + i + "/source" + suffix)));
        assertEquals(31, files.stream().filter(base::contains).count());
        assertEquals(List.of(files.getFirst()), files.stream().filter(scope::contains).toList());
        // Different legitimate source files remain, even if they contain identical declarations.
        assertTrue(scope.contains(file("E:/metallic/Tests" + suffix)));
    }

    @Test public void projectAndAdditionalContentRootsBothFilterCmakeAndBuildOutputs() {
        var scope = new SlangSymbolSearchScope(allFiles(), List.of("E:/metallic", "D:/ShaderLibrary/"));
        for (String path : List.of("E:/metallic/build/run/source/A.slang", "E:/metallic/cmake-build-debug/Shaders/A.slang",
                "D:/ShaderLibrary/build/A.slang", "D:/ShaderLibrary/cmake-build-release/A.slang"))
            assertFalse(path, scope.contains(file(path)));
        for (String path : List.of("E:/metallic/Shaders/A.slang", "E:/metallic/Shaders/build/A.slang",
                "E:/metallic/building/A.slang", "E:/metallic2/build/A.slang", "D:/Other/build/A.slang"))
            assertTrue(path, scope.contains(file(path)));
    }

    @Test public void preservesBaseScopeAndFilesystemCaseRules() {
        var base = new GlobalSearchScope() {
            @Override public boolean contains(VirtualFile file) { return file.getName().equals("Allowed.slang"); }
            @Override public boolean isSearchInModuleContent(Module module) { return true; }
            @Override public boolean isSearchInLibraries() { return false; }
        };
        var scope = new SlangSymbolSearchScope(base, List.of("E:/metallic"));
        assertFalse(scope.contains(file("E:/metallic/Shaders/Other.slang")));
        assertTrue(scope.contains(file("E:/metallic/Shaders/Allowed.slang")));
        assertTrue(SlangSymbolSearchScope.isBuildOutput("e:\\METALLIC\\BUILD\\Allowed.slang", List.of("E:/metallic"), false));
        assertFalse(SlangSymbolSearchScope.isBuildOutput("/project/Build/A.slang", List.of("/project"), true));
        // Opening a snapshot as its own project must still search its source tree.
        assertFalse(SlangSymbolSearchScope.isBuildOutput("/repo/build/run/source/Shaders/A.slang",
                List.of("/repo/build/run/source"), true));
    }

    @Test public void buildOutputOptInPersistsAndDefaultsOffForUpgrades() {
        var settings = new SlangProjectSettings();
        assertFalse(settings.isIncludeBuildOutputSymbols());
        settings.setIncludeBuildOutputSymbols(true);
        var saved = settings.getState();
        settings.setIncludeBuildOutputSymbols(false);
        settings.loadState(saved);
        assertTrue(settings.isIncludeBuildOutputSymbols());
        saved.includeBuildOutputSymbols = false;
        assertTrue(settings.isIncludeBuildOutputSymbols());
        settings.loadState(new SlangProjectSettings.SettingsState());
        assertFalse(settings.isIncludeBuildOutputSymbols());
    }

    private static VirtualFile file(String path) { return new SlangSymbolDeduplicationTest.MemoryFile(path, false); }
    private static GlobalSearchScope allFiles() {
        return new GlobalSearchScope() {
            @Override public boolean contains(VirtualFile file) { return true; }
            @Override public boolean isSearchInModuleContent(Module module) { return true; }
            @Override public boolean isSearchInLibraries() { return true; }
        };
    }
}
