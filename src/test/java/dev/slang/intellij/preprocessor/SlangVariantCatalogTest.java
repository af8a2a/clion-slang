package dev.slang.intellij.preprocessor;

import dev.slang.intellij.settings.SlangProjectSettings;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class SlangVariantCatalogTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final Path workspace = Path.of("workspace").toAbsolutePath();
    private final Path manifest = workspace.resolve("build/slang-variants.json");
    private static final String SAMPLE = """
            {"version":1,"contexts":[{"id":"compute","name":"Compute","root":"../shader.slang",
              "entryPoint":"main","stage":"compute","target":"spirv","profile":"spirv_1_5",
              "defines":{"BASE":"1","REMOVE":"1"},"undefines":["UNSET"],"includePaths":["../base"],
              "variants":[
                {"id":"a","name":"A","defines":{"MODE":"1","EMPTY":"","UNSET":"2"},"undefines":["REMOVE"],"includePaths":["../special"]},
                {"id":"b","defines":{"MODE":"2"}}
              ]}]}
            """;
    private SlangVariantCatalog parse(String json) { return SlangVariantCatalog.parse(manifest, workspace, json); }

    @Test public void resolvesInheritedFieldsAndMacroPrecedenceWithoutWorkspaceLeakage() {
        var catalog = parse(SAMPLE);
        assertEquals(2, catalog.variants().size());
        var a = catalog.find("compute/a");
        assertEquals(workspace.resolve("shader.slang"), a.root());
        assertEquals("Compute / A", a.label());
        assertEquals("main", a.entryPoint());
        assertEquals("compute", a.stage());
        assertFalse(a.buildContext().inheritWorkspace());
        assertEquals(Map.of("BASE", "1", "MODE", "1", "EMPTY", "", "UNSET", "2"), macros(a));
        assertEquals(List.of("REMOVE"), a.buildContext().undefines());
        assertEquals(List.of(workspace.resolve("special").toString(), workspace.resolve("base").toString()), a.buildContext().includePaths());
        assertEquals(List.of("UNSET"), catalog.find("compute/b").buildContext().undefines());
        assertEquals(64, a.buildContext().fingerprint().length());
    }

    @Test public void fingerprintsAreStableAcrossObjectOrderButCoverAllSemanticInputs() {
        String original = parse(SAMPLE).find("compute/a").buildContext().fingerprint();
        assertEquals(original, parse(SAMPLE.replace("\"BASE\":\"1\",\"REMOVE\":\"1\"", "\"REMOVE\":\"1\",\"BASE\":\"1\""))
                .find("compute/a").buildContext().fingerprint());
        for (String changed : List.of(SAMPLE.replace("\"MODE\":\"1\"", "\"MODE\":\"3\""), SAMPLE.replace("../shader.slang", "../other.slang"),
                SAMPLE.replace("../special", "../different"), SAMPLE.replace("spirv_1_5", "spirv_1_6"), SAMPLE.replace("\"spirv\"", "\"dxil\""),
                SAMPLE.replace("\"main\"", "\"otherMain\""), SAMPLE.replace("\"stage\":\"compute\"", "\"stage\":\"vertex\""),
                SAMPLE.replace("\"name\":\"Compute\"", "\"name\":\"Compute\",\"configuration\":\"Debug\""),
                SAMPLE.replace("\"name\":\"Compute\"", "\"name\":\"Compute\",\"buildTarget\":\"shader_lib\""),
                SAMPLE.replace("\"name\":\"Compute\"", "\"name\":\"Compute\",\"inheritWorkspace\":true")))
            assertNotEquals(original, parse(changed).find("compute/a").buildContext().fingerprint());
        assertNotEquals(original, parse(SAMPLE).find("compute/b").buildContext().fingerprint());
    }

    @Test public void omittedVariantsCreatesExactlyOneDefaultAndExpandsOnlyWorkspaceVariable() {
        var variant = parse("{\"version\":1,\"contexts\":[{\"id\":\"one\",\"root\":\"${workspaceFolder}/shader.slang\"}]}").variants().getFirst();
        assertEquals("one/default", variant.id());
        assertEquals(workspace.resolve("shader.slang"), variant.root());
        assertTrue(variant.buildContext().defines().isEmpty());
        assertEquals("", variant.buildContext().target());
        assertNull(parse(SAMPLE).find("missing"));
    }

    @Test public void rejectsMalformedAmbiguousAndUnsupportedManifests() {
        for (String invalid : List.of(SAMPLE.replace("\"version\":1", "\"version\":2"),
                SAMPLE.replace("\"version\":1", "\"version\":\"1\""), SAMPLE + "true",
                SAMPLE.replace("\"MODE\":\"1\"", "\"MODE\":1"), SAMPLE.replace("\"MODE\":\"1\"", "\"BAD-NAME\":\"1\""),
                SAMPLE.replace("\"MODE\":\"1\"", "\"MODE\":\"1\",\"MODE\":\"2\""),
                SAMPLE.replace("\"id\":\"b\"", "\"id\":\"a\""), SAMPLE.replace("\"id\":\"b\"", "\"id\":\"a/b\""),
                SAMPLE.replace("\"spirv\"", "\"bogus\""), SAMPLE.replace("\"stage\":\"compute\"", "\"stage\":\"bogus\""),
                SAMPLE.replace("../shader.slang", "../shader.cpp"), SAMPLE.replace("../shader.slang", "${HOME}/shader.slang"),
                SAMPLE.replace("\"undefines\":[\"REMOVE\"]", "\"undefines\":[\"MODE\"]"),
                SAMPLE.replace("\"target\":\"spirv\"", "\"target\":\"spirv\",\"command\":\"do not execute\""),
                SAMPLE.replace("\"EMPTY\":\"\"", "\"EMPTY\":\"bad\\nvalue\""),
                "{\"version\":1,\"contexts\":[{\"id\":\"a\",\"root\":\"a.slang\",\"variants\":[]}]}",
                "{\"version\":1,\"contexts\":[{\"id\":\"a\",\"root\":\"a.slang\"},{\"id\":\"a\",\"root\":\"b.slang\"}]}")) {
            assertThrows(invalid, IllegalArgumentException.class, () -> parse(invalid));
        }
    }

    @Test public void boundsSizeNestingAndVariantExpansion() {
        assertThrows(IllegalArgumentException.class, () -> parse(" ".repeat(1024 * 1024 + 1)));
        assertThrows(IllegalArgumentException.class, () -> parse("[".repeat(15) + "0" + "]".repeat(15)));
        String variants = java.util.stream.IntStream.range(0, 513).mapToObj(i -> "{\"id\":\"v" + i + "\"}").collect(Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"contexts\":[{\"id\":\"a\",\"root\":\"a.slang\",\"variants\":[" + variants + "]}]}"));
    }

    @Test public void diskErrorsFailClosedAndDoNotEraseRememberedSelection() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("variants.json");
        assertFalse(SlangVariantCatalog.read(file, workspace).present());
        Files.writeString(file, SAMPLE);
        assertEquals(2, SlangVariantCatalog.read(file, workspace).variants().size());
        Files.writeString(file, "broken JSON");
        var broken = SlangVariantCatalog.read(file, workspace);
        assertTrue(broken.present()); assertNotNull(broken.error()); assertTrue(broken.variants().isEmpty());
        var settings = new SlangProjectSettings();
        settings.setShaderVariant("file", "compute/a");
        settings.setShaderVariantsPath("build/generated.json");
        var saved = settings.getState();
        saved.shaderVariants.clear();
        assertEquals("compute/a", settings.getShaderVariant("file"));
        settings.loadState(settings.getState());
        assertEquals("compute/a", settings.getShaderVariant("file"));
        assertEquals("build/generated.json", settings.getShaderVariantsPath());
        settings.setPreprocessorContext("file", null);
        assertNull(settings.getShaderVariant("file"));
        saved.shaderVariants = null; saved.shaderVariantsPath = null;
        settings.loadState(saved);
        assertEquals("slang-variants.json", settings.getShaderVariantsPath());
    }

    @Test public void readsRealCmakeGeneratedManifestWithEscapedArguments() {
        String filename = System.getProperty("slang.test.cmakeVariants", "");
        Assume.assumeFalse("Generate the CMake export fixture to opt in", filename.isEmpty());
        var catalog = SlangVariantCatalog.read(Path.of(filename), workspace);
        assertNull(catalog.error());
        assertEquals(2, catalog.variants().size());
        var blue = catalog.find("blue/default");
        assertEquals("1", macros(blue).get("MODE"));
        assertEquals("\"quoted value\"", macros(blue).get("LABEL"));
        assertEquals("a=b", macros(blue).get("EQUATION"));
        assertEquals("a;b", macros(blue).get("SEMI"));
        assertEquals("", macros(blue).get("EMPTY"));
        assertTrue(blue.root().isAbsolute());
        assertTrue(blue.label().contains("Debug"));
        assertEquals("Debug", blue.configuration());
        assertEquals("sample_shaders", blue.buildTarget());
        var release = SlangVariantCatalog.read(Path.of(filename).resolveSibling("slang-variants-Release.json"), workspace);
        assertNull(release.error());
        assertEquals("Release", release.find("blue/default").configuration());
        assertNotEquals(blue.buildContext().fingerprint(), release.find("blue/default").buildContext().fingerprint());
    }

    @Test public void repositoryExamplesAndFixtureRemainValidText() throws Exception {
        var example = SlangVariantCatalog.read(Path.of("slang-variants.example.json"), workspace);
        assertNull(example.error()); assertEquals(3, example.variants().size());
        var metallic = SlangVariantCatalog.read(Path.of("docs/metallic-slang-variants.example.json"), workspace);
        assertNull(metallic.error()); assertEquals(5, metallic.variants().size());
        assertEquals("1", macros(metallic.find("scene-path-trace/sharc-update")).get("SHARC_UPDATE"));
        assertEquals("spirv_1_6", metallic.variants().getFirst().buildContext().profile());
        assertTrue(Files.readString(Path.of("src/test/testData/slang/PreprocessorVariant.slang")).contains("#if MODE == 1"));
    }

    private static Map<String, String> macros(SlangVariantCatalog.Variant variant) {
        return variant.buildContext().defines().stream().collect(Collectors.toMap(d -> d.name(), d -> d.value()));
    }
}
