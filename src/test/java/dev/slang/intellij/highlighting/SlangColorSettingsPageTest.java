package dev.slang.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlangColorSettingsPageTest {
    private static final Pattern SEMANTIC_TAG = Pattern.compile("</?(semantic[A-Za-z0-9]+)>");

    @Test
    public void exposesSemanticTagsAndDescriptors() {
        SlangColorSettingsPage page = new SlangColorSettingsPage();
        Map<String, TextAttributesKey> tags = page.getAdditionalHighlightingTagToDescriptorMap();

        assertEquals(26, tags.size());
        assertSame(SlangSemanticColors.STRUCT, tags.get("semanticStruct"));
        assertSame(SlangSemanticColors.PARAMETER, tags.get("semanticParameter"));
        assertSame(SlangSemanticColors.INTRINSIC, tags.get("semanticIntrinsic"));
        assertSame(SlangSemanticColors.DECORATOR, tags.get("semanticDecorator"));
        assertSame(SlangSemanticColors.SHADER_SEMANTIC, tags.get("semanticShaderSemantic"));
        assertSame(SlangSemanticColors.SWIZZLE, tags.get("semanticSwizzle"));
        assertNotSame(SlangSyntaxHighlighter.SEMANTIC, SlangSemanticColors.SHADER_SEMANTIC);
        assertNotSame(SlangSemanticColors.PROPERTY, SlangSemanticColors.SWIZZLE);

        Set<TextAttributesKey> descriptorKeys = new HashSet<>();
        for (AttributesDescriptor descriptor : page.getAttributeDescriptors()) {
            assertTrue("duplicate descriptor key: " + descriptor.getKey(), descriptorKeys.add(descriptor.getKey()));
        }
        assertTrue(descriptorKeys.containsAll(tags.values()));
    }

    @Test
    public void metallicCalibrationTemplateCoversEveryRegisteredSemanticColor() {
        SlangColorSettingsPage page = new SlangColorSettingsPage();
        String demo = page.getDemoText();
        Map<String, TextAttributesKey> tags = page.getAdditionalHighlightingTagToDescriptorMap();

        Set<String> usedTags = new HashSet<>();
        Matcher matcher = SEMANTIC_TAG.matcher(demo);
        while (matcher.find()) {
            String tag = matcher.group(1);
            assertTrue("unregistered semantic tag: " + tag, tags.containsKey(tag));
            usedTags.add(tag);
        }

        assertEquals(tags.keySet(), usedTags);
        tags.keySet().forEach(tag -> assertEquals(
                "unbalanced semantic tag: " + tag,
                occurrences(demo, "<" + tag + ">"),
                occurrences(demo, "</" + tag + ">")
        ));

        assertTrue(demo.contains("vk::<semanticDecorator>binding</semanticDecorator>"));
        assertTrue(demo.contains("<semanticBuiltinType>RWTexture2D</semanticBuiltinType>"));
        assertTrue(demo.contains("<semanticBuiltinType>DescriptorHandle</semanticBuiltinType>"));
        assertTrue(demo.contains("[<semanticDecorator>shader</semanticDecorator>(\"compute\")]"));
        assertTrue(demo.contains("uniform <semanticType>CompositePush</semanticType>"));
        assertTrue(demo.contains("<semanticShaderSemantic>SV_DispatchThreadID</semanticShaderSemantic>"));
        assertTrue(demo.contains("dispatchThreadID.<semanticSwizzle>xy</semanticSwizzle>"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
