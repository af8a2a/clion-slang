package dev.slang.intellij.navigation;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.*;
import java.util.function.Predicate;

/** Conservative declaration-based hierarchy; ambiguous type names are not linked. */
final class SlangDerivedTypes {
    record Declaration(VirtualFile file, SlangSymbol symbol) {}

    static List<PsiElement> find(Project project, VirtualFile file, SlangSymbol symbol) {
        var scope = SlangSymbolSearchScope.forProject(project, GlobalSearchScope.projectScope(project));
        var index = FileBasedIndex.getInstance();
        var declarations = new HashMap<String, List<Declaration>>();
        var namespaces = new HashMap<String, Boolean>();
        Predicate<String> namespaceExists = name -> namespaces.computeIfAbsent(name, key ->
                !index.getValues(SlangInheritanceIndex.NAME, SlangInheritanceIndex.namespaceKey(key), scope).isEmpty());
        var root = new Declaration(file, symbol);
        var visited = new LinkedHashSet<Declaration>();
        var pending = new ArrayDeque<Declaration>();
        visited.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            ProgressManager.checkCanceled();
            var parent = pending.removeFirst();
            var possibleBases = declarations.computeIfAbsent(parent.symbol().name(), name -> {
                var types = new ArrayList<Declaration>();
                index.processValues(SlangSymbolIndex.NAME, name, null, (typeFile, symbols) -> {
                    for (var type : symbols)
                        if (type.kind() == SlangSymbol.Kind.TYPE || type.kind() == SlangSymbol.Kind.ALIAS)
                            types.add(new Declaration(typeFile, type));
                    return true;
                }, scope);
                return types;
            });
            var children = new ArrayList<Declaration>();
            index.processValues(SlangInheritanceIndex.NAME, parent.symbol().name(), null, (childFile, symbols) -> {
                for (var child : symbols) children.add(new Declaration(childFile, child));
                return true;
            }, scope);
            // Resolve namespaces outside index callbacks to avoid nested index access.
            for (var declaration : children) {
                ProgressManager.checkCanceled();
                var child = declaration.symbol();
                if (child.baseTypes().stream().anyMatch(base -> resolvesTo(base, child.container(),
                        child.usingNamespaces(), possibleBases, parent, namespaceExists))) {
                    if (visited.add(declaration)) pending.add(declaration);
                }
            }
        }
        visited.remove(root);
        var result = new ArrayList<PsiElement>();
        visited.stream().sorted(Comparator.comparing((Declaration d) -> d.symbol().qualifiedName())
                .thenComparing(d -> d.file().getPath()).thenComparingInt(d -> d.symbol().offset())).forEach(d -> {
            var psi = PsiManager.getInstance(project).findFile(d.file());
            if (psi != null) result.add(new SlangSymbolNavigationItem(psi, d.symbol()));
        });
        return List.copyOf(result);
    }

    static boolean resolvesTo(String base, String owner, List<Declaration> candidates, Declaration target) {
        return resolvesTo(base, owner, List.of(), candidates, target, ignored -> false);
    }

    static boolean resolvesTo(String base, String owner, List<SlangSymbol.NamespaceImport> usingNamespaces,
                              List<Declaration> candidates, Declaration target, Predicate<String> namespaceExists) {
        if (!SlangInheritanceIndex.shortName(base).equals(target.symbol().name())) return false;
        String prefix = owner;
        while (true) {
            String qualified = prefix.isEmpty() ? base : prefix + "." + base;
            var matches = candidates.stream().filter(d -> d.symbol().qualifiedName().equals(qualified)).toList();
            if (!matches.isEmpty()) return matches.size() == 1 && matches.getFirst().equals(target);
            var imported = new HashSet<Declaration>();
            for (var namespace : usingNamespaces) {
                if (!namespace.owner().equals(prefix)) continue;
                String bindingScope = namespace.owner();
                while (true) {
                    String bound = (bindingScope.isEmpty() ? "" : bindingScope + ".") + namespace.name();
                    if (namespaceExists.test(bound)) {
                        String importedName = bound + "." + base;
                        candidates.stream().filter(d -> d.symbol().qualifiedName().equals(importedName))
                                .forEach(imported::add);
                        // The namespace is bound here even when it does not contain this type.
                        break;
                    }
                    if (bindingScope.isEmpty()) break;
                    bindingScope = parentScope(bindingScope);
                }
            }
            if (!imported.isEmpty()) return imported.size() == 1 && imported.contains(target);
            if (prefix.isEmpty()) break;
            prefix = parentScope(prefix);
        }
        // Module imports can make an otherwise unique simple name visible.
        // Without compiler resolution, never choose between competing namespaces/modules.
        return usingNamespaces.isEmpty() && !base.contains(".") && candidates.size() == 1 && candidates.getFirst().equals(target);
    }

    private static String parentScope(String scope) {
        int dot = scope.lastIndexOf('.');
        return dot < 0 ? "" : scope.substring(0, dot);
    }
}
