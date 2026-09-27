package dev.slang.intellij.navigation;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.IOUtil;
import dev.slang.intellij.lang.SlangFileType;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content-dependent indexing includes unopened files and IDE-managed unsaved document updates. */
public final class SlangSymbolIndex extends FileBasedIndexExtension<String, List<SlangSymbol>> {
    public static final ID<String, List<SlangSymbol>> NAME = ID.create("dev.slang.symbols");
    static final DataExternalizer<List<SlangSymbol>> VALUES = new DataExternalizer<>() {
        @Override public void save(@NotNull DataOutput out, List<SlangSymbol> symbols) throws IOException {
            out.writeInt(symbols.size());
            for (var symbol : symbols) {
                IOUtil.writeUTF(out, symbol.name()); IOUtil.writeUTF(out, symbol.container()); IOUtil.writeUTF(out, symbol.signature());
                out.writeByte(symbol.kind().ordinal()); out.writeInt(symbol.offset());
            }
        }
        @Override public List<SlangSymbol> read(@NotNull DataInput in) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Invalid symbol count");
            var result = new ArrayList<SlangSymbol>(Math.min(count, 1024));
            for (int i = 0; i < count; i++) {
                String name = IOUtil.readUTF(in), owner = IOUtil.readUTF(in), signature = IOUtil.readUTF(in);
                int kind = in.readUnsignedByte(), offset = in.readInt();
                if (kind >= SlangSymbol.Kind.values().length || offset < 0) throw new IOException("Invalid symbol entry");
                result.add(new SlangSymbol(name, owner, signature, SlangSymbol.Kind.values()[kind], offset));
            }
            return List.copyOf(result);
        }
    };
    static Map<String, List<SlangSymbol>> index(CharSequence source) {
        Map<String, List<SlangSymbol>> result = new LinkedHashMap<>();
        for (var symbol : SlangSymbolExtractor.extract(source))
            result.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol);
        return result;
    }
    @Override public @NotNull ID<String, List<SlangSymbol>> getName() { return NAME; }
    @Override public @NotNull DataIndexer<String, List<SlangSymbol>, FileContent> getIndexer() {
        return content -> index(content.getContentAsText());
    }
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() { return EnumeratorStringDescriptor.INSTANCE; }
    @Override public @NotNull DataExternalizer<List<SlangSymbol>> getValueExternalizer() { return VALUES; }
    @Override public int getVersion() { return 1; }
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(SlangFileType.INSTANCE);
    }
    @Override public boolean dependsOnFileContent() { return true; }
}
