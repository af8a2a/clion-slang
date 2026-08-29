package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import dev.slang.intellij.lang.SlangFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

/** Settings | Editor | Color Scheme page for lexical Slang colors. */
public final class SlangColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
            descriptor("Keywords", SlangSyntaxHighlighter.KEYWORD),
            descriptor("Built-in types", SlangSyntaxHighlighter.TYPE),
            descriptor("Literals//Numbers", SlangSyntaxHighlighter.NUMBER),
            descriptor("Literals//Strings and characters", SlangSyntaxHighlighter.STRING),
            descriptor("Literals//Boolean values", SlangSyntaxHighlighter.BOOLEAN),
            descriptor("Comments//Line comment", SlangSyntaxHighlighter.LINE_COMMENT),
            descriptor("Comments//Block comment", SlangSyntaxHighlighter.BLOCK_COMMENT),
            descriptor("Comments//Documentation comment", SlangSyntaxHighlighter.DOC_COMMENT),
            descriptor("Preprocessor directives", SlangSyntaxHighlighter.PREPROCESSOR),
            descriptor("Attributes", SlangSyntaxHighlighter.ATTRIBUTE),
            descriptor("HLSL semantics", SlangSyntaxHighlighter.SEMANTIC),
            descriptor("Punctuation//Operators", SlangSyntaxHighlighter.OPERATOR),
            descriptor("Punctuation//Braces", SlangSyntaxHighlighter.BRACES),
            descriptor("Punctuation//Brackets", SlangSyntaxHighlighter.BRACKETS),
            descriptor("Punctuation//Parentheses", SlangSyntaxHighlighter.PARENTHESES),
            descriptor("Punctuation//Semicolon", SlangSyntaxHighlighter.SEMICOLON),
            descriptor("Punctuation//Comma", SlangSyntaxHighlighter.COMMA),
            descriptor("Punctuation//Dot", SlangSyntaxHighlighter.DOT),
            descriptor("Invalid character", SlangSyntaxHighlighter.BAD_CHARACTER)
    };

    private static final String DEMO_TEXT = """
            #define THREAD_COUNT 8

            import gfx;

            /// A compact compute entry point.
            [shader(\"compute\")]
            [numthreads(THREAD_COUNT, 1, 1)]
            void main(uint3 dispatchThreadID : SV_DispatchThreadID)
            {
                // Slang and HLSL literals/operators.
                const float exposure = 1.25f;
                bool enabled = true;
                RWTexture2D<float4> outputTexture;
                outputTexture[dispatchThreadID.xy] = float4(exposure, 0.5, 1.0, 1.0);
            }

            /* Slang supports C-style block comments. */
            struct VertexOutput
            {
                float4 position : SV_Position;
                float2 uv : TEXCOORD0;
            };
            """;

    @Override
    public @Nullable Icon getIcon() {
        return SlangFileType.ICON;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new SlangSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return DEMO_TEXT;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return null;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Slang";
    }

    private static AttributesDescriptor descriptor(String name, TextAttributesKey key) {
        return new AttributesDescriptor(name, key);
    }
}
