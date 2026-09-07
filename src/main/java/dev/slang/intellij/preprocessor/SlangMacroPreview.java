package dev.slang.intellij.preprocessor;

import com.google.gson.Gson;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Editor input only, never a configuration file or a command line. */
public record SlangMacroPreview(String definitions, String undefinitions, SlangPreprocessorTrace.Preview wire) {
    public static SlangMacroPreview parse(String definitions, String undefinitions) {
        if (definitions.length() + undefinitions.length() > 1_200_000)
            throw new IllegalArgumentException("Preview input is too large");
        var defines = new TreeMap<String, String>();
        var undefines = new TreeSet<String>();
        for (String line : definitions.split("\r?\n", -1)) {
            if (line.isBlank()) continue;
            int equal = line.indexOf('=');
            String name = (equal < 0 ? line : line.substring(0, equal)).trim();
            String value = equal < 0 ? "" : line.substring(equal + 1);
            validateName(name);
            if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || value.getBytes(StandardCharsets.UTF_8).length > 4096)
                throw new IllegalArgumentException("Invalid value for " + name + " (single line, at most 4096 UTF-8 bytes)");
            if (defines.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate definition: " + name);
        }
        for (String line : undefinitions.split("\r?\n", -1)) {
            if (line.isBlank()) continue;
            String name = line.trim();
            validateName(name);
            if (!undefines.add(name) || defines.containsKey(name))
                throw new IllegalArgumentException("Duplicate or conflicting macro: " + name);
        }
        if (defines.size() + undefines.size() > 256)
            throw new IllegalArgumentException("At most 256 macro overrides are allowed");
        if (defines.isEmpty() && undefines.isEmpty())
            throw new IllegalArgumentException("Enter a macro override, or use Stop Slang Branch Preview");
        try {
            String canonical = new Gson().toJson(List.of(defines, undefines));
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new SlangMacroPreview(definitions, undefinitions, new SlangPreprocessorTrace.Preview(1, fingerprint,
                    defines.entrySet().stream().map(e -> new SlangPreprocessorTrace.Macro(e.getKey(), e.getValue())).toList(),
                    List.copyOf(undefines)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void validateName(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("Expected an object-like macro name (1–128 ASCII characters): "
                    + name.substring(0, Math.min(name.length(), 128)));
    }

    public String summary() {
        return "+" + wire.defines().size() + " / −" + wire.undefines().size() + " macros";
    }
}
