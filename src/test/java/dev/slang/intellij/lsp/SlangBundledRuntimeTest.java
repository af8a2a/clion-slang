package dev.slang.intellij.lsp;

import com.intellij.execution.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SlangBundledRuntimeTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsVerifiedRuntimeIntoContentAddressedCache() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        AtomicInteger opens = new AtomicInteger();
        SlangBundledRuntime runtime = runtime(archive, cache, opens);

        Path executable = runtime.resolveExecutable();

        assertEquals("slangd.exe", executable.getFileName().toString());
        assertEquals(sha256(archive), executable.getParent().getFileName().toString());
        assertArrayEquals(files.get("slangd.exe"), Files.readAllBytes(executable));
        assertArrayEquals(
                files.get("slang-compiler.dll"),
                Files.readAllBytes(executable.getParent().resolve("slang-compiler.dll"))
        );
        assertArrayEquals(
                files.get("slang-glsl-module.bin"),
                Files.readAllBytes(executable.getParent().resolve("slang-glsl-module.bin"))
        );
        assertEquals(
                moduleTimestamp(),
                Files.getLastModifiedTime(executable.getParent().resolve("slang-compiler.dll"))
                        .to(TimeUnit.SECONDS)
        );
        assertTrue(Files.isRegularFile(executable.getParent().resolve("manifest.json")));
        assertEquals(executable, runtime.resolveExecutable());
        assertEquals(executable, runtime.resolve());
        SlangBundledRuntime.RuntimeInfo info = runtime.describe();
        assertEquals(sha256(archive), info.bundleId());
        assertEquals("clion-slang-m3", info.profile());
        assertEquals("test-source", info.sourceDescribe());
        assertEquals("windows-x64", info.platform());
        assertEquals(info.bundleId(), runtime.getBundleId());
        assertEquals("A resolved manager should not reopen its embedded archive", 1, opens.get());
    }

    @Test
    public void installsTheActualPackagedRuntimeResource() throws Exception {
        SlangBundledRuntime runtime = new SlangBundledRuntime(
                () -> SlangBundledRuntime.class.getResourceAsStream(SlangBundledRuntime.RESOURCE_PATH),
                temporaryFolder.newFolder("packaged-cache").toPath(),
                "Windows 11",
                "amd64"
        );

        Path executable = runtime.resolveExecutable();
        SlangBundledRuntime.RuntimeInfo info = runtime.describe();

        assertTrue(Files.size(executable) > 0);
        assertTrue(Files.isRegularFile(executable.getParent().resolve("slang-compiler.dll")));
        assertTrue(Files.isRegularFile(executable.getParent().resolve("slang-glsl-module.bin")));
        assertTrue(Files.isRegularFile(executable.getParent().resolve("0003-field-layout-hover.patch")));
        assertTrue(Files.isRegularFile(executable.getParent().resolve("0004-field-hover-presentation.patch")));
        assertTrue(Files.isRegularFile(executable.getParent().resolve("0005-document-local-references.patch")));
        assertTrue(Files.isRegularFile(executable.getParent().resolve("0006-document-variable-highlights.patch")));
        assertEquals("clion-slang-m3", info.profile());
        assertEquals("windows-x64", info.platform());
        assertEquals(64, info.bundleId().length());
    }

    @Test
    public void reusesAndRevalidatesAnExistingInstallation() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        Path first = runtime(archive, cache, new AtomicInteger()).resolveExecutable();

        Path second = runtime(archive, cache, new AtomicInteger()).resolveExecutable();

        assertEquals(first, second);
        assertArrayEquals(files.get("slangd.exe"), Files.readAllBytes(second));
    }

    @Test
    public void repairsTamperedCachedRuntime() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        SlangBundledRuntime runtime = runtime(archive, cache, new AtomicInteger());
        Path executable = runtime.resolveExecutable();
        Files.writeString(executable, "tampered", StandardCharsets.UTF_8);

        Path repaired = runtime.resolveExecutable();

        assertEquals(executable, repaired);
        assertArrayEquals(files.get("slangd.exe"), Files.readAllBytes(repaired));
    }

    @Test
    public void sameManagerRepairsExecutableRemovedAfterResolution() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        SlangBundledRuntime runtime = runtime(archive, cache, new AtomicInteger());
        Path executable = runtime.resolveExecutable();
        Files.delete(executable);

        Path repaired = runtime.resolveExecutable();

        assertEquals(executable, repaired);
        assertArrayEquals(files.get("slangd.exe"), Files.readAllBytes(repaired));
    }

    @Test
    public void sameManagerRevalidatesAndRepairsDeclaredDependencies() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        SlangBundledRuntime runtime = runtime(archive, cache, new AtomicInteger());
        Path executable = runtime.resolveExecutable();
        Path compiler = executable.getParent().resolve("slang-compiler.dll");
        Files.writeString(compiler, "tampered compiler", StandardCharsets.UTF_8);

        Path repaired = runtime.resolveExecutable();

        assertEquals(executable, repaired);
        assertArrayEquals(files.get("slang-compiler.dll"), Files.readAllBytes(compiler));
        assertEquals(moduleTimestamp(), Files.getLastModifiedTime(compiler).to(TimeUnit.SECONDS));
    }

    @Test
    public void undeclaredCachedFileCausesCleanReinstallation() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();
        Path executable = runtime(archive, cache, new AtomicInteger()).resolveExecutable();
        Path injected = Files.writeString(
                executable.getParent().resolve("injected.dll"),
                "not part of the bundle",
                StandardCharsets.UTF_8
        );

        Path repaired = runtime(archive, cache, new AtomicInteger()).resolveExecutable();

        assertEquals(executable, repaired);
        assertFalse(Files.exists(injected));
    }

    @Test
    public void rejectsHashMismatchWithoutPublishingPartialDirectory() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        Map<String, String> hashes = new LinkedHashMap<>(hashes(files));
        hashes.put("slangd.exe", "0".repeat(64));
        byte[] archive = bundle(1, "windows-x64", hashes, files, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(archive, cache, new AtomicInteger()).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("SHA-256 mismatch for bundled runtime file slangd.exe"));
        Path platformRoot = cache.resolve("slang/runtime/windows-x64");
        if (Files.exists(platformRoot)) {
            assertFalse(Files.exists(platformRoot.resolve(sha256(archive))));
            try (var children = Files.list(platformRoot)) {
                assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".install-")));
            }
        }
    }

    @Test
    public void rejectsZipSlipAndLeavesOutsidePathUntouched() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        Map<String, String> hashes = new LinkedHashMap<>(hashes(files));
        hashes.put("../escape.dll", sha256("escape".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> archiveFiles = new LinkedHashMap<>(files);
        archiveFiles.put("../escape.dll", "escape".getBytes(StandardCharsets.UTF_8));
        byte[] archive = bundle(1, "windows-x64", hashes, archiveFiles, Map.of());
        Path cache = temporaryFolder.newFolder("cache").toPath();

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(archive, cache, new AtomicInteger()).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("unsafe path"));
        assertFalse(Files.exists(cache.resolve("escape.dll")));
    }

    @Test
    public void rejectsWindowsAbsoluteAndAlternateStreamPaths() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        Map<String, byte[]> extras = Map.of("C:/escape.dll", new byte[]{1});
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, extras);
        Path cache = temporaryFolder.newFolder("cache").toPath();

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(archive, cache, new AtomicInteger()).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("absolute or unsafe path"));
    }

    @Test
    public void rejectsUndeclaredArchiveFiles() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundle(
                1,
                "windows-x64",
                hashes(files),
                files,
                Map.of("surprise.dll", new byte[]{1, 2, 3})
        );

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        archive,
                        temporaryFolder.newFolder("cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("undeclared runtime file: surprise.dll"));
    }

    @Test
    public void validatesManifestSchemaAndPlatform() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] wrongSchema = bundle(2, "windows-x64", hashes(files), files, Map.of());
        byte[] wrongPlatform = bundle(1, "linux-x64", hashes(files), files, Map.of());

        ExecutionException schemaException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        wrongSchema,
                        temporaryFolder.newFolder("schema-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException platformException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        wrongPlatform,
                        temporaryFolder.newFolder("platform-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );

        assertTrue(schemaException.getMessage().contains("Unsupported runtime manifest schemaVersion 2"));
        assertTrue(platformException.getMessage().contains("targets linux-x64"));
    }

    @Test
    public void validatesProtocolVersionAndRequiredFeatures() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        Map<String, String> hashes = hashes(files);
        String[] features = new String[]{
                "semanticTokens.m2a",
                "semanticTokens.m3",
                "hover.fieldLayout.natural",
                "references.documentLocal",
                "documentHighlight.documentLocal"
        };
        byte[] wrongMajor = bundleWithProtocol(
                1, "windows-x64", 2, 4, features, hashes, files, Map.of()
        );
        byte[] wrongMinor = bundleWithProtocol(
                1, "windows-x64", 1, 1, features, hashes, files, Map.of()
        );
        byte[] missingFeature = bundleWithProtocol(
                1,
                "windows-x64",
                1,
                4,
                new String[]{
                        "semanticTokens.m2a",
                        "semanticTokens.m3",
                        "hover.fieldLayout.natural",
                        "references.documentLocal"
                },
                hashes,
                files,
                Map.of()
        );
        byte[] unexpectedFeature = bundleWithProtocol(
                1,
                "windows-x64",
                1,
                4,
                new String[]{
                        "semanticTokens.m2a",
                        "semanticTokens.m3",
                        "hover.fieldLayout.natural",
                        "references.documentLocal",
                        "documentHighlight.documentLocal",
                        "other.feature"
                },
                hashes,
                files,
                Map.of()
        );
        byte[] reversedFeatures = bundleWithProtocol(
                1,
                "windows-x64",
                1,
                4,
                new String[]{
                        "semanticTokens.m3",
                        "semanticTokens.m2a",
                        "hover.fieldLayout.natural",
                        "references.documentLocal",
                        "documentHighlight.documentLocal"
                },
                hashes,
                files,
                Map.of()
        );
        byte[] duplicateFeatures = bundleWithProtocol(
                1,
                "windows-x64",
                1,
                4,
                new String[]{
                        "semanticTokens.m2a",
                        "semanticTokens.m3",
                        "hover.fieldLayout.natural",
                        "references.documentLocal",
                        "documentHighlight.documentLocal",
                        "references.documentLocal"
                },
                hashes,
                files,
                Map.of()
        );

        ExecutionException majorException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        wrongMajor,
                        temporaryFolder.newFolder("major-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException minorException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        wrongMinor,
                        temporaryFolder.newFolder("minor-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException featureException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        missingFeature,
                        temporaryFolder.newFolder("feature-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException unexpectedFeatureException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        unexpectedFeature,
                        temporaryFolder.newFolder("unexpected-feature-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException reversedFeaturesException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        reversedFeatures,
                        temporaryFolder.newFolder("reversed-features-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );
        ExecutionException duplicateFeaturesException = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        duplicateFeatures,
                        temporaryFolder.newFolder("duplicate-features-cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );

        assertTrue(majorException.getMessage().contains("protocol 2.4"));
        assertTrue(minorException.getMessage().contains("protocol 1.1"));
        assertTrue(featureException.getMessage().contains("documentHighlight.documentLocal"));
        assertTrue(unexpectedFeatureException.getMessage().contains("other.feature"));
        assertTrue(reversedFeaturesException.getMessage().contains("instead of required"));
        assertTrue(duplicateFeaturesException.getMessage().contains("must not contain duplicates"));
    }

    @Test
    public void rejectsRuntimeFromAnotherProfile() throws Exception {
        Map<String, byte[]> files = runtimeFiles();
        byte[] archive = bundleWithProfileAndProtocol(
                1,
                "windows-x64",
                "another-client",
                1,
                4,
                new String[]{
                        "semanticTokens.m2a",
                        "semanticTokens.m3",
                        "hover.fieldLayout.natural",
                        "references.documentLocal",
                        "documentHighlight.documentLocal"
                },
                hashes(files),
                files,
                Map.of()
        );

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        archive,
                        temporaryFolder.newFolder("cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("another-client"));
        assertTrue(exception.getMessage().contains("clion-slang-m3"));
    }

    @Test
    public void requiresCompleteRuntimeFileSet() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>(runtimeFiles());
        files.remove("slang-glsl-module.bin");
        byte[] archive = bundle(1, "windows-x64", hashes(files), files, Map.of());

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> runtime(
                        archive,
                        temporaryFolder.newFolder("cache").toPath(),
                        new AtomicInteger()
                ).resolveExecutable()
        );

        assertTrue(exception.getMessage().contains("does not declare slang-glsl-module.bin"));
    }

    @Test
    public void failsBeforeReadingBundleOnUnsupportedHost() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        SlangBundledRuntime runtime = new SlangBundledRuntime(
                () -> {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(new byte[0]);
                },
                temporaryFolder.newFolder("cache").toPath(),
                "Linux",
                "aarch64"
        );

        ExecutionException exception = assertThrows(ExecutionException.class, runtime::resolveExecutable);

        assertTrue(exception.getMessage().contains("supports only Windows x64"));
        assertTrue(exception.getMessage().contains("Linux / aarch64"));
        assertEquals(0, opens.get());
    }

    @Test
    public void reportsMissingEmbeddedResource() throws Exception {
        SlangBundledRuntime runtime = new SlangBundledRuntime(
                () -> null,
                temporaryFolder.newFolder("cache").toPath(),
                "Windows 11",
                "amd64"
        );

        ExecutionException exception = assertThrows(ExecutionException.class, runtime::resolveExecutable);

        assertTrue(exception.getMessage().contains(SlangBundledRuntime.RESOURCE_PATH));
        assertTrue(exception.getMessage().contains("is missing"));
    }

    private static SlangBundledRuntime runtime(byte[] archive, Path cache, AtomicInteger opens) {
        return new SlangBundledRuntime(
                () -> {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(archive);
                },
                cache,
                "Windows 11",
                "amd64"
        );
    }

    private static Map<String, byte[]> runtimeFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("slangd.exe", "test-slangd".getBytes(StandardCharsets.UTF_8));
        files.put("slang-compiler.dll", "test-compiler".getBytes(StandardCharsets.UTF_8));
        ByteBuffer module = ByteBuffer.allocate(Long.BYTES + 11).order(ByteOrder.LITTLE_ENDIAN);
        module.putLong(moduleTimestamp());
        module.put("test-module".getBytes(StandardCharsets.UTF_8));
        files.put("slang-glsl-module.bin", module.array());
        return files;
    }

    private static long moduleTimestamp() {
        return 1_788_008_406L;
    }

    private static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        return hashes;
    }

    private static byte[] bundle(
            int schemaVersion,
            String platform,
            Map<String, String> hashes,
            Map<String, byte[]> files,
            Map<String, byte[]> extras
    ) throws IOException {
        return bundleWithProtocol(
                schemaVersion,
                platform,
                1,
                4,
                new String[]{
                        "semanticTokens.m2a",
                        "semanticTokens.m3",
                        "hover.fieldLayout.natural",
                        "references.documentLocal",
                        "documentHighlight.documentLocal"
                },
                hashes,
                files,
                extras
        );
    }

    private static byte[] bundleWithProtocol(
            int schemaVersion,
            String platform,
            int protocolMajor,
            int protocolMinor,
            String[] protocolFeatures,
            Map<String, String> hashes,
            Map<String, byte[]> files,
            Map<String, byte[]> extras
    ) throws IOException {
        return bundleWithProfileAndProtocol(
                schemaVersion,
                platform,
                "clion-slang-m3",
                protocolMajor,
                protocolMinor,
                protocolFeatures,
                hashes,
                files,
                extras
        );
    }

    private static byte[] bundleWithProfileAndProtocol(
            int schemaVersion,
            String platform,
            String profile,
            int protocolMajor,
            int protocolMinor,
            String[] protocolFeatures,
            Map<String, String> hashes,
            Map<String, byte[]> files,
            Map<String, byte[]> extras
    ) throws IOException {
        StringBuilder manifest = new StringBuilder()
                .append("{\"schemaVersion\":").append(schemaVersion)
                .append(",\"profile\":\"").append(profile).append('\"')
                .append(",\"source\":{\"describe\":\"test-source\"}")
                .append(",\"build\":{\"platform\":\"").append(platform)
                .append("\"},\"protocol\":{\"major\":").append(protocolMajor)
                .append(",\"minor\":").append(protocolMinor)
                .append(",\"features\":[");
        for (int index = 0; index < protocolFeatures.length; index++) {
            if (index != 0) {
                manifest.append(',');
            }
            manifest.append('\"').append(protocolFeatures[index]).append('\"');
        }
        manifest.append("]},\"files\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (!first) {
                manifest.append(',');
            }
            first = false;
            manifest.append('\"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('\"');
        }
        manifest.append("}}");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : extras.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
