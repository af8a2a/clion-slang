package dev.slang.intellij.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** File type for native Slang shader source files. */
public final class SlangFileType extends LanguageFileType {
    public static final @NotNull SlangFileType INSTANCE = new SlangFileType();
    public static final @NonNls String DEFAULT_EXTENSION = "slang";
    public static final @NonNls String HEADER_EXTENSION = "slangh";
    public static final @NotNull Icon ICON = IconLoader.getIcon("/icons/slang.svg", SlangFileType.class);

    private SlangFileType() {
        super(SlangLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "Slang";
    }

    @Override
    public @Nls @NotNull String getDescription() {
        return "Slang shader source";
    }

    @Override
    public @NonNls @NotNull String getDefaultExtension() {
        return DEFAULT_EXTENSION;
    }

    @Override
    public @NotNull Icon getIcon() {
        return ICON;
    }
}
