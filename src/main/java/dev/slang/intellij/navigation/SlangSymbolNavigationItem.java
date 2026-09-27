package dev.slang.intellij.navigation;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import dev.slang.intellij.lang.SlangLanguage;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Locale;

/** PSI facade supplies file previews and native Search Everywhere navigation. */
final class SlangSymbolNavigationItem extends FakePsiElement {
    private final PsiFile file;
    private final SlangSymbol symbol;
    private final Project project;
    private final String sourceUrl;
    SlangSymbolNavigationItem(PsiFile file, SlangSymbol symbol) {
        this.file = file;
        this.symbol = symbol;
        this.project = file.getProject();
        this.sourceUrl = sourceUrl(file.getVirtualFile());
    }
    private static String sourceUrl(VirtualFile file) {
        VirtualFile canonical = file.getCanonicalFile();
        if (canonical != null) file = canonical;
        String url = file.getUrl();
        return file.getFileSystem().isCaseSensitive() ? url : url.toLowerCase(Locale.ROOT);
    }
    String qualifiedName() { return symbol.qualifiedName(); }
    @Override public @NotNull Project getProject() { return project; }
    @Override public @NotNull Language getLanguage() { return SlangLanguage.INSTANCE; }
    @Override public @NotNull PsiElement getParent() { return file; }
    @Override public @NotNull PsiFile getContainingFile() { return file; }
    @Override public @NotNull String getName() { return symbol.name(); }
    @Override public @NotNull String getText() { return symbol.name(); }
    @Override public int getTextOffset() { return symbol.offset(); }
    @Override public int getTextLength() { return symbol.name().length(); }
    @Override public @NotNull TextRange getTextRange() {
        return TextRange.from(symbol.offset(), symbol.name().length());
    }
    @Override public @NotNull String getPresentableText() { return symbol.signature(); }
    @Override public @NotNull String getLocationString() {
        String path = file.getVirtualFile().getPath(), base = getProject().getBasePath();
        if (base != null && path.startsWith(base + "/")) path = path.substring(base.length() + 1);
        return symbol.container().isEmpty() ? path : symbol.container() + " — " + path;
    }
    @Override public Icon getIcon(boolean open) {
        return switch (symbol.kind()) {
            case FUNCTION -> AllIcons.Nodes.Function;
            case TYPE -> AllIcons.Nodes.Class;
            case NAMESPACE -> AllIcons.Nodes.Package;
            case FIELD -> AllIcons.Nodes.Field;
            case VARIABLE -> AllIcons.Nodes.Gvariable;
            case ENUM_MEMBER -> AllIcons.Nodes.Enum;
            case ALIAS -> AllIcons.Nodes.Alias;
        };
    }
    @Override public boolean isValid() { return !getProject().isDisposed() && file.isValid(); }
    @Override public boolean canNavigate() { return isValid() && file.getVirtualFile().isValid(); }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
    @Override public void navigate(boolean requestFocus) {
        if (canNavigate()) new OpenFileDescriptor(getProject(), file.getVirtualFile(), symbol.offset()).navigate(requestFocus);
    }
    @Override public boolean equals(Object other) {
        // PSI wrappers and presentation details can change between search passes. A declaration's
        // physical file and offset do not; overloads/other source files keep distinct identities.
        return other instanceof SlangSymbolNavigationItem item && project == item.project
                && sourceUrl.equals(item.sourceUrl) && symbol.offset() == item.symbol.offset();
    }
    @Override public int hashCode() {
        return 31 * (31 * System.identityHashCode(project) + sourceUrl.hashCode()) + symbol.offset();
    }
    @Override public boolean isEquivalentTo(PsiElement other) { return equals(other); }
}
