package dev.slang.intellij.navigation;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.slang.intellij.lang.SlangFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.*;

public final class SlangInterfaceLineMarkerProvider implements LineMarkerProvider {
    @Override public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) { return null; }

    @Override public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                                @NotNull Collection<? super LineMarkerInfo<?>> result) {
        var leaves = new HashMap<PsiFile, Map<Integer, PsiElement>>();
        for (var element : elements) {
            var file = element.getContainingFile();
            if (file == null || file.getFileType() != SlangFileType.INSTANCE || element.getFirstChild() != null
                    || file.getVirtualFile() == null || DumbService.isDumb(element.getProject())) continue;
            leaves.computeIfAbsent(file, key -> new HashMap<>()).put(element.getTextOffset(), element);
        }
        for (var entry : leaves.entrySet()) {
            var file = entry.getKey();
            for (var symbol : SlangSymbolExtractor.extract(file.getViewProvider().getContents())) {
                var leaf = entry.getValue().get(symbol.offset());
                if (!symbol.isInterface() || leaf == null) continue;
                var derived = SlangDerivedTypes.find(file.getProject(), file.getVirtualFile(), symbol);
                if (derived.isEmpty()) continue;
                long aliases = derived.stream().filter(e -> e instanceof SlangSymbolNavigationItem item && item.isTypeAlias()).count();
                String tooltip = (derived.size() - aliases) + " derived types of " + symbol.qualifiedName()
                        + (aliases == 0 ? "" : " (and " + aliases + " type aliases)");
                result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                        .setTargets(derived)
                        .setTooltipText(tooltip)
                        .setPopupTitle((aliases == 0 ? "Derived types of " : "Derived types and aliases of ") + symbol.qualifiedName())
                        .createLineMarkerInfo(leaf));
            }
        }
    }
}
