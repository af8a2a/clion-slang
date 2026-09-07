package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import dev.slang.intellij.lang.SlangTokenTypes;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlangColorSettingsPageTest {
    @Test
    public void includePathsHaveAnIndependentStringBasedColor() {
        SlangSyntaxHighlighter highlighter = new SlangSyntaxHighlighter();
        assertSame(SlangSyntaxHighlighter.PREPROCESSOR,
                highlighter.getTokenHighlights(SlangTokenTypes.PREPROCESSOR)[0]);
        assertSame(SlangSyntaxHighlighter.INCLUDE_PATH,
                highlighter.getTokenHighlights(SlangTokenTypes.INCLUDE_PATH)[0]);
        assertSame(SlangSyntaxHighlighter.STRING, SlangSyntaxHighlighter.INCLUDE_PATH.getFallbackAttributeKey());
        assertTrue(Arrays.stream(new SlangColorSettingsPage().getAttributeDescriptors())
                .anyMatch(descriptor -> descriptor.getKey() == SlangSyntaxHighlighter.INCLUDE_PATH));
    }

    @Test
    public void exposesSemanticTagsAndDescriptors() {
        SlangColorSettingsPage page = new SlangColorSettingsPage();
        Map<String, TextAttributesKey> tags = page.getAdditionalHighlightingTagToDescriptorMap();

        assertEquals(29, tags.size());
        assertSame(SlangSemanticColors.STRUCTURED_BUFFER, tags.get("semanticStructuredBuffer"));
        assertSame(SlangSemanticColors.TYPE_ARGUMENT, tags.get("semanticTypeArgument"));
        assertSame(SlangSemanticColors.STRUCT, tags.get("semanticStruct"));
        assertSame(SlangSemanticColors.PARAMETER, tags.get("semanticParameter"));
        assertSame(SlangSemanticColors.INTRINSIC, tags.get("semanticIntrinsic"));
        assertSame(SlangSemanticColors.DECORATOR, tags.get("semanticDecorator"));

        Set<TextAttributesKey> descriptorKeys = new HashSet<>();
        for (AttributesDescriptor descriptor : page.getAttributeDescriptors()) {
            assertTrue("duplicate descriptor key: " + descriptor.getKey(), descriptorKeys.add(descriptor.getKey()));
        }
        assertTrue(descriptorKeys.containsAll(tags.values()));
    }

    @Test
    public void everyDemoSemanticTagHasARegisteredColor() {
        SlangColorSettingsPage page = new SlangColorSettingsPage();
        String demo = page.getDemoText();
        Map<String, TextAttributesKey> tags = page.getAdditionalHighlightingTagToDescriptorMap();

        Set<String> usedTags = new HashSet<>();
        tags.keySet().forEach(tag -> {
            if (demo.contains("<" + tag + ">")) {
                usedTags.add(tag);
                assertTrue(demo.contains("</" + tag + ">"));
            }
        });

        assertTrue(usedTags.containsAll(Arrays.asList(
                "semanticNamespace", "semanticStruct", "semanticInterface", "semanticEnum",
                "semanticTypeParameter", "semanticParameter", "semanticVariable",
                "semanticProperty", "semanticFunction", "semanticIntrinsic", "semanticDecorator",
                "semanticStructuredBuffer", "semanticTypeArgument"
        )));
    }

    @Test public void structuredBuffersHaveTheSameConfigurableLexicalAndSemanticColor() {
        var highlighter = new SlangSyntaxHighlighter();
        assertSame(SlangSemanticColors.STRUCTURED_BUFFER, highlighter.getTokenHighlights(SlangTokenTypes.STRUCTURED_BUFFER_TYPE)[0]);
        assertSame(SlangSyntaxHighlighter.TYPE, highlighter.getTokenHighlights(SlangTokenTypes.TYPE_KEYWORD)[0]);
        assertSame(SlangSemanticColors.STRUCT, SlangSemanticColors.STRUCTURED_BUFFER.getFallbackAttributeKey());
        assertSame(SlangSemanticColors.TYPE_PARAMETER, SlangSemanticColors.TYPE_ARGUMENT.getFallbackAttributeKey());
    }
}
