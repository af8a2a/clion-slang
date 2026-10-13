package dev.slang.intellij.navigation;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assume;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Optional integration regression against a read-only snapshot of Metallic's actual shaders. */
public class SlangInheritanceCorpusPlatformTest extends BasePlatformTestCase {
    private record InterfaceDeclaration(PsiFile file, SlangSymbol symbol) {}

    public void testMetallicInterfaceImplementationsAndTransparentAliases() throws Exception {
        String configuredRoot = System.getProperty("slang.test.corpus");
        Assume.assumeTrue("Set -Dslang.test.corpus to Metallic/Shaders for the corpus test",
                configuredRoot != null && !configuredRoot.isBlank());
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        assertTrue("Shader corpus directory does not exist: " + root, Files.isDirectory(root));

        List<Path> paths;
        try (var files = Files.walk(root)) {
            paths = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".slang") || path.toString().endsWith(".slangh"))
                    .sorted().toList();
        }
        assertFalse("No Slang files found in " + root, paths.isEmpty());
        var interfaces = new LinkedHashMap<String, InterfaceDeclaration>();
        for (Path path : paths) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            var file = myFixture.addFileToProject(relative, Files.readString(path, StandardCharsets.UTF_8));
            for (var symbol : SlangSymbolExtractor.extract(file.getText())) {
                if (symbol.isInterface()) {
                    assertNull("Duplicate interface name needs a qualified corpus expectation: " + symbol.name(),
                            interfaces.put(symbol.name(), new InterfaceDeclaration(file, symbol)));
                }
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());

        // Query every interface through the real file indices, including ones without implementations.
        var targets = new LinkedHashMap<String, List<SlangSymbolNavigationItem>>();
        interfaces.forEach((name, declaration) -> targets.put(name,
                SlangDerivedTypes.find(getProject(), declaration.file().getVirtualFile(), declaration.symbol())
                        .stream().map(element -> (SlangSymbolNavigationItem) element).toList()));
        assertTrue("Metallic interface corpus unexpectedly shrank", interfaces.size() >= 22);

        // These explicit source relationships are independent of the extractor under test.
        // Keys are interface names; each value is source path | concrete/interface declaration name.
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("IRTXCRHairParameterProvider", List.of("Interop/RTXCRMaterialAdapter.slang|SceneRTXCRHairParameters")),
                Map.entry("IPreparedSurfaceClosure", List.of(
                        "Modules/DebugLambert.slang|DebugLambertPreparedClosure",
                        "Modules/DebugMirror.slang|DebugMirrorPreparedClosure",
                        "Modules/OpenPBRClosure.slang|OpenPBRPreparedClosure",
                        "Modules/SlabClosure.slang|SlabPreparedClosure")),
                Map.entry("ISurfaceClosure", List.of(
                        "Modules/DebugLambert.slang|DebugLambertClosure",
                        "Modules/DebugMirror.slang|DebugMirrorClosure",
                        "Modules/OpenPBRClosure.slang|OpenPBRClosure",
                        "Modules/SlabClosure.slang|SingleSlabClosure",
                        "Modules/SlabClosure.slang|DualSlabClosure")),
                Map.entry("ISurfaceMaterialProgram", List.of(
                        "Modules/DebugLambert.slang|DebugLambertMaterialProgram",
                        "Modules/DebugMirror.slang|DebugMirrorMaterialProgram",
                        "Modules/SceneSurfaceMaterial.slang|ISceneSurfaceMaterialProgram",
                        "Modules/SlabClosure.slang|SingleSlabMaterialProgram",
                        "Modules/SlabClosure.slang|DualSlabMaterialProgram")),
                Map.entry("ISurfaceLightProvider", List.of(
                        "Modules/OpenPBRDirectLighting.slang|RealtimeSurfaceLights",
                        "Modules/VisibilityBufferLighting.slang|VisibilitySurfaceLights",
                        "Modules/OpenPBRPathTracing/OpenPBRPathTrace.slang|PathSurfaceLights")),
                Map.entry("IOpenPBRContext", List.of(
                        "Modules/OpenPBRSurface.slang|SceneOpenPBRContext",
                        "Features/VisibilityBuffer/VisibilityBufferShading.slang|PreviewOpenPBRContext")),
                Map.entry("ISceneMaterialTextureSource", List.of(
                        "Modules/OpenPBRSurface.slang|IOpenPBRMaterialSource",
                        "Modules/OpenPBRSurface.slang|SceneMaterialTextureSource")),
                Map.entry("IOpenPBRMaterialSource", List.of("Modules/OpenPBRSurface.slang|SceneOpenPBRMaterialSource")),
                Map.entry("ISceneSurfaceMaterialProgram", List.of(
                        "Modules/OpenPBRSurface.slang|OpenPBRMaterialProgramImpl",
                        "Modules/OpenPBRSurface.slang|SceneSlabMaterialProgram")),
                Map.entry("ISceneSlabModel", List.of(
                        "Modules/OpenPBRSurface.slang|SceneSingleSlabModel",
                        "Modules/OpenPBRSurface.slang|SceneDualSlabModel")),
                Map.entry("IPreparedFiberClosure", List.of("Modules/RTXCRFiber.slang|RTXCRChiangPreparedClosure")),
                Map.entry("IFiberClosure", List.of("Modules/RTXCRFiber.slang|RTXCRChiangClosure")),
                Map.entry("IFiberMaterialProgram", List.of("Modules/RTXCRFiber.slang|RTXCRChiangMaterialProgram")),
                Map.entry("IScatterSink", List.of("Features/Debug/ShaderToHumanScatterExample.slang|OutputSink")),
                Map.entry("ICoverageEvaluator", List.of("Modules/Material/Coverage.slang|CoverageProgram")));
        int checkedEdges = 0;
        for (var entry : expected.entrySet()) {
            assertNotNull("Missing interface " + entry.getKey(), targets.get(entry.getKey()));
            for (String child : entry.getValue()) {
                String[] parts = child.split("\\|", 2);
                assertTrue(entry.getKey() + " must find " + child,
                        targets.get(entry.getKey()).stream().anyMatch(item -> item.getName().equals(parts[1])
                                && item.getContainingFile().getVirtualFile().getPath().endsWith("/" + parts[0])));
                checkedEdges++;
            }
        }
        assertEquals(32, checkedEdges);

        Set<String> scenePrograms = names(targets.get("ISceneSurfaceMaterialProgram"));
        var expectedPrograms = Set.of("OpenPBRMaterialProgramImpl", "OpenPBRMaterialProgram",
                "SceneOpenPBRMaterialProgram", "SceneSlabMaterialProgram",
                "SceneSingleSlabMaterialProgram", "SceneDualSlabMaterialProgram");
        assertTrue("Missing transparent generic alias chain: " + scenePrograms,
                scenePrograms.containsAll(expectedPrograms));
        assertTrue("Indirect interface inheritance must preserve the alias chain",
                names(targets.get("ISurfaceMaterialProgram")).containsAll(expectedPrograms));
        assertTrue("Indirect interface child was lost", names(targets.get("ISceneMaterialTextureSource"))
                .contains("SceneOpenPBRMaterialSource"));
        assertEquals(Set.of("OutputSink"), names(targets.get("IScatterSink")));

        // A generic constraint and a constrained extension do not make the enclosing type implement it.
        var forbiddenConstraints = List.of("ContextScatter", "SurfaceMaterialResult", "SurfaceShadingResult",
                "FiberMaterialResult", "FiberShadingResult", "Material");
        var foundConstraints = new ArrayList<String>();
        targets.forEach((name, items) -> items.stream().filter(item -> forbiddenConstraints.contains(item.getName()))
                .forEach(item -> foundConstraints.add(name + " -> " + item.getName())));
        assertTrue("Generic constraints were mistaken for inheritance: " + foundConstraints, foundConstraints.isEmpty());
        System.out.printf("METALLIC_INHERITANCE_CORPUS files=%d interfaces=%d explicitEdges=%d sceneProgramTargets=%s%n",
                paths.size(), interfaces.size(), checkedEdges, scenePrograms.stream().sorted().toList());
        targets.forEach((name, items) -> System.out.println("CORPUS_TARGETS " + name + " = "
                + items.stream().map(item -> item.qualifiedName() + (item.isTypeAlias() ? " [alias]" : ""))
                .sorted().toList()));
    }

    private static Set<String> names(List<SlangSymbolNavigationItem> items) {
        return items.stream().map(SlangSymbolNavigationItem::getName).collect(Collectors.toSet());
    }
}
