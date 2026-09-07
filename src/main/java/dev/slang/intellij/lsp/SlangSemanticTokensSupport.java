package dev.slang.intellij.lsp;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.psi.PsiFile;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;
import dev.slang.intellij.lang.SlangLanguage;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Enables semantic-token requests for Slang PSI and maps LSP roles to Slang colors. */
public final class SlangSemanticTokensSupport extends LspSemanticTokensSupport {
    public static final SlangSemanticTokensSupport INSTANCE = new SlangSemanticTokensSupport();

    private static final List<String> TOKEN_TYPES = List.of(
            "namespace",
            "type",
            "class",
            "enum",
            "interface",
            "struct",
            "typeParameter",
            "parameter",
            "variable",
            "property",
            "enumMember",
            "event",
            "function",
            "method",
            "macro",
            "keyword",
            "modifier",
            "comment",
            "string",
            "number",
            "regexp",
            "operator",
            "decorator",
            "slangStructuredBuffer",
            "slangTypeArgument"
    );

    private static final List<String> TOKEN_MODIFIERS = List.of(
            "declaration",
            "definition",
            "readonly",
            "static",
            "deprecated",
            "abstract",
            "async",
            "modification",
            "documentation",
            "defaultLibrary"
    );

    private SlangSemanticTokensSupport() {
    }

    @Override
    public boolean shouldAskServerForSemanticTokens(@NotNull PsiFile psiFile) {
        return psiFile.getLanguage() == SlangLanguage.INSTANCE;
    }

    @Override
    public @NotNull List<String> getTokenTypes() {
        return TOKEN_TYPES;
    }

    @Override
    public @NotNull List<String> getTokenModifiers() {
        return TOKEN_MODIFIERS;
    }

    @Override
    public @NotNull TextAttributesKey getTextAttributesKey(
            @NotNull String tokenType,
            @NotNull List<String> modifiers
    ) {
        // Specific shader roles must survive defaultLibrary on future/enhanced publishers.
        if (tokenType.equals("slangStructuredBuffer")) return SlangSemanticColors.STRUCTURED_BUFFER;
        if (tokenType.equals("slangTypeArgument")) return SlangSemanticColors.TYPE_ARGUMENT;
        if (modifiers.contains("defaultLibrary")) {
            if (isTypeToken(tokenType)) {
                return SlangSemanticColors.BUILTIN_TYPE;
            }
            if (tokenType.equals("function") || tokenType.equals("method")) {
                return SlangSemanticColors.INTRINSIC;
            }
            return SlangSemanticColors.BUILTIN_SYMBOL;
        }

        return switch (tokenType) {
            case "namespace" -> SlangSemanticColors.NAMESPACE;
            case "type" -> SlangSemanticColors.TYPE;
            case "class" -> SlangSemanticColors.CLASS;
            case "enum" -> SlangSemanticColors.ENUM;
            case "interface" -> SlangSemanticColors.INTERFACE;
            case "struct" -> SlangSemanticColors.STRUCT;
            case "typeParameter" -> SlangSemanticColors.TYPE_PARAMETER;
            case "parameter" -> SlangSemanticColors.PARAMETER;
            case "variable" -> variableColor(modifiers);
            case "property", "event" -> propertyColor(modifiers);
            case "enumMember" -> SlangSemanticColors.ENUM_MEMBER;
            case "function" -> SlangSemanticColors.FUNCTION;
            case "method" -> modifiers.contains("static")
                    ? SlangSemanticColors.STATIC_METHOD
                    : SlangSemanticColors.METHOD;
            case "macro" -> SlangSemanticColors.MACRO;
            case "keyword", "modifier" -> SlangSyntaxHighlighter.KEYWORD;
            case "comment" -> modifiers.contains("documentation")
                    ? SlangSyntaxHighlighter.DOC_COMMENT
                    : SlangSyntaxHighlighter.LINE_COMMENT;
            case "string", "regexp" -> SlangSyntaxHighlighter.STRING;
            case "number" -> SlangSyntaxHighlighter.NUMBER;
            case "operator" -> SlangSyntaxHighlighter.OPERATOR;
            case "decorator" -> SlangSemanticColors.DECORATOR;
            default -> SlangSemanticColors.IDENTIFIER;
        };
    }

    private static boolean isTypeToken(String tokenType) {
        return switch (tokenType) {
            case "type", "class", "enum", "interface", "struct", "typeParameter" -> true;
            default -> false;
        };
    }

    private static TextAttributesKey variableColor(List<String> modifiers) {
        if (modifiers.contains("readonly")) {
            return SlangSemanticColors.READONLY_VARIABLE;
        }
        if (modifiers.contains("static")) {
            return SlangSemanticColors.STATIC_VARIABLE;
        }
        return SlangSemanticColors.VARIABLE;
    }

    private static TextAttributesKey propertyColor(List<String> modifiers) {
        if (modifiers.contains("readonly")) {
            return SlangSemanticColors.READONLY_PROPERTY;
        }
        if (modifiers.contains("static")) {
            return SlangSemanticColors.STATIC_PROPERTY;
        }
        return SlangSemanticColors.PROPERTY;
    }
}
