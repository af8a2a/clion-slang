package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import dev.slang.intellij.lang.SlangFileType;
import dev.slang.intellij.preprocessor.SlangBranchColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

/** Settings | Editor | Color Scheme page for lexical and semantic Slang colors. */
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
            descriptor("Include paths", SlangSyntaxHighlighter.INCLUDE_PATH),
            descriptor("Preprocessor branches//Inactive code", SlangBranchColors.INACTIVE),
            descriptor("Preprocessor branches//Active branch", SlangBranchColors.ACTIVE),
            descriptor("Preprocessor branches//Source label", SlangBranchColors.LABEL),
            descriptor("Attributes", SlangSyntaxHighlighter.ATTRIBUTE),
            descriptor("HLSL semantics", SlangSyntaxHighlighter.SEMANTIC),
            descriptor("Semantic//Namespace", SlangSemanticColors.NAMESPACE),
            descriptor("Semantic//Types//General", SlangSemanticColors.TYPE),
            descriptor("Semantic//Types//Built-in", SlangSemanticColors.BUILTIN_TYPE),
            descriptor("Semantic//Types//Class", SlangSemanticColors.CLASS),
            descriptor("Semantic//Types//Struct", SlangSemanticColors.STRUCT),
            descriptor("Semantic//Types//Interface", SlangSemanticColors.INTERFACE),
            descriptor("Semantic//Types//Enum", SlangSemanticColors.ENUM),
            descriptor("Semantic//Types//Type parameter", SlangSemanticColors.TYPE_PARAMETER),
            descriptor("Semantic//Types//Structured buffer", SlangSemanticColors.STRUCTURED_BUFFER),
            descriptor("Semantic//Types//Generic type argument", SlangSemanticColors.TYPE_ARGUMENT),
            descriptor("Semantic//Values//Parameter", SlangSemanticColors.PARAMETER),
            descriptor("Semantic//Values//Variable", SlangSemanticColors.VARIABLE),
            descriptor("Semantic//Values//Static variable", SlangSemanticColors.STATIC_VARIABLE),
            descriptor("Semantic//Values//Read-only variable", SlangSemanticColors.READONLY_VARIABLE),
            descriptor("Semantic//Values//Property or field", SlangSemanticColors.PROPERTY),
            descriptor("Semantic//Values//Static property", SlangSemanticColors.STATIC_PROPERTY),
            descriptor("Semantic//Values//Read-only property", SlangSemanticColors.READONLY_PROPERTY),
            descriptor("Semantic//Values//Enum member", SlangSemanticColors.ENUM_MEMBER),
            descriptor("Semantic//Callables//Function", SlangSemanticColors.FUNCTION),
            descriptor("Semantic//Callables//Method", SlangSemanticColors.METHOD),
            descriptor("Semantic//Callables//Static method", SlangSemanticColors.STATIC_METHOD),
            descriptor("Semantic//Callables//Built-in intrinsic", SlangSemanticColors.INTRINSIC),
            descriptor("Semantic//Macro", SlangSemanticColors.MACRO),
            descriptor("Semantic//Decorator", SlangSemanticColors.DECORATOR),
            descriptor("Semantic//Other built-in symbol", SlangSemanticColors.BUILTIN_SYMBOL),
            descriptor("Semantic//Unknown identifier", SlangSemanticColors.IDENTIFIER),
            descriptor("Punctuation//Operators", SlangSyntaxHighlighter.OPERATOR),
            descriptor("Punctuation//Braces", SlangSyntaxHighlighter.BRACES),
            descriptor("Punctuation//Brackets", SlangSyntaxHighlighter.BRACKETS),
            descriptor("Punctuation//Parentheses", SlangSyntaxHighlighter.PARENTHESES),
            descriptor("Punctuation//Semicolon", SlangSyntaxHighlighter.SEMICOLON),
            descriptor("Punctuation//Comma", SlangSyntaxHighlighter.COMMA),
            descriptor("Punctuation//Dot", SlangSyntaxHighlighter.DOT),
            descriptor("Invalid character", SlangSyntaxHighlighter.BAD_CHARACTER)
    };

    private static final Map<String, TextAttributesKey> SEMANTIC_TAGS = Map.ofEntries(
            Map.entry("semanticNamespace", SlangSemanticColors.NAMESPACE),
            Map.entry("semanticType", SlangSemanticColors.TYPE),
            Map.entry("semanticBuiltinType", SlangSemanticColors.BUILTIN_TYPE),
            Map.entry("semanticClass", SlangSemanticColors.CLASS),
            Map.entry("semanticStruct", SlangSemanticColors.STRUCT),
            Map.entry("semanticInterface", SlangSemanticColors.INTERFACE),
            Map.entry("semanticEnum", SlangSemanticColors.ENUM),
            Map.entry("semanticTypeParameter", SlangSemanticColors.TYPE_PARAMETER),
            Map.entry("semanticStructuredBuffer", SlangSemanticColors.STRUCTURED_BUFFER),
            Map.entry("semanticTypeArgument", SlangSemanticColors.TYPE_ARGUMENT),
            Map.entry("semanticParameter", SlangSemanticColors.PARAMETER),
            Map.entry("semanticVariable", SlangSemanticColors.VARIABLE),
            Map.entry("semanticStaticVariable", SlangSemanticColors.STATIC_VARIABLE),
            Map.entry("semanticReadonlyVariable", SlangSemanticColors.READONLY_VARIABLE),
            Map.entry("semanticProperty", SlangSemanticColors.PROPERTY),
            Map.entry("semanticStaticProperty", SlangSemanticColors.STATIC_PROPERTY),
            Map.entry("semanticReadonlyProperty", SlangSemanticColors.READONLY_PROPERTY),
            Map.entry("semanticEnumMember", SlangSemanticColors.ENUM_MEMBER),
            Map.entry("semanticFunction", SlangSemanticColors.FUNCTION),
            Map.entry("semanticMethod", SlangSemanticColors.METHOD),
            Map.entry("semanticStaticMethod", SlangSemanticColors.STATIC_METHOD),
            Map.entry("semanticIntrinsic", SlangSemanticColors.INTRINSIC),
            Map.entry("semanticMacro", SlangSemanticColors.MACRO),
            Map.entry("semanticDecorator", SlangSemanticColors.DECORATOR),
            Map.entry("semanticBuiltinSymbol", SlangSemanticColors.BUILTIN_SYMBOL),
            Map.entry("semanticIdentifier", SlangSemanticColors.IDENTIFIER),
            Map.entry("inactiveBranch", SlangBranchColors.INACTIVE),
            Map.entry("activeBranch", SlangBranchColors.ACTIVE),
            Map.entry("branchLabel", SlangBranchColors.LABEL)
    );

    private static final String DEMO_TEXT = """
            // Slang Rider Light (Rider_Light.icls): blue directives, brown paths, green comments.
            #include "ClusterLightGridCommon.slang"
            #include <lighting/Common.slangh>
            #if BLUE
            <inactiveBranch>float3 tint = float3(0, 0, 1);</inactiveBranch>
            #<activeBranch>elif</activeBranch> GREEN <branchLabel>#if BLUE</branchLabel>
            float3 tint = float3(0, 1, 0);
            #endif <branchLabel>#elif GREEN</branchLabel>

            #define <semanticMacro>THREAD_COUNT</semanticMacro> 8

            namespace <semanticNamespace>rendering</semanticNamespace>
            {
                enum <semanticEnum>SurfaceMode</semanticEnum>
                {
                    <semanticEnumMember>matte</semanticEnumMember>,
                    glossy,
                };

                interface <semanticInterface>IMaterial</semanticInterface>
                {
                    <semanticBuiltinType>float3</semanticBuiltinType> <semanticMethod>evaluate</semanticMethod>(
                        <semanticBuiltinType>float3</semanticBuiltinType> <semanticParameter>normal</semanticParameter>);
                }

                struct <semanticStruct>Lambert</semanticStruct> : IMaterial
                {
                    float3 <semanticProperty>albedo</semanticProperty>;
                    static float <semanticStaticProperty>defaultScale</semanticStaticProperty>;
                    const float <semanticReadonlyProperty>roughness</semanticReadonlyProperty>;

                    float3 evaluate(float3 normal)
                    {
                        float <semanticVariable>weight</semanticVariable> =
                            <semanticIntrinsic>max</semanticIntrinsic>(normal.z, 0.0);
                        return albedo * weight;
                    }
                };

                class <semanticClass>MaterialLibrary</semanticClass> {}

                struct Box<<semanticTypeParameter>TValue</semanticTypeParameter>>
                {
                    TValue value;
                }

                static uint <semanticStaticVariable>materialCount</semanticStaticVariable>;
                static const uint <semanticReadonlyVariable>MAX_LIGHTS</semanticReadonlyVariable> = 8;
            }

            // Structs / fields are deep purple; types violet; functions teal; numbers magenta.
            struct <semanticStruct>HitEntry</semanticStruct>
            {
                uint <semanticProperty>instanceID</semanticProperty>;
                uint <semanticProperty>primitiveIndex</semanticProperty>;
                float2 <semanticProperty>barycentrics</semanticProperty>;
                bool <semanticMethod>isValid</semanticMethod>()
                {
                    return <semanticProperty>instanceID</semanticProperty> != -1;
                }
            };
            <semanticStructuredBuffer>StructuredBuffer</semanticStructuredBuffer><<semanticTypeArgument>HitEntry</semanticTypeArgument>> <semanticStaticVariable>g_GBuffer</semanticStaticVariable>;
            <semanticStructuredBuffer>RWStructuredBuffer</semanticStructuredBuffer><<semanticTypeArgument>uint</semanticTypeArgument>> <semanticStaticVariable>g_CompactedGBuffer</semanticStaticVariable>;
            <semanticStructuredBuffer>RWStructuredBuffer</semanticStructuredBuffer><<semanticTypeArgument>uint</semanticTypeArgument>> <semanticStaticVariable>g_CompactedGBufferLength</semanticStaticVariable>;

            /* Linear RGB luminance, matching the reference's function / field contrast. */
            float <semanticFunction>luminance</semanticFunction>(float3 <semanticParameter>color</semanticParameter>)
            {
                const float3 <semanticReadonlyVariable>weights</semanticReadonlyVariable> = float3(0.2126, 0.7152, 0.0722);
                return <semanticIntrinsic>dot</semanticIntrinsic>(<semanticParameter>color</semanticParameter>, <semanticReadonlyVariable>weights</semanticReadonlyVariable>);
            }

            /// A compact compute entry point.
            [<semanticDecorator>shader</semanticDecorator>("compute")]
            [numthreads(THREAD_COUNT, 1, 1)]
            void <semanticFunction>main</semanticFunction>(uint3 dispatchThreadID : SV_DispatchThreadID)
            {
                rendering.<semanticType>Lambert</semanticType> material;
                material.albedo = float3(1.0, 0.5, 0.25);
                float3 color = material.evaluate(float3(dispatchThreadID));
            }
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
    public @NotNull Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return SEMANTIC_TAGS;
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
