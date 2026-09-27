package dev.slang.intellij.navigation;

import com.intellij.lang.Language;
import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.GotoClassContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import dev.slang.intellij.lang.SlangLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the normal Symbols tab: platform matching, scope selection, limits and cancellation. */
public final class SlangGotoSymbolContributor implements ChooseByNameContributorEx, GotoClassContributor {
    @Override public void processNames(@NotNull Processor<? super String> processor,
            @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        var project = scope.getProject();
        if (project == null || project.isDisposed() || DumbService.isDumb(project)) return;
        FileBasedIndex.getInstance().processAllKeys(SlangSymbolIndex.NAME, distinct(processor),
                SlangSymbolSearchScope.forProject(project, scope), filter);
    }
    @Override public void processElementsWithName(@NotNull String name,
            @NotNull Processor<? super NavigationItem> processor, @NotNull FindSymbolParameters parameters) {
        var project = parameters.getProject();
        if (project == null || project.isDisposed() || DumbService.isDumb(project)) return;
        var scope = SlangSymbolSearchScope.forProject(project, parameters.getSearchScope());
        Processor<NavigationItem> uniqueItems = distinct(processor);
        FileBasedIndex.getInstance().processValues(SlangSymbolIndex.NAME, name, null, (file, symbols) -> {
            ProgressManager.checkCanceled();
            if (!file.isValid() || !scope.contains(file)) return true;
            var psi = PsiManager.getInstance(project).findFile(file);
            if (psi == null) return true;
            for (var symbol : symbols) {
                ProgressManager.checkCanceled();
                if (!uniqueItems.process(new SlangSymbolNavigationItem(psi, symbol))) return false;
            }
            return true;
        }, scope, parameters.getIdFilter());
    }
    @Override public @Nullable String getQualifiedName(@NotNull NavigationItem item) {
        return item instanceof SlangSymbolNavigationItem symbol ? symbol.qualifiedName() : null;
    }
    @Override public @NotNull String getQualifiedNameSeparator() { return "."; }
    @Override public @NotNull Language getElementLanguage() { return SlangLanguage.INSTANCE; }

    /** Index streams may revisit a key/value; uniqueness belongs to this invocation, not a global cache. */
    static <T> Processor<T> distinct(Processor<? super T> target) {
        Set<T> seen = ConcurrentHashMap.newKeySet();
        var stopped = new AtomicBoolean();
        return value -> {
            ProgressManager.checkCanceled();
            if (stopped.get()) return false;
            if (seen.add(value) && !target.process(value)) { stopped.set(true); return false; }
            return !stopped.get();
        };
    }
}
