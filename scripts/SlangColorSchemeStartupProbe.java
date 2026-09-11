import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.util.JDOMUtil;

import java.nio.file.Path;

/** Run in a fresh JVM against the IDE libraries, before any Slang Java keys initialize. */
class SlangColorSchemeStartupProbe {
    private static final class ComparableScheme extends EditorColorsSchemeImpl {
        ComparableScheme(DefaultColorsScheme parent) { super(parent); }

        boolean sameAttributes(ComparableScheme other) {
            // EditorColorsManagerImpl.hideIntellijLightSchemeIfNeeded compares without defaults.
            return attributesEqual(other, false);
        }
    }

    public static void main(String[] args) throws Exception {
        var palette = JDOMUtil.load(Path.of(args[0])).getChild("attributes");
        var parent = new DefaultColorsScheme();
        var bundled = new ComparableScheme(parent);
        var editable = new ComparableScheme(parent);
        bundled.readAttributes(palette);
        editable.readAttributes(palette);
        // No references to SlangSyntaxHighlighter/SlangSemanticColors are allowed here:
        // class initialization would mask missing fallbacks during IDE startup.
        for (var option : palette.getChildren("option")) {
            var name = option.getAttributeValue("name");
            if (TextAttributesKey.find(name).getFallbackAttributeKey() != null) {
                throw new AssertionError("Probe must run before fallback registration: " + name);
            }
        }
        if (!editable.sameAttributes(bundled) || !bundled.sameAttributes(editable)) {
            throw new AssertionError("Identical light-scheme palettes compare differently");
        }
        System.out.println("PASS: cold light-scheme comparison before Slang color-key initialization");
    }
}
