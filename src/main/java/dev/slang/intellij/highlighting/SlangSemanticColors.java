package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

/** User-configurable colors for LSP semantic-token roles. */
public final class SlangSemanticColors {
    public static final TextAttributesKey IDENTIFIER = key(
            "SLANG.SEMANTIC.IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey NAMESPACE = key(
            "SLANG.SEMANTIC.NAMESPACE", DefaultLanguageHighlighterColors.CLASS_REFERENCE);

    public static final TextAttributesKey TYPE = key(
            "SLANG.SEMANTIC.TYPE", DefaultLanguageHighlighterColors.CLASS_REFERENCE);
    public static final TextAttributesKey BUILTIN_TYPE = key(
            "SLANG.SEMANTIC.BUILTIN_TYPE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);
    public static final TextAttributesKey CLASS = key(
            "SLANG.SEMANTIC.CLASS", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey STRUCT = key(
            "SLANG.SEMANTIC.STRUCT", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey INTERFACE = key(
            "SLANG.SEMANTIC.INTERFACE", DefaultLanguageHighlighterColors.INTERFACE_NAME);
    public static final TextAttributesKey ENUM = key(
            "SLANG.SEMANTIC.ENUM", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey TYPE_PARAMETER = key(
            "SLANG.SEMANTIC.TYPE_PARAMETER", DefaultLanguageHighlighterColors.CLASS_REFERENCE);
    public static final TextAttributesKey STRUCTURED_BUFFER = key(
            "SLANG.SEMANTIC.STRUCTURED_BUFFER", STRUCT);
    public static final TextAttributesKey TYPE_ARGUMENT = key(
            "SLANG.SEMANTIC.TYPE_ARGUMENT", TYPE_PARAMETER);

    public static final TextAttributesKey PARAMETER = key(
            "SLANG.SEMANTIC.PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey VARIABLE = key(
            "SLANG.SEMANTIC.VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey STATIC_VARIABLE = key(
            "SLANG.SEMANTIC.STATIC_VARIABLE", DefaultLanguageHighlighterColors.GLOBAL_VARIABLE);
    public static final TextAttributesKey READONLY_VARIABLE = key(
            "SLANG.SEMANTIC.READONLY_VARIABLE", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey PROPERTY = key(
            "SLANG.SEMANTIC.PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey STATIC_PROPERTY = key(
            "SLANG.SEMANTIC.STATIC_PROPERTY", DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey READONLY_PROPERTY = key(
            "SLANG.SEMANTIC.READONLY_PROPERTY", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey ENUM_MEMBER = key(
            "SLANG.SEMANTIC.ENUM_MEMBER", DefaultLanguageHighlighterColors.CONSTANT);

    public static final TextAttributesKey FUNCTION = key(
            "SLANG.SEMANTIC.FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey METHOD = key(
            "SLANG.SEMANTIC.METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD);
    public static final TextAttributesKey STATIC_METHOD = key(
            "SLANG.SEMANTIC.STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey INTRINSIC = key(
            "SLANG.SEMANTIC.INTRINSIC", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);

    public static final TextAttributesKey MACRO = key(
            "SLANG.SEMANTIC.MACRO", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);
    public static final TextAttributesKey DECORATOR = key(
            "SLANG.SEMANTIC.DECORATOR", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey BUILTIN_SYMBOL = key(
            "SLANG.SEMANTIC.BUILTIN_SYMBOL", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);

    private SlangSemanticColors() {
    }

    private static TextAttributesKey key(String name, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(name, fallback);
    }
}
