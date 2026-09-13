package dev.slang.intellij.settings;

import java.util.Locale;

/** Stable persisted values; labels are only for presentation. */
public enum SlangServerSource {
    BUNDLED("Bundled enhanced slangd (Windows x64)"),
    EXTERNAL("External / official slangd");

    private final String label;

    SlangServerSource(String label) { this.label = label; }

    public static boolean isBundledSupported() {
        return isBundledSupported(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static boolean isBundledSupported(String os, String arch) {
        String normalizedArch = arch.toLowerCase(Locale.ROOT);
        return os.toLowerCase(Locale.ROOT).startsWith("windows")
                && (normalizedArch.equals("amd64") || normalizedArch.equals("x86_64"));
    }

    @Override
    public String toString() { return label; }
}
