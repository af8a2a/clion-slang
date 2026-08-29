package dev.slang.intellij.navigation;

import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.util.TextRange;
import org.eclipse.lsp4j.Position;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SlangGotoDeclarationHandlerTest {
    @Test
    public void findsTheWholeIdentifierAtAndAfterTheCaret() {
        String source = "float value = projectDepth(position);";
        int start = source.indexOf("projectDepth");
        int end = start + "projectDepth".length();

        assertEquals(new TextRange(start, end), SlangGotoDeclarationHandler.identifierRangeAt(source, start + 3));
        assertEquals(new TextRange(start, end), SlangGotoDeclarationHandler.identifierRangeAt(source, end));
        assertEquals(end - 1, SlangGotoDeclarationHandler.requestOffsetForIdentifier(
                new TextRange(start, end),
                end
        ));
        assertNull(SlangGotoDeclarationHandler.identifierRangeAt(source, source.indexOf('=')));
    }

    @Test
    public void convertsUtf16Utf8AndUtf32Positions() {
        DocumentImpl document = new DocumentImpl("é😀name\nsecond");
        int offsetAfterUnicode = 3;

        assertEquals(3, SlangGotoDeclarationHandler.toLspCharacter(document, 0, offsetAfterUnicode, "utf-16"));
        assertEquals(6, SlangGotoDeclarationHandler.toLspCharacter(document, 0, offsetAfterUnicode, "utf-8"));
        assertEquals(2, SlangGotoDeclarationHandler.toLspCharacter(document, 0, offsetAfterUnicode, "utf-32"));

        assertEquals(offsetAfterUnicode, SlangGotoDeclarationHandler.toDocumentOffset(
                document,
                new Position(0, 3),
                "utf-16"
        ));
        assertEquals(offsetAfterUnicode, SlangGotoDeclarationHandler.toDocumentOffset(
                document,
                new Position(0, 6),
                "utf-8"
        ));
        assertEquals(offsetAfterUnicode, SlangGotoDeclarationHandler.toDocumentOffset(
                document,
                new Position(0, 2),
                "utf-32"
        ));
    }
}
