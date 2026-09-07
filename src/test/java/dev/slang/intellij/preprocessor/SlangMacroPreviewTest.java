package dev.slang.intellij.preprocessor;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class SlangMacroPreviewTest {
    @Test public void preservesReplacementTextAndCanonicalizesIdentity() {
        var input = SlangMacroPreview.parse("MODE=2\r\nEMPTY\r\nTEXT=\"a=b; c\"\r\nSPACE= x ", "REMOVED\nFLAG");
        var macros = input.wire().defines().stream().collect(Collectors.toMap(m -> m.name(), m -> m.value()));
        assertEquals("", macros.get("EMPTY"));
        assertEquals("\"a=b; c\"", macros.get("TEXT"));
        assertEquals(" x ", macros.get("SPACE"));
        assertEquals(List.of("FLAG", "REMOVED"), input.wire().undefines());
        assertEquals(input.wire().fingerprint(), SlangMacroPreview.parse(
                "SPACE= x \nTEXT=\"a=b; c\"\nEMPTY=\nMODE=2", "FLAG\nREMOVED").wire().fingerprint());
        assertNotEquals(input.wire().fingerprint(), SlangMacroPreview.parse("MODE=1", "").wire().fingerprint());
        assertTrue(input.wire().fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test public void rejectsConflictsCommandsFunctionMacrosAndInvalidValues() {
        for (String text : List.of("A=1\nA=2", "-DA=1", "#define A 1", "F(x)=x", "BAD-NAME=1",
                "1A=1", "A=\0", "A=a\rb", "非ASCII=1", "A".repeat(129) + "=1", "A=" + "😀".repeat(1025)))
            assertThrows(text.substring(0, Math.min(30, text.length())), IllegalArgumentException.class,
                    () -> SlangMacroPreview.parse(text, ""));
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse("A=1", "A"));
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse("", "A\nA"));
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse("", "A=1"));
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse(" \n", "\n"));
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse(" ".repeat(1_200_001), ""));
    }

    @Test public void enforcesCombinedLimitAndUtf8ByteLimit() {
        String names = IntStream.range(0, 256).mapToObj(i -> "A" + i).collect(Collectors.joining("\n"));
        assertEquals(256, SlangMacroPreview.parse(names, "").wire().defines().size());
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse(names, "B"));
        assertEquals(4096, SlangMacroPreview.parse("A=" + "x".repeat(4096), "").wire().defines().getFirst().value().length());
        assertThrows(IllegalArgumentException.class, () -> SlangMacroPreview.parse("A=" + "x".repeat(4097), ""));
    }
}
