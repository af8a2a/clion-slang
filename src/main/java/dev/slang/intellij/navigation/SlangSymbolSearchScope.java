package dev.slang.intellij.navigation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Excludes build snapshots at query time, including entries already stored by older versions. */
final class SlangSymbolSearchScope extends DelegatingGlobalSearchScope {
    private final List<String> roots;

    SlangSymbolSearchScope(GlobalSearchScope base, List<String> roots) {
        super(base, List.copyOf(roots));
        this.roots = List.copyOf(roots);
    }

    static GlobalSearchScope forProject(Project project, GlobalSearchScope base) {
        if (SlangProjectSettings.getInstance(project).isIncludeBuildOutputSymbols()) return base;
        var roots = new ArrayList<String>();
        if (project.getBasePath() != null) roots.add(project.getBasePath());
        for (var root : ProjectRootManager.getInstance(project).getContentRoots()) roots.add(root.getPath());
        return new SlangSymbolSearchScope(base, roots);
    }

    @Override public boolean contains(@NotNull VirtualFile file) {
        return super.contains(file) && !isBuildOutput(file.getPath(), roots, file.getFileSystem().isCaseSensitive());
    }

    static boolean isBuildOutput(String path, List<String> roots, boolean caseSensitive) {
        String normalized = normalize(path, caseSensitive);
        for (String root : roots) {
            String prefix = normalize(root, caseSensitive);
            if (!prefix.endsWith("/")) prefix += "/";
            if (!normalized.startsWith(prefix)) continue;
            String relative = normalized.substring(prefix.length());
            int slash = relative.indexOf('/');
            if (slash < 0) continue; // A source file called 'build' is not a build directory.
            String directory = relative.substring(0, slash);
            if (directory.equals("build") || directory.startsWith("cmake-build-")) return true;
        }
        return false;
    }

    private static String normalize(String path, boolean caseSensitive) {
        path = path.replace('\\', '/');
        return caseSensitive ? path : path.toLowerCase(Locale.ROOT);
    }
}
