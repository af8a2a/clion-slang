package dev.slang.intellij.preprocessor;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;

import java.awt.Color;
import java.awt.Font;

public final class SlangBranchColors {
    public static final TextAttributesKey INACTIVE = TextAttributesKey.createTextAttributesKey(
            "SLANG.PREPROCESSOR.INACTIVE_CODE", CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES);
    public static final TextAttributesKey ACTIVE = TextAttributesKey.createTextAttributesKey(
            "SLANG.PREPROCESSOR.ACTIVE_BRANCH",
            new TextAttributes(null, null, new Color(0x808080), EffectType.LINE_UNDERSCORE, Font.PLAIN));
    public static final TextAttributesKey LABEL = TextAttributesKey.createTextAttributesKey(
            "SLANG.PREPROCESSOR.BRANCH_LABEL", DefaultLanguageHighlighterColors.INLAY_DEFAULT);

    private SlangBranchColors() {}
}
