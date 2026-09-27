package dev.slang.intellij.navigation;

import org.junit.Test;
import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class SlangSymbolExtractorTest {
    @Test public void indexesScreenshotFunctionsWithSignaturesAndExactOffsetsButNoLocalsOrCalls() {
        String source = """
                #ifndef STREAM_DECODE
                #define STREAM_DECODE
                import Core;
                using Metallic;
                // module description
                uint streamNormalStride(uint format) { return format == 2u ? 16u : 4u; }
                uint streamTangentStride(uint format) { return 16u; }
                float3 streamLoadNormal(StructuredBuffer<uint> words, uint page, uint offset, uint vertex, uint format)
                {
                    uint n = page + vertex * streamNormalStride(format);
                    return decodeSceneNormal(words[n]);
                }
                float4 streamLoadTangent(StructuredBuffer<uint> words, uint page)
                { return float4(0); }
                #endif
                """;
        var symbols = SlangSymbolExtractor.extract(source);
        assertEquals(List.of("streamNormalStride", "streamTangentStride", "streamLoadNormal", "streamLoadTangent"),
                symbols.stream().map(SlangSymbol::name).toList());
        assertEquals("streamNormalStride(uint format)", symbols.getFirst().signature());
        for (var symbol : symbols) {
            assertEquals(SlangSymbol.Kind.FUNCTION, symbol.kind());
            assertEquals(source.indexOf(symbol.name()), symbol.offset());
        }
    }

    @Test public void typesMembersEnumsAliasesAndOverloadsRetainTheirContainers() {
        String source = """
                namespace Metallic.Lighting {
                    public struct Hit<T> {
                        uint instanceID;
                        public float3 normal;
                        T values[3];
                        Hit() { uint local; }
                        float sampleValue(float x) { return x; }
                        float sampleValue(float2 x);
                        struct Inner { int member; };
                    };
                    enum Mode { First, Second = min(1, 2), Third };
                    typedef vector<float, 3> Color;
                    typealias Id = uint;
                    static const uint LIMIT = 3;
                    float compute<T : IFoo>(T input) { return 0; }
                }
                """;
        var symbols = SlangSymbolExtractor.extract(source);
        assertTrue(symbols.stream().anyMatch(s -> s.qualifiedName().equals("Metallic.Lighting.Hit.Inner.member")));
        assertEquals(2, symbols.stream().filter(s -> s.name().equals("sampleValue")).count());
        assertEquals("Metallic.Lighting.Hit", find(symbols, "normal").container());
        assertEquals(SlangSymbol.Kind.FIELD, find(symbols, "values").kind());
        assertEquals(SlangSymbol.Kind.ENUM_MEMBER, find(symbols, "Second").kind());
        assertEquals(SlangSymbol.Kind.ALIAS, find(symbols, "Color").kind());
        assertEquals(SlangSymbol.Kind.ALIAS, find(symbols, "Id").kind());
        assertEquals("compute<T : IFoo>(T input)", find(symbols, "compute").signature());
        assertFalse(names(symbols).contains("local"));
        assertFalse(names(symbols).contains("min"));
        assertFalse(names(symbols).contains("input"));
    }

    @Test public void attributesInitializersCommentsAndStringsDoNotCreateCallSymbols() {
        var symbols = SlangSymbolExtractor.extract("""
                // float comment(uint x) {}
                /* struct Fake { float member; }; */
                string message = "float fake() {}";
                [shader("compute")]
                [numthreads(8, 8, 1)]
                void main(uint3 id : SV_DispatchThreadID) { helper(); }
                float4 color = makeColor(1, 2);
                float array[2] = { 1, 2 };
                uint a = 0, b = 1;
                StructuredBuffer<vector<float, 3>> buffer;
                #define GENERATED(name) float name() { return 0; }
                """);
        assertEquals(Set.of("message", "main", "color", "array", "a", "b", "buffer"), names(symbols));
        assertEquals(SlangSymbol.Kind.VARIABLE, find(symbols, "color").kind());
    }

    @Test public void namespaceExtensionInterfaceAndHeaderPrototypesAreSearchable() {
        var symbols = SlangSymbolExtractor.extract("""
                module Example;
                __include "Impl.slang";
                implementing Example;
                namespace A::B { interface IFoo { associatedtype Element; float apply(float x); }; }
                extension<T> Box<T> { float getValue() { return 0; } }
                cbuffer Settings { float exposure; uint flags; };
                float prototype(float value);
                """);
        assertEquals("A.B.IFoo", find(symbols, "apply").container());
        assertEquals("Box<T>", find(symbols, "getValue").container());
        assertEquals("Settings", find(symbols, "exposure").container());
        assertEquals(SlangSymbol.Kind.FUNCTION, find(symbols, "prototype").kind());
        assertFalse(names(symbols).contains("Example"));
    }

    @Test public void utf16CrLfEditsAndConditionalBranchesPreservePhysicalDeclarationOffsets() {
        String source = "// 😀\r\n#if A\r\nfloat first() { return 0; }\r\n#else\r\nfloat second() { return 0; }\r\n#endif\r\n";
        for (String current : List.of(source, source.replace("first", "renamed").replace("second", "updated"))) {
            var symbols = SlangSymbolExtractor.extract(current);
            assertEquals(2, symbols.size());
            for (var s : symbols) assertEquals(s.name(), current.substring(s.offset(), s.offset() + s.name().length()));
        }
        assertTrue(SlangSymbolExtractor.extract("// deleted content").isEmpty());
    }

    @Test public void contextualMethodNamesAndGenericDeclarationsRemainSearchable() {
        var symbols = SlangSymbolExtractor.extract("""
                interface IContainer { associatedtype Element; Element get(int index); }
                struct Sampler { float sample(float x); void set(float x); }
                __generic<typename T, let N : int>
                vector<T, N> identity(vector<T, N> value) { return value; }
                """);
        for (String name : List.of("get", "sample", "set", "identity"))
            assertEquals(SlangSymbol.Kind.FUNCTION, find(symbols, name).kind());
        assertFalse(names(symbols).contains("index"));
        assertFalse(names(symbols).contains("N"));
    }

    @Test public void indexPreservesOverloadsAndSerializationRoundTrips() throws Exception {
        var indexed = SlangSymbolIndex.index("float f(float a); float f(int a); struct Data { uint field; };");
        assertEquals(2, indexed.get("f").size());
        var bytes = new ByteArrayOutputStream();
        SlangSymbolIndex.VALUES.save(new DataOutputStream(bytes), indexed.get("f"));
        assertEquals(indexed.get("f"), SlangSymbolIndex.VALUES.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertFalse(SlangSymbolIndex.index("float renamed(int a);").containsKey("f"));
    }

    private static SlangSymbol find(List<SlangSymbol> symbols, String name) {
        return symbols.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " missing: " + symbols));
    }
    private static Set<String> names(List<SlangSymbol> symbols) {
        return symbols.stream().map(SlangSymbol::name).collect(Collectors.toSet());
    }
}
