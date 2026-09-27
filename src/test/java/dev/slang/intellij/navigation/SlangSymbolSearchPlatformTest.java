package dev.slang.intellij.navigation;

import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.indexing.FindSymbolParameters;
import dev.slang.intellij.settings.SlangProjectSettings;
import java.util.ArrayList;
import java.util.List;

/** Native index/contributor integration, including unopened files and unsaved documents. */
public class SlangSymbolSearchPlatformTest extends BasePlatformTestCase {
    public void testBuildSnapshotsHiddenByDefaultAndSearchableWhenOptedIn() {
        String source = "uint materialClassForPixel(uint2 pixel) { return 0; }";
        myFixture.addFileToProject("Shaders/VisibilityMaterialBinning.slang", source);
        myFixture.addFileToProject("build/workload-1/source/Shaders/VisibilityMaterialBinning.slang", source);
        myFixture.addFileToProject("build/workload-2/source/Shaders/VisibilityMaterialBinning.slang", source);
        myFixture.addFileToProject("cmake-build-debug/Shaders/VisibilityMaterialBinning.slang", source);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var scope = GlobalSearchScope.projectScope(getProject());
        assertEquals(1, search("materialClassForPixel", scope).size());
        var settings = SlangProjectSettings.getInstance(getProject());
        settings.setIncludeBuildOutputSymbols(true);
        assertEquals(4, search("materialClassForPixel", scope).size());
        settings.setIncludeBuildOutputSymbols(false);
        assertEquals(1, search("materialClassForPixel", scope).size());
    }
    public void testUnopenedFileIsIndexedAndNavigationLandsOnName() {
        String source = "// 😀\nuint streamNormalStride(uint format) { return format; }";
        var file = myFixture.addFileToProject("Shaders/Stream.slang", source);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertTrue(ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList().stream()
                .anyMatch(c -> c instanceof SlangGotoSymbolContributor));
        var result = search("streamNormalStride", GlobalSearchScope.projectScope(getProject()));
        assertEquals(1, result.size());
        assertEquals("streamNormalStride(uint format)", result.getFirst().getPresentation().getPresentableText());
        assertTrue(result.getFirst().getPresentation().getLocationString().contains("Shaders/Stream.slang"));
        result.getFirst().navigate(true);
        var editor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull(editor);
        assertEquals(source.indexOf("streamNormalStride"), editor.getCaretModel().getOffset());
        assertEquals(file.getVirtualFile(), FileDocumentManager.getInstance().getFile(editor.getDocument()));
    }

    public void testOverloadsScopeUnsavedRenameAndDeletion() throws Exception {
        var first = myFixture.addFileToProject("One.slang", "float findMe(float a); float findMe(int a);");
        var second = myFixture.addFileToProject("Two.slangh", "float findMe(uint a);");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var scope = GlobalSearchScope.projectScope(getProject());
        assertEquals(3, search("findMe", scope).size());
        assertEquals(2, search("findMe", GlobalSearchScope.fileScope(first)).size());
        var doc = PsiDocumentManager.getInstance(getProject()).getDocument(first);
        assertNotNull(doc);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText("float renamed(float a);"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(1, search("renamed", scope).size());
        assertEquals(1, search("findMe", scope).size());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> second.delete());
        assertTrue(search("findMe", scope).isEmpty());
    }

    private List<NavigationItem> search(String name, GlobalSearchScope scope) {
        var result = new ArrayList<NavigationItem>();
        new SlangGotoSymbolContributor().processElementsWithName(name, item -> { result.add(item); return true; },
                FindSymbolParameters.wrap(name, scope));
        return result;
    }
}
