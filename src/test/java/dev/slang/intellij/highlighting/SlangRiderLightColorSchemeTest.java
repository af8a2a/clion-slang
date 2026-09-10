package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.util.JDOMUtil;
import dev.slang.intellij.preprocessor.SlangBranchColors;
import org.jdom.Element;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Tests the actual platform XML reader without an IDE application or language server. */
public class SlangRiderLightColorSchemeTest {
    private static final String NAME = "Slang Rider Light";
    private static final String PATH = "colorSchemes/SlangRiderLight";

    @Test
    public void registersOneSelectablePresetAndOnlySupportedLightDefaults() throws Exception {
        Element extensions = resource("META-INF/plugin.xml").getChildren("extensions").stream()
                .filter(element -> "com.intellij".equals(element.getAttributeValue("defaultExtensionNs")))
                .findFirst().orElseThrow();
        var bundles = extensions.getChildren("bundledColorScheme");
        assertEquals(1, bundles.size());
        assertEquals(NAME, bundles.getFirst().getAttributeValue("id"));
        assertEquals(PATH, bundles.getFirst().getAttributeValue("path"));

        Set<String> schemes = new HashSet<>();
        for (Element defaults : extensions.getChildren("additionalTextAttributes")) {
            assertEquals(PATH + ".xml", defaults.getAttributeValue("file"));
            assertTrue(schemes.add(defaults.getAttributeValue("scheme")));
        }
        // Default is a shared ancestor of third-party light AND dark schemes. Do not inject into it.
        assertEquals(Set.of("Light", "IntelliJ Light"), schemes);
        Element preset = resource(PATH + ".xml");
        assertEquals("scheme", preset.getName());
        assertEquals(NAME, preset.getAttributeValue("name"));
        assertEquals("142", preset.getAttributeValue("version"));
        assertEquals("Light", preset.getAttributeValue("parent_scheme"));
    }

