package dev.slang.intellij.navigation;

import com.intellij.ide.actions.searcheverywhere.PsiElementsEqualityProvider;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.psi.PsiFile;
import com.intellij.util.Processor;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SlangSymbolDeduplicationTest {
    private final Project project = proxy(Project.class, Map.of());
    private final MemoryFile file = new MemoryFile("/Shaders/GPUDrivenCullingCommon.slang", false);
    private static SlangSymbol symbol(int offset, String parameters) {
        return new SlangSymbol("cullingCamera", "Metallic.GPUDriven", "cullingCamera(" + parameters + ")",
                SlangSymbol.Kind.FUNCTION, offset);
    }
    private SlangSymbolNavigationItem item(MemoryFile file, int offset, String parameters) {
        // A fresh PSI view each time, as in independent native search passes.
        return new SlangSymbolNavigationItem(proxy(PsiFile.class, Map.of("getProject", project, "getVirtualFile", file)),
                symbol(offset, parameters));
    }

    @Test public void sameDeclarationAcrossPsiViewsIsEqualInNativeSearchEverywhere() {
        var first = item(file, 64, "GPUDrivenPreviewParams params");
        var repeated = item(file, 64, "GPUDrivenPreviewParams params");
        assertNotSame(first.getContainingFile(), repeated.getContainingFile());
        assertEquals(first, repeated);
        assertEquals(first.hashCode(), repeated.hashCode());
        assertTrue(first.isEquivalentTo(repeated));
        var contributor = proxy(SearchEverywhereContributor.class, Map.of());
        assertTrue(new PsiElementsEqualityProvider().areEqual(
                new SearchEverywhereFoundElementInfo(first, 0, contributor),
                new SearchEverywhereFoundElementInfo(repeated, 0, contributor)));
    }

    @Test public void presentationChangesDoNotCreateAnotherDeclarationButOverloadsRemain() {
        var original = item(file, 64, "GPUDrivenPreviewParams params");
        var newPresentation = item(file, 64, "GPUDrivenPreviewParams value");
        var overload = item(file, 164, "OtherParams params");
        var anotherFile = item(new MemoryFile("/Other/GPUDrivenCullingCommon.slang", false), 64, "GPUDrivenPreviewParams params");
        assertEquals(original, newPresentation);
        assertNotEquals(original, overload);
        assertNotEquals(original, anotherFile);
        assertEquals(3, new HashSet<>(List.of(original, newPresentation, overload, anotherFile)).size());
    }

    @Test public void canonicalFileAndCaseRulesDetermineIdentity() {
        var alias = new MemoryFile("/Link/Camera.slang", false);
        alias.canonical = file;
        assertEquals(item(file, 64, "P p"), item(alias, 64, "P p"));
        assertEquals(item(file, 64, "P p"), item(new MemoryFile(file.getPath().toUpperCase(java.util.Locale.ROOT), false), 64, "P p"));
        assertNotEquals(item(new MemoryFile("/A.slang", true), 64, "P p"), item(new MemoryFile("/a.slang", true), 64, "P p"));
    }

    @Test public void duplicateNameAndResultCallbacksEmitOncePerInvocation() {
        var names = new ArrayList<String>();
        Processor<String> uniqueNames = SlangGotoSymbolContributor.distinct(names::add);
        for (int i = 0; i < 30; i++) assertTrue(uniqueNames.process("cullingCamera"));
        assertEquals(List.of("cullingCamera"), names);
        var output = new ArrayList<SlangSymbolNavigationItem>();
        Processor<SlangSymbolNavigationItem> results = SlangGotoSymbolContributor.distinct(output::add);
        for (int i = 0; i < 30; i++) assertTrue(results.process(item(file, 64, "P p")));
        assertTrue(results.process(item(file, 164, "Other p")));
        assertEquals(2, output.size());
        // New queries must return the same symbol again; no persistent suppression cache.
        assertTrue(SlangGotoSymbolContributor.<SlangSymbolNavigationItem>distinct(output::add).process(item(file, 64, "P p")));
        assertEquals(3, output.size());
    }

    @Test public void processorStopIsPropagatedEvenForSubsequentDuplicateCallbacks() {
        var output = new ArrayList<String>();
        Processor<String> processor = SlangGotoSymbolContributor.distinct(value -> { output.add(value); return false; });
        assertFalse(processor.process("cullingCamera"));
        assertFalse(processor.process("cullingCamera"));
        assertFalse(processor.process("another"));
        assertEquals(List.of("cullingCamera"), output);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            return methods.get(method.getName());
        });
    }

    static final class MemoryFile extends VirtualFile {
        private final String path;
        private final VirtualFileSystem fs;
        private VirtualFile canonical;
        MemoryFile(String path, boolean caseSensitive) {
            this.path = path;
            fs = new DeprecatedVirtualFileSystem() {
                @Override public String getProtocol() { return "test"; }
                @Override public VirtualFile findFileByPath(String path) { return null; }
                @Override public void refresh(boolean async) {}
                @Override public VirtualFile refreshAndFindFileByPath(String path) { return null; }
                @Override public boolean isCaseSensitive() { return caseSensitive; }
            };
        }
        @Override public VirtualFile getCanonicalFile() { return canonical == null ? this : canonical; }
        @Override public String getName() { return path.substring(path.lastIndexOf('/') + 1); }
        @Override public String getPath() { return path; }
        @Override public VirtualFileSystem getFileSystem() { return fs; }
        @Override public boolean isWritable() { return false; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public VirtualFile getParent() { return null; }
        @Override public VirtualFile[] getChildren() { return EMPTY_ARRAY; }
        @Override public OutputStream getOutputStream(Object requestor, long stamp, long time) { throw new UnsupportedOperationException(); }
        @Override public byte[] contentsToByteArray() { return new byte[0]; }
        @Override public long getTimeStamp() { return 0; }
        @Override public long getLength() { return 0; }
        @Override public void refresh(boolean async, boolean recursive, Runnable post) { if (post != null) post.run(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    }
}
