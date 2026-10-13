package dev.slang.intellij.navigation;

import org.junit.Test;
import java.io.*;
import java.util.List;
import static org.junit.Assert.*;

public class SlangInheritanceTest {
    @Test public void genericAliasesTypedefsAndScopedImportsPreserveTargets() {
        var symbols = SlangSymbolExtractor.extract("""
                using Shared;
                namespace Demo {
                    using Material;
                    public typealias Program<T : ISource> = ProgramImpl<T>;
                    typealias SceneProgram = Program<SceneSource>;
                    typedef Other::IGeneric<float> Legacy;
                    typealias Identity<T> = T;
                    typealias Dependent<T> = T.Closure;
                    associatedtype Closure : IClosure;
                    typedef IClosure* Pointer;
                    typedef IClosure Array[4];
                    typealias Broken = IClosure<float;
                    typedef IClosure<float BrokenTypedef;
                    struct Generic<T> { typealias Member = T; }
                    extension<TProgram : IProgram> Material<TProgram> { typealias ExtensionParameter = TProgram; }
                }
                namespace Sibling { struct Thing : IFoo {} }
                """);
        assertEquals(List.of("ProgramImpl"), find(symbols, "Program").baseTypes());
        assertEquals(List.of("Program"), find(symbols, "SceneProgram").baseTypes());
        assertEquals(List.of("Other.IGeneric"), find(symbols, "Legacy").baseTypes());
        for (String name : List.of("Identity", "Dependent", "Closure", "Pointer", "Array", "Member", "ExtensionParameter", "Broken", "BrokenTypedef"))
            assertTrue(name, find(symbols, name).baseTypes().isEmpty());
        assertEquals(List.of(new SlangSymbol.NamespaceImport("Shared", ""),
                new SlangSymbol.NamespaceImport("Material", "Demo")), find(symbols, "Program").usingNamespaces());
        assertEquals(List.of(new SlangSymbol.NamespaceImport("Shared", "")), find(symbols, "Thing").usingNamespaces());
    }

    @Test public void extractsOnlyBaseClausesNotGenericConstraintsOrBodies() throws Exception {
        var symbols = SlangSymbolExtractor.extract("""
                namespace A.B {
                    interface IBase<T> {}
                    interface IChild : IBase<float> {}
                    struct Derived<T : IConstraint> : IChild, Other::IGeneric<Box<T, int>> {
                        struct Nested : A.B.IBase<float> {}
                        void run() { int fake = a ? b : c; }
                    }
                    struct OnlyConstraint<T : IBase<float>> {}
                    extension<T> Box<T> : IBase<float> {}
                }
                """);
        assertEquals(List.of("IBase"), find(symbols, "IChild").baseTypes());
        assertEquals(List.of("IChild", "Other.IGeneric"), find(symbols, "Derived").baseTypes());
        assertEquals(List.of("A.B.IBase"), find(symbols, "Nested").baseTypes());
        assertTrue(find(symbols, "OnlyConstraint").baseTypes().isEmpty());
        assertTrue(find(symbols, "IBase").isInterface());
        assertFalse(find(symbols, "Derived").isInterface());
        var bytes = new ByteArrayOutputStream();
        SlangSymbolIndex.VALUES.save(new DataOutputStream(bytes), symbols);
        assertEquals(symbols, SlangSymbolIndex.VALUES.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test public void resolvesQualifiedAndNearestNamesWithoutMixingAmbiguousNamespaces() {
        var a = declaration("A");
        var b = declaration("B");
        var candidates = List.of(a, b);
        assertTrue(SlangDerivedTypes.resolvesTo("IFoo", "A.Nested", candidates, a));
        assertFalse(SlangDerivedTypes.resolvesTo("IFoo", "B", candidates, a));
        assertTrue(SlangDerivedTypes.resolvesTo("A.IFoo", "B", candidates, a));
        assertFalse(SlangDerivedTypes.resolvesTo("IFoo", "Elsewhere", candidates, a));
        assertFalse(SlangDerivedTypes.resolvesTo("IBar", "A", candidates, a));
    }

    private static SlangDerivedTypes.Declaration declaration(String owner) {
        return new SlangDerivedTypes.Declaration(null, new SlangSymbol("IFoo", owner, "interface IFoo", SlangSymbol.Kind.TYPE, 0));
    }
    private static SlangSymbol find(List<SlangSymbol> symbols, String name) {
        return symbols.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
