package dev.slang.intellij.navigation;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;

public class SlangInheritancePlatformTest extends BasePlatformTestCase {
    public void testAliasesFollowGenericAndTypedefChainsAndInvalidateUnsavedTargets() {
        var root = myFixture.addFileToProject("API.slang", "namespace Demo { interface IRoot {} }");
        myFixture.addFileToProject("Impl.slang", "namespace Demo { struct Impl<T> : IRoot {} }");
        var aliases = myFixture.addFileToProject("Aliases.slang", """
                namespace Demo {
                    typealias Program<T> = Impl<T>;
                    typealias SceneProgram = Program<float>;
                    typedef SceneProgram Legacy;
                    struct Child : Legacy {}
                    typealias Identity<T> = T;
                }
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(List.of("Child", "Impl", "Legacy", "Program", "SceneProgram"), names(derived(root)));
        var markers = new ArrayList<LineMarkerInfo<?>>();
        new SlangInterfaceLineMarkerProvider().collectSlowLineMarkers(
                List.of(root.findElementAt(rootSymbol(root).offset())), markers);
        assertEquals(1, markers.size());
        assertEquals("2 derived types of Demo.IRoot (and 3 type aliases)", markers.getFirst().getLineMarkerTooltip());
        var document = PsiDocumentManager.getInstance(getProject()).getDocument(aliases);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("namespace Demo { typealias Program = float; }"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(List.of("Impl"), names(derived(root)));
    }

    public void testUsingNamespacesResolveCompetingInterfaceNamesAndDoNotLeakAcrossScopes() {
        var root = myFixture.addFileToProject("A.slang", "namespace A { interface IRoot {} }");
        myFixture.addFileToProject("B.slang", "namespace B { interface IRoot {} }");
        myFixture.addFileToProject("Impl.slang", """
                namespace Imported { using A; typealias Local = IRoot; struct Correct : Local {} }
                namespace Other { using B; struct Wrong : IRoot {} }
                namespace Ambiguous { using A; using B; struct Unknown : IRoot {} }
                namespace Sibling { struct NotImported : IRoot {} }
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(List.of("Correct", "Local"), names(derived(root)));
    }

    private List<String> names(List<PsiElement> targets) {
        return targets.stream().map(e -> ((SlangSymbolNavigationItem)e).getName()).toList();
    }

    public void testInheritedUsingKeepsItsDeclarationScope() {
        var root = myFixture.addFileToProject("A.slang", "namespace A { interface IRoot {} }");
        myFixture.addFileToProject("Shadow.slang", "namespace B.A { interface IRoot {} }");
        myFixture.addFileToProject("Impl.slang", "using A; namespace B { struct Correct : IRoot {} }");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(List.of("Correct"), names(derived(root)));
    }

    public void testUsingBindsEmptyNearerNamespaceAndNearestImportedTypesWin() {
        var root = myFixture.addFileToProject("A.slang", "namespace A { interface IRoot {} }");
        myFixture.addFileToProject("Global.slang", "interface IRoot {}");
        myFixture.addFileToProject("Impl.slang", """
                namespace B { namespace A {} using A; struct Wrong : IRoot {} }
                namespace C { using A; struct Correct : IRoot {} }
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(List.of("Correct"), names(derived(root)));
    }

    public void testEmptyNamespaceShadowDoesNotFallBackToUniqueProjectName() {
        var root = myFixture.addFileToProject("A.slang", "namespace A { interface IRoot {} }");
        myFixture.addFileToProject("Impl.slang", "namespace B { namespace A {} using A; struct Wrong : IRoot {} }");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertTrue(derived(root).isEmpty());
    }

    public void testUnopenedGenericIndirectTypesGutterAndNavigation() {
        var root = myFixture.addFileToProject("API.slang", "namespace Demo { interface IRoot {} }");
        myFixture.addFileToProject("Child.slang", "namespace Demo { interface IChild : IRoot {} }");
        String source = "// 😀\r\nnamespace Demo { struct Loader<T : IRoot> : IChild {} struct Constraint<T : IRoot> {} }";
        var child = myFixture.addFileToProject("Impl.slang", source);
        myFixture.addFileToProject("build/copy/Impl.slang", source);
        myFixture.addFileToProject("cmake-build-debug/Impl.slang", source);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var targets = derived(root);
        assertEquals(List.of("IChild", "Loader"), targets.stream().map(e -> ((SlangSymbolNavigationItem)e).getName()).toList());
        var marker = new ArrayList<LineMarkerInfo<?>>();
        var symbol = rootSymbol(root);
        var leaf = root.findElementAt(symbol.offset());
        assertNotNull(leaf);
        new SlangInterfaceLineMarkerProvider().collectSlowLineMarkers(List.of(leaf), marker);
        assertEquals(1, marker.size());
        assertEquals(leaf, marker.getFirst().getElement());
        assertTrue(marker.getFirst().getLineMarkerTooltip().contains("2 derived types"));
        var loader = (SlangSymbolNavigationItem) targets.getLast();
        assertEquals(child, loader.getContainingFile());
        assertTrue(loader.getLocationString().contains("Demo"));
        loader.navigate(true);
        // IntelliJ documents normalize CRLF; navigation offsets are UTF-16 document offsets.
        assertEquals(child.getText().indexOf("Loader"), FileEditorManager.getInstance(getProject()).getSelectedTextEditor().getCaretModel().getOffset());
    }

    public void testUnsavedRemovalRenameAndDeletionUpdateReverseEdges() {
        var root = myFixture.addFileToProject("API.slang", "interface IRoot {}");
        var child = myFixture.addFileToProject("Impl.slang", "struct Before : IRoot {}");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(1, derived(root).size());
        var document = PsiDocumentManager.getInstance(getProject()).getDocument(child);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("struct After : IRoot {}"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals("After", ((SlangSymbolNavigationItem) derived(root).getFirst()).getName());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("struct After {}"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(derived(root).isEmpty());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> child.delete());
        assertTrue(derived(root).isEmpty());
    }

    public void testCyclesAndAmbiguousInterfacesDoNotDuplicateOrCrossLink() {
        var root = myFixture.addFileToProject("A.slang", "namespace A { interface IRoot : IChild {} interface IChild : IRoot {} }");
        myFixture.addFileToProject("B.slang", "namespace B { interface IRoot {} struct Other : IRoot {} }");
        myFixture.addFileToProject("C.slang", "struct Ambiguous : IRoot {} struct Qualified : A.IRoot {}");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(List.of("IChild", "Qualified"), derived(root).stream().map(e -> ((SlangSymbolNavigationItem)e).getName()).toList());
    }

    private SlangSymbol rootSymbol(PsiFile root) {
        return SlangSymbolExtractor.extract(root.getText()).stream().filter(s -> s.name().equals("IRoot")).findFirst().orElseThrow();
    }
    private List<PsiElement> derived(PsiFile root) {
        return SlangDerivedTypes.find(getProject(), root.getVirtualFile(), rootSymbol(root));
    }
}