    @Test
    public void standalonePresetPreservesExportedClionColorsAndChangesOnlySlang() throws Exception {
        Element reference = resource("colorSchemes/ClionLightReference.xml");
        var legacyDefault = new EditorColorsSchemeImpl(null);
        legacyDefault.setAttributes(DefaultLanguageHighlighterColors.KEYWORD, color(0x000080));
        var clionLight = new EditorColorsSchemeImpl(legacyDefault);
        clionLight.readAttributes(reference.getChild("attributes"));
        clionLight.readColors(reference.getChild("colors"));
        Element palette = resource(PATH + ".xml");
        // Resolve the declared parent, not a hard-coded test parent: catches a regression to Default.
        var parent = Map.of("Default", legacyDefault, "Light", clionLight)
                .get(palette.getAttributeValue("parent_scheme"));
        assertNotNull(parent);
        var standalone = new EditorColorsSchemeImpl(parent);
        standalone.readAttributes(palette.getChild("attributes"));

        for (Element entry : reference.getChild("attributes").getChildren("option")) {
            var key = TextAttributesKey.createTextAttributesKey(entry.getAttributeValue("name"));
            assertEquals(key.toString(), clionLight.getAttributes(key), standalone.getAttributes(key));
        }
        for (Element entry : reference.getChild("colors").getChildren("option")) {
            var key = ColorKey.createColorKey(entry.getAttributeValue("name"));
            assertEquals(key.toString(), clionLight.getColor(key), standalone.getColor(key));
        }
        foreground(standalone, 0x0033B3, DefaultLanguageHighlighterColors.KEYWORD);
        foreground(standalone, 0x067D17, DefaultLanguageHighlighterColors.STRING);
        foreground(standalone, 0x8C8C8C, DefaultLanguageHighlighterColors.LINE_COMMENT);
        foreground(standalone, 0x0F54D6, SlangSyntaxHighlighter.KEYWORD);
        foreground(standalone, 0x8C6C41, SlangSyntaxHighlighter.STRING);
        foreground(standalone, 0x248700, SlangSyntaxHighlighter.LINE_COMMENT);
        assertTrue(standalone.getDirectlyDefinedColors().isEmpty());
        assertTrue(standalone.getDirectlyDefinedAttributes().keySet().stream().allMatch(key -> key.startsWith("SLANG.")));

        // Parent changes remain inherited instead of being frozen into copied C++ attributes.
        clionLight.setAttributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD, color(0x123456));
        foreground(standalone, 0x123456, DefaultLanguageHighlighterColors.INSTANCE_FIELD);
        foreground(standalone, 0x300073, SlangSemanticColors.PROPERTY);
        var customized = new EditorColorsSchemeImpl(standalone);
        customized.setAttributes(DefaultLanguageHighlighterColors.NUMBER, color(0x456789));
        standalone.readAttributes(palette.getChild("attributes"));
        foreground(customized, 0x456789, DefaultLanguageHighlighterColors.NUMBER);
        foreground(customized, 0xAB2F6B, SlangSyntaxHighlighter.NUMBER);
    }

    @Test
    public void coversEverySlangSettingWithoutChangingGlobalColorsOrFonts() throws Exception {
        Element preset = resource(PATH + ".xml");
        assertEquals(1, preset.getChildren().size());
        assertEquals("attributes", preset.getChildren().getFirst().getName());
        Set<String> keys = new HashSet<>();
        for (Element option : preset.getChild("attributes").getChildren()) {
            assertEquals("option", option.getName());
            String name = option.getAttributeValue("name");
            assertTrue(name, name.startsWith("SLANG."));
            assertTrue("Duplicate palette key: " + name, keys.add(name));
            if (option.getAttributeValue("baseAttributes") != null) {
                assertEquals(SlangSyntaxHighlighter.BAD_CHARACTER.getExternalName(), name);
                assertEquals(HighlighterColors.BAD_CHARACTER.getExternalName(), option.getAttributeValue("baseAttributes"));
                assertNull(option.getChild("value"));
            } else {
                assertNotNull(name, option.getChild("value"));
                Set<String> properties = new HashSet<>();
                for (Element property : option.getChild("value").getChildren()) {
                    String key = property.getAttributeValue("name");
                    assertTrue(properties.add(key));
                    assertTrue(Set.of("FOREGROUND", "BACKGROUND", "EFFECT_COLOR", "EFFECT_TYPE", "FONT_TYPE").contains(key));
                    assertTrue(property.getAttributeValue("value").matches(
                            Set.of("EFFECT_TYPE", "FONT_TYPE").contains(key) ? "[0-9]+" : "[0-9A-F]{6}"));
                }
            }
        }
        Set<String> settings = Arrays.stream(new SlangColorSettingsPage().getAttributeDescriptors())
                .map(AttributesDescriptor::getKey).map(TextAttributesKey::getExternalName).collect(Collectors.toSet());
        assertEquals(settings, keys);
    }

    @Test
    public void platformReaderLoadsTheExportedPaletteAndItalicComments() throws Exception {
        var scheme = preset();
        foreground(scheme, 0x0F54D6, SlangSyntaxHighlighter.KEYWORD, SlangSyntaxHighlighter.PREPROCESSOR,
                SlangSyntaxHighlighter.BOOLEAN, SlangSemanticColors.MACRO);
        foreground(scheme, 0x6B2FBA, SlangSyntaxHighlighter.TYPE, SlangSemanticColors.BUILTIN_TYPE,
                SlangSemanticColors.TYPE_PARAMETER, SlangSemanticColors.TYPE_ARGUMENT,
                SlangSemanticColors.TYPE, SlangSemanticColors.CLASS, SlangSemanticColors.INTERFACE,
                SlangSyntaxHighlighter.ATTRIBUTE, SlangSemanticColors.DECORATOR);
        foreground(scheme, 0x300073, SlangSemanticColors.STRUCT, SlangSemanticColors.ENUM,
                SlangSemanticColors.STRUCTURED_BUFFER, SlangSemanticColors.PROPERTY);
        foreground(scheme, 0x00855F, SlangSemanticColors.FUNCTION, SlangSemanticColors.METHOD,
                SlangSemanticColors.STATIC_METHOD, SlangSemanticColors.INTRINSIC);
        foreground(scheme, 0xAB2F6B, SlangSyntaxHighlighter.NUMBER);
        foreground(scheme, 0x8C6C41, SlangSyntaxHighlighter.INCLUDE_PATH, SlangSyntaxHighlighter.STRING);
        foreground(scheme, 0x383838, SlangSemanticColors.VARIABLE, SlangSemanticColors.IDENTIFIER,
                SlangSemanticColors.STATIC_VARIABLE, SlangSemanticColors.PARAMETER, SlangSyntaxHighlighter.OPERATOR);
        foreground(scheme, 0x0093A1, SlangSemanticColors.READONLY_VARIABLE);
        assertEquals(Font.BOLD, scheme.getAttributes(SlangSemanticColors.READONLY_VARIABLE).getFontType());
        foreground(scheme, 0x000000, SlangSemanticColors.BUILTIN_SYMBOL);
        for (var comment : new TextAttributesKey[]{SlangSyntaxHighlighter.LINE_COMMENT,
                SlangSyntaxHighlighter.BLOCK_COMMENT, SlangSyntaxHighlighter.DOC_COMMENT}) {
            foreground(scheme, 0x248700, comment);
            assertEquals(Font.ITALIC, scheme.getAttributes(comment).getFontType());
        }
        assertEquals(Font.PLAIN, scheme.getAttributes(SlangSemanticColors.FUNCTION).getFontType());
        assertNotEquals(scheme.getAttributes(SlangSyntaxHighlighter.PREPROCESSOR).getForegroundColor(),
                scheme.getAttributes(SlangSyntaxHighlighter.INCLUDE_PATH).getForegroundColor());
        assertNotEquals(scheme.getAttributes(SlangSemanticColors.STRUCTURED_BUFFER).getForegroundColor(),
                scheme.getAttributes(SlangSemanticColors.TYPE_ARGUMENT).getForegroundColor());
    }

    @Test
    public void branchOverlaysPreserveSemanticForegroundAndSelectionBackground() throws Exception {
        var scheme = preset();
        var active = scheme.getAttributes(SlangBranchColors.ACTIVE);
        assertNull(active.getForegroundColor());
        assertNull(active.getBackgroundColor());
        assertEquals(EffectType.LINE_UNDERSCORE, active.getEffectType());
        assertEquals(new Color(0x0F54D6), active.getEffectColor());
        var inactive = scheme.getAttributes(SlangBranchColors.INACTIVE);
        assertEquals(new Color(0x949494), inactive.getForegroundColor());
        assertNull(inactive.getBackgroundColor());
        foreground(scheme, 0x949494, SlangBranchColors.LABEL);
        assertEquals(new Color(0xEBEBEB), scheme.getAttributes(SlangBranchColors.LABEL).getBackgroundColor());
    }

    @Test
    public void allMappedRolesMatchThePortableRiderCppReferenceIncludingFontAndEffects() throws Exception {
        var actual = preset();
        var reference = new EditorColorsSchemeImpl(null);
        reference.readAttributes(resource("colorSchemes/RiderLightReference.xml").getChild("attributes"));
        Set<TextAttributesKey> mapped = new HashSet<>();
        // C++-specific categories take precedence; absent categories use the exported language defaults.
        mapped(actual, reference, mapped, "DEFAULT_KEYWORD", SlangSyntaxHighlighter.KEYWORD,
                SlangSyntaxHighlighter.PREPROCESSOR, SlangSyntaxHighlighter.BOOLEAN);
        mapped(actual, reference, mapped, "DEFAULT_CLASS_REFERENCE", SlangSyntaxHighlighter.TYPE,
                SlangSemanticColors.TYPE, SlangSemanticColors.BUILTIN_TYPE);
        mapped(actual, reference, mapped, "DEFAULT_CLASS_NAME", SlangSemanticColors.CLASS);
        mapped(actual, reference, mapped, "DEFAULT_INTERFACE_NAME", SlangSemanticColors.INTERFACE);
        mapped(actual, reference, mapped, "DEFAULT_NUMBER", SlangSyntaxHighlighter.NUMBER);
        mapped(actual, reference, mapped, "DEFAULT_STRING", SlangSyntaxHighlighter.STRING, SlangSyntaxHighlighter.INCLUDE_PATH);
        mapped(actual, reference, mapped, "DEFAULT_LINE_COMMENT", SlangSyntaxHighlighter.LINE_COMMENT);
        mapped(actual, reference, mapped, "DEFAULT_BLOCK_COMMENT", SlangSyntaxHighlighter.BLOCK_COMMENT);
        mapped(actual, reference, mapped, "DEFAULT_DOC_COMMENT", SlangSyntaxHighlighter.DOC_COMMENT);
        mapped(actual, reference, mapped, "DEFAULT_METADATA", SlangSyntaxHighlighter.ATTRIBUTE,
                SlangSyntaxHighlighter.SEMANTIC, SlangSemanticColors.DECORATOR);
        mapped(actual, reference, mapped, "DEFAULT_IDENTIFIER", SlangSemanticColors.IDENTIFIER);
        mapped(actual, reference, mapped, "DEFAULT_PARAMETER", SlangSemanticColors.PARAMETER);
        mapped(actual, reference, mapped, "DEFAULT_CONSTANT", SlangSemanticColors.READONLY_VARIABLE);
        mapped(actual, reference, mapped, "DEFAULT_FUNCTION_CALL", SlangSemanticColors.FUNCTION, SlangSemanticColors.INTRINSIC);
        mapped(actual, reference, mapped, "DEFAULT_INSTANCE_METHOD", SlangSemanticColors.METHOD);
        mapped(actual, reference, mapped, "DEFAULT_STATIC_METHOD", SlangSemanticColors.STATIC_METHOD);
        mapped(actual, reference, mapped, "DEFAULT_PREDEFINED_SYMBOL", SlangSemanticColors.BUILTIN_SYMBOL);
        mapped(actual, reference, mapped, "DEFAULT_OPERATION_SIGN", SlangSyntaxHighlighter.OPERATOR);
        mapped(actual, reference, mapped, "DEFAULT_BRACES", SlangSyntaxHighlighter.BRACES);
        mapped(actual, reference, mapped, "DEFAULT_BRACKETS", SlangSyntaxHighlighter.BRACKETS);
        mapped(actual, reference, mapped, "DEFAULT_PARENTHS", SlangSyntaxHighlighter.PARENTHESES);
        mapped(actual, reference, mapped, "DEFAULT_SEMICOLON", SlangSyntaxHighlighter.SEMICOLON);
        mapped(actual, reference, mapped, "DEFAULT_COMMA", SlangSyntaxHighlighter.COMMA);
        mapped(actual, reference, mapped, "DEFAULT_DOT", SlangSyntaxHighlighter.DOT);
        mapped(actual, reference, mapped, "ReSharper.CPP_PREPROCESSOR_MACRO_IDENTIFIER", SlangSemanticColors.MACRO);
        mapped(actual, reference, mapped, "ReSharper.CPP_STRUCT_FIELD_IDENTIFIER", SlangSemanticColors.PROPERTY,
                SlangSemanticColors.STATIC_PROPERTY, SlangSemanticColors.READONLY_PROPERTY);
        mapped(actual, reference, mapped, "ReSharper.CPP_ENUM_ENUMERATOR_IDENTIFIER", SlangSemanticColors.ENUM_MEMBER);
        mapped(actual, reference, mapped, "ReSharper.CPP_GLOBAL_VARIABLE_IDENTIFIER", SlangSemanticColors.STATIC_VARIABLE);
        mapped(actual, reference, mapped, "ReSharper.CPP_LOCAL_VARIABLE_IDENTIFIER", SlangSemanticColors.VARIABLE);
        mapped(actual, reference, mapped, "ReSharper.NAMESPACE_IDENTIFIER", SlangSemanticColors.NAMESPACE);
        mapped(actual, reference, mapped, "ReSharper.STRUCT_IDENTIFIER", SlangSemanticColors.STRUCT,
                SlangSemanticColors.STRUCTURED_BUFFER);
        mapped(actual, reference, mapped, "ReSharper.ENUM_IDENTIFIER", SlangSemanticColors.ENUM);
        mapped(actual, reference, mapped, "ReSharper.TYPE_PARAMETER_IDENTIFIER", SlangSemanticColors.TYPE_PARAMETER,
                SlangSemanticColors.TYPE_ARGUMENT);
        mapped(actual, reference, mapped, "ReSharper.INACTIVE_PREPROCESSOR_BRANCH", SlangBranchColors.INACTIVE);
        mapped(actual, reference, mapped, "INLINE_PARAMETER_HINT", SlangBranchColors.LABEL);

        Set<TextAttributesKey> settings = Arrays.stream(new SlangColorSettingsPage().getAttributeDescriptors())
                .map(AttributesDescriptor::getKey).collect(Collectors.toSet());
        // These two intentionally retain plugin/platform behavior, not the export's unrelated effects.
        settings.remove(SlangBranchColors.ACTIVE);
        settings.remove(SlangSyntaxHighlighter.BAD_CHARACTER);
        assertEquals(settings, mapped);
    }

    @Test
    public void userOverridesSurviveParentRefreshAndCanOptBackIntoLanguageDefaults() throws Exception {
        var parent = preset();
        var user = new EditorColorsSchemeImpl(parent);
        var customized = color(0x123456);
        user.setAttributes(SlangSemanticColors.STRUCTURED_BUFFER, customized);
        parent.readAttributes(resource(PATH + ".xml").getChild("attributes"));
        assertEquals(customized, user.getAttributes(SlangSemanticColors.STRUCTURED_BUFFER));
        foreground(user, 0x6B2FBA, SlangSemanticColors.TYPE_ARGUMENT);

        // "Inherit values from" is a per-key escape hatch from the provided palette.
        user.setAttributes(DefaultLanguageHighlighterColors.KEYWORD, color(0x456789));
        user.setAttributes(SlangSyntaxHighlighter.KEYWORD, AbstractColorsScheme.INHERITED_ATTRS_MARKER);
        foreground(user, 0x456789, SlangSyntaxHighlighter.KEYWORD);
        foreground(parent, 0x0F54D6, SlangSyntaxHighlighter.KEYWORD);
    }

    @Test
    public void darkAndUnrelatedSchemesKeepFallbacksAndInvalidCharacterIndications() throws Exception {
        var base = new EditorColorsSchemeImpl(null);
        base.setAttributes(DefaultLanguageHighlighterColors.KEYWORD, color(0xCC7832));
        base.setAttributes(DefaultLanguageHighlighterColors.CLASS_NAME, color(0xA9B7C6));
        base.setAttributes(HighlighterColors.BAD_CHARACTER, color(0xFF0000));
        var dark = new EditorColorsSchemeImpl(base);
        var light = new EditorColorsSchemeImpl(base);
        light.readAttributes(resource(PATH + ".xml").getChild("attributes"));
        foreground(light, 0x0F54D6, SlangSyntaxHighlighter.KEYWORD);
        foreground(dark, 0xCC7832, SlangSyntaxHighlighter.KEYWORD);
        foreground(dark, 0xA9B7C6, SlangSemanticColors.STRUCTURED_BUFFER);
        foreground(light, 0xCC7832, DefaultLanguageHighlighterColors.KEYWORD);
        foreground(light, 0xFF0000, SlangSyntaxHighlighter.BAD_CHARACTER);
        assertTrue(dark.getDirectlyDefinedAttributes().isEmpty());
        assertTrue(light.getDirectlyDefinedColors().isEmpty());
    }

    private static EditorColorsSchemeImpl preset() throws Exception {
        var scheme = new EditorColorsSchemeImpl(null);
        // The additive loader calls readAttributes on this very child; use the platform parser.
        scheme.readAttributes(resource(PATH + ".xml").getChild("attributes"));
        return scheme;
    }

    private static TextAttributes color(int rgb) {
        return new TextAttributes(new Color(rgb), null, null, null, Font.PLAIN);
    }

    private static void mapped(EditorColorsSchemeImpl actual, EditorColorsSchemeImpl reference,
                               Set<TextAttributesKey> mapped, String source, TextAttributesKey... targets) {
        TextAttributes expected = reference.getAttributes(TextAttributesKey.createTextAttributesKey(source));
        assertNotNull(source, expected);
        for (var target : targets) {
            assertTrue("Duplicate mapping: " + target, mapped.add(target));
            assertEquals(source + " -> " + target, expected, actual.getAttributes(target));
        }
    }

    private static void foreground(EditorColorsSchemeImpl scheme, int rgb, TextAttributesKey... keys) {
        for (var key : keys) {
            assertEquals(key.getExternalName(), new Color(rgb), scheme.getAttributes(key).getForegroundColor());
        }
    }

    private static Element resource(String path) throws Exception {
        try (InputStream input = SlangRiderLightColorSchemeTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull("Missing bundled resource: " + path, input);
            return JDOMUtil.load(input);
        }
    }
}
