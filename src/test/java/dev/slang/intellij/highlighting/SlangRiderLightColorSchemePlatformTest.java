package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Color;

/** Full scheme loading needs an application service; run with the platform test framework. */
public class SlangRiderLightColorSchemePlatformTest extends BasePlatformTestCase {
    public void testAttributeFragmentLoadsWithoutParentResolution() throws Exception {
        var manager = EditorColorsManager.getInstance();
        var scheme = new EditorColorsSchemeImpl(manager.getScheme("Light"));
        try (var input = getClass().getClassLoader().getResourceAsStream("colorSchemes/SlangRiderLight.xml")) {
            assertNotNull(input);
            scheme.readAttributes(JDOMUtil.load(input).getChild("attributes"));
        }
        assertSame(manager.getScheme("Light"), scheme.getParentScheme());
        assertEquals(Color.WHITE, scheme.getDefaultBackground());
        assertEquals(new Color(0x0F54D6), scheme.getAttributes(SlangSyntaxHighlighter.KEYWORD).getForegroundColor());
        assertEquals(new Color(0x00855F), scheme.getAttributes(SlangSemanticColors.FUNCTION).getForegroundColor());
    }

    public void testAdditivePalettePreservesAllNonSlangAttributesAndEditorColors() throws Exception {
        var manager = EditorColorsManager.getInstance();
        var light = manager.getScheme("Light");
        var preset = new EditorColorsSchemeImpl(light);
        try (var input = getClass().getClassLoader().getResourceAsStream("colorSchemes/SlangRiderLight.xml")) {
            assertNotNull(input);
            preset.readAttributes(JDOMUtil.load(input).getChild("attributes"));
        }
        assertNotNull(light);
        assertNotNull(preset);
        // Include inherited and CLion-provided C++ keys, not just a few language defaults.
        for (var current = light; current instanceof AbstractColorsScheme scheme; current = scheme.getParentScheme()) {
            for (String name : scheme.getDirectlyDefinedAttributes().keySet()) {
                if (!name.startsWith("SLANG.")) {
                    var key = TextAttributesKey.createTextAttributesKey(name);
                    assertEquals(name, light.getAttributes(key), preset.getAttributes(key));
                }
            }
            for (var key : scheme.getDirectlyDefinedColors().keySet()) {
                assertEquals(key.toString(), light.getColor(key), preset.getColor(key));
            }
        }
    }

    public void testExtensionsLoadThePresetAndOnlyLightDefaults() {
        var manager = EditorColorsManager.getInstance();
        for (String name : new String[]{"Light", "IntelliJ Light"}) {
            var scheme = manager.getScheme(name);
            assertNotNull("Missing registered scheme: " + name, scheme);
            assertEquals(name, new Color(0x0F54D6), scheme.getAttributes(SlangSyntaxHighlighter.KEYWORD).getForegroundColor());
            assertEquals(name, new Color(0x300073), scheme.getAttributes(SlangSemanticColors.STRUCTURED_BUFFER).getForegroundColor());
        }
        for (String name : new String[]{"Default", "Darcula"}) {
            var scheme = manager.getScheme(name);
            assertNotNull(scheme);
            assertEquals(name, scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD),
                    scheme.getAttributes(SlangSyntaxHighlighter.KEYWORD));
        }
    }
}
