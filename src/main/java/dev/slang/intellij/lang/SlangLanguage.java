package dev.slang.intellij.lang;

import com.intellij.lang.Language;
import org.jetbrains.annotations.NotNull;

/** The standalone Slang language used by .slang and .slangh files. */
public final class SlangLanguage extends Language {
    public static final @NotNull SlangLanguage INSTANCE = new SlangLanguage();

    private SlangLanguage() {
        super("Slang");
    }
}
