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
            descriptor("Attributes", SlangSyntaxHighlighter.ATTRIBUTE),
            descriptor("HLSL semantics", SlangSyntaxHighlighter.SEMANTIC),
            descriptor("Semantic//Namespace", SlangSemanticColors.NAMESPACE),
            descriptor("Semantic//Types//General or stock fallback", SlangSemanticColors.TYPE),
            descriptor("Semantic//Types//Built-in or resource type", SlangSemanticColors.BUILTIN_TYPE),
            descriptor("Semantic//Types//Class", SlangSemanticColors.CLASS),
            descriptor("Semantic//Types//Struct", SlangSemanticColors.STRUCT),
            descriptor("Semantic//Types//Interface", SlangSemanticColors.INTERFACE),
            descriptor("Semantic//Types//Enum", SlangSemanticColors.ENUM),
            descriptor("Semantic//Types//Type parameter", SlangSemanticColors.TYPE_PARAMETER),
            descriptor("Semantic//Values//Parameter", SlangSemanticColors.PARAMETER),
            descriptor("Semantic//Values//Variable or global resource", SlangSemanticColors.VARIABLE),
            descriptor("Semantic//Values//Static variable", SlangSemanticColors.STATIC_VARIABLE),
            descriptor("Semantic//Values//Read-only variable", SlangSemanticColors.READONLY_VARIABLE),
            descriptor("Semantic//Values//Property or parameter-block field", SlangSemanticColors.PROPERTY),
            descriptor("Semantic//Values//Static property", SlangSemanticColors.STATIC_PROPERTY),
            descriptor("Semantic//Values//Read-only property", SlangSemanticColors.READONLY_PROPERTY),
            descriptor("Semantic//Values//Enum member", SlangSemanticColors.ENUM_MEMBER),
            descriptor("Semantic//Callables//Function or shader entry point", SlangSemanticColors.FUNCTION),
            descriptor("Semantic//Callables//Method", SlangSemanticColors.METHOD),
            descriptor("Semantic//Callables//Static method", SlangSemanticColors.STATIC_METHOD),
            descriptor("Semantic//Callables//Built-in intrinsic", SlangSemanticColors.INTRINSIC),
            descriptor("Semantic//Shader//Binding semantic", SlangSemanticColors.SHADER_SEMANTIC),
            descriptor("Semantic//Shader//Swizzle", SlangSemanticColors.SWIZZLE),
            descriptor("Semantic//Macro", SlangSemanticColors.MACRO),
            descriptor("Semantic//Shader//Attribute", SlangSemanticColors.DECORATOR),
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
            Map.entry("semanticShaderSemantic", SlangSemanticColors.SHADER_SEMANTIC),
            Map.entry("semanticSwizzle", SlangSemanticColors.SWIZZLE),
            Map.entry("semanticMacro", SlangSemanticColors.MACRO),
            Map.entry("semanticDecorator", SlangSemanticColors.DECORATOR),
            Map.entry("semanticBuiltinSymbol", SlangSemanticColors.BUILTIN_SYMBOL),
            Map.entry("semanticIdentifier", SlangSemanticColors.IDENTIFIER)
    );

    /**
     * Theme-neutral calibration corpus derived from the high-frequency constructs in Metallic's
     * first-party shaders. The main block emphasizes resources, constants, attributes, semantics,
     * and swizzles; the compact tail keeps less common Slang roles adjustable as well.
     */
    private static final String DEMO_TEXT = """
            #define <semanticMacro>METALLIC_TILE_SIZE</semanticMacro> 8

            struct <semanticStruct>CompositePush</semanticStruct>
            {
                uint2 <semanticProperty>extent</semanticProperty>;
                uint <semanticProperty>outputTexture</semanticProperty>;
                const float <semanticReadonlyProperty>exposure</semanticReadonlyProperty>;
                static float <semanticStaticProperty>defaultExposure</semanticStaticProperty>;
            };

            [[vk::<semanticDecorator>binding</semanticDecorator>(0, 0)]]
            <semanticBuiltinType>RaytracingAccelerationStructure</semanticBuiltinType>
                <semanticVariable>gScene</semanticVariable>;
            [[vk::<semanticDecorator>binding</semanticDecorator>(1, 0)]]
            <semanticBuiltinType>Texture2D</semanticBuiltinType><float4>
                <semanticVariable>gHistory</semanticVariable>;
            [[vk::<semanticDecorator>binding</semanticDecorator>(2, 0)]]
            <semanticBuiltinType>RWTexture2D</semanticBuiltinType><float4>
                <semanticVariable>gOutput</semanticVariable>;

            static const uint <semanticReadonlyVariable>kInvalidMaterial</semanticReadonlyVariable> = 0xffffffffu;
            static uint <semanticStaticVariable>gFrameIndex</semanticStaticVariable>;

            float <semanticFunction>luminance</semanticFunction>(
                float3 <semanticParameter>color</semanticParameter>)
            {
                return <semanticIntrinsic>dot</semanticIntrinsic>(
                    color.<semanticSwizzle>rgb</semanticSwizzle>, float3(0.2126, 0.7152, 0.0722));
            }

            /// Metallic-style compute pass used to calibrate the common semantic colors.
            [<semanticDecorator>shader</semanticDecorator>("compute")]
            [<semanticDecorator>numthreads</semanticDecorator>(METALLIC_TILE_SIZE, METALLIC_TILE_SIZE, 1)]
            void <semanticFunction>compositeMain</semanticFunction>(
                uint3 <semanticParameter>dispatchThreadID</semanticParameter>
                    : <semanticShaderSemantic>SV_DispatchThreadID</semanticShaderSemantic>,
                uniform <semanticType>CompositePush</semanticType> <semanticParameter>push</semanticParameter>)
            {
                uint2 <semanticVariable>pixel</semanticVariable> =
                    dispatchThreadID.<semanticSwizzle>xy</semanticSwizzle>;
                <semanticBuiltinType>DescriptorHandle</semanticBuiltinType><
                    <semanticBuiltinType>RWTexture2D</semanticBuiltinType><float4>>
                    <semanticVariable>outputHandle</semanticVariable> =
                        DescriptorHandle<RWTexture2D<float4>>(uint2(push.outputTexture, 0));
                <semanticBuiltinType>RWTexture2D</semanticBuiltinType><float4>
                    <semanticVariable>output</semanticVariable> = outputHandle;
                float3 <semanticVariable>history</semanticVariable> =
                    gHistory.<semanticIntrinsic>Load</semanticIntrinsic>(int3(pixel, 0)).<semanticSwizzle>rgb</semanticSwizzle>;
                float <semanticVariable>weight</semanticVariable> =
                    <semanticIntrinsic>saturate</semanticIntrinsic>(push.exposure);
                uint <semanticVariable>rayFlags</semanticVariable> =
                    <semanticBuiltinSymbol>RAY_FLAG_NONE</semanticBuiltinSymbol>;
                output[pixel] = float4(
                    <semanticIntrinsic>lerp</semanticIntrinsic>(history, history * weight, 0.5), 1.0);
            }

            // Less common in Metallic, but retained so every supported Slang role is adjustable.
            namespace <semanticNamespace>calibration</semanticNamespace>
            {
                enum <semanticEnum>CacheMode</semanticEnum>
                {
                    <semanticEnumMember>disabled</semanticEnumMember>,
                    enabled,
                };

                interface <semanticInterface>IMaterial</semanticInterface>
                {
                    float3 <semanticMethod>evaluate</semanticMethod>(float3 normal);
                }

                class <semanticClass>MaterialLibrary</semanticClass>
                {
                    static float3 <semanticStaticMethod>fallbackColor</semanticStaticMethod>();
                }

                struct GenericBuffer<<semanticTypeParameter>TValue</semanticTypeParameter>>
                {
                    TValue value;
                };

                float <semanticIdentifier>futureSemanticRole</semanticIdentifier>;
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
