package dev.slang.intellij.navigation;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import dev.slang.intellij.lang.SlangFileType;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Reverse edges allow gutter queries without scanning every project file. */
public final class SlangInheritanceIndex extends FileBasedIndexExtension<String, List<SlangSymbol>> {
    public static final ID<String, List<SlangSymbol>> NAME = ID.create("dev.slang.inheritance");
    static String shortName(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    static String namespaceKey(String name) { return "@namespace/" + name; }
    @Override public @NotNull ID<String, List<SlangSymbol>> getName() { return NAME; }
    @Override public @NotNull DataIndexer<String, List<SlangSymbol>, FileContent> getIndexer() {
        return content -> {
            var result = new LinkedHashMap<String, List<SlangSymbol>>();
            for (var symbol : SlangSymbolExtractor.extract(content.getContentAsText())) {
                if (symbol.kind() == SlangSymbol.Kind.NAMESPACE) {
                    // Dotted namespace declarations also declare their implicit parents.
                    String namespace = symbol.qualifiedName();
                    while (!namespace.isEmpty()) {
                        result.computeIfAbsent(namespaceKey(namespace), key -> new ArrayList<>()).add(symbol);
                        int dot = namespace.lastIndexOf('.');
                        namespace = dot < 0 ? "" : namespace.substring(0, dot);
                    }
                }
                for (String base : symbol.baseTypes()) {
                    var entries = result.computeIfAbsent(shortName(base), key -> new ArrayList<>());
                    if (!entries.contains(symbol)) entries.add(symbol);
                }
            }
            return result;
        };
    }
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() { return EnumeratorStringDescriptor.INSTANCE; }
    @Override public @NotNull DataExternalizer<List<SlangSymbol>> getValueExternalizer() { return SlangSymbolIndex.VALUES; }
    @Override public int getVersion() { return 4; }
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(SlangFileType.INSTANCE);
    }
    @Override public boolean dependsOnFileContent() { return true; }
}
