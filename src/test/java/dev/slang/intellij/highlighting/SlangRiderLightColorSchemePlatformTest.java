package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Color;

/** Full scheme loading needs an application service; run with the platform test framework. */
public class SlangRiderLightColorSchemePlatformTest extends BasePlatformTestCase {
    public void testStandaloneResourceLoadsWithTheRealDefaultParent() throws Exception {
        var manager = EditorColorsManager.getInstance();
        var scheme = new EditorColorsSchemeImpl(null);
        try (var input = getClass().getClassLoader().getResourceAsStream("colorSchemes/SlangRiderLight.xml")) {
            assertNotNull(input);
            scheme.readExternal(JDOMUtil.load(input));
        }
        scheme.resolveParent(manager::getScheme);
        assertEquals("Slang Rider Light", scheme.getName());
        assertEquals(Color.WHITE, scheme.getDefaultBackground());
        assertEquals(new Color(0x0F54D6), scheme.getAttributes(SlangSyntaxHighlighter.KEYWORD).getForegroundColor());
        assertEquals(new Color(0x00855F), scheme.getAttributes(SlangSemanticColors.FUNCTION).getForegroundColor());
    }

    public void testExtensionsLoadThePresetAndOnlyLightDefaults() {
        var manager = EditorColorsManager.getInstance();
        for (String name : new String[]{"Slang Rider Light", "Light", "IntelliJ Light"}) {
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
