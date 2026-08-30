package dev.slang.intellij.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs and resolves the slangd runtime shipped inside the plugin. */
public final class SlangBundledRuntime {
    static final String RESOURCE_PATH = "/slang/runtime/windows-x86_64.zip";
    static final String PLATFORM = "windows-x64";

    private static final String MANIFEST_NAME = "manifest.json";
    private static final String EXECUTABLE_NAME = "slangd.exe";
    private static final String COMPILER_NAME = "slang-compiler.dll";
    private static final String GLSL_MODULE_NAME = "slang-glsl-module.bin";
    private static final String REQUIRED_PROFILE = "clion-slang-m3";
    private static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final int PROTOCOL_MAJOR = 1;
    private static final int PROTOCOL_MINOR = 2;
    private static final List<String> REQUIRED_PROTOCOL_FEATURES = List.of(
            "semanticTokens.m2a",
            "semanticTokens.m3",
            "hover.fieldLayout.natural"
    );
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024;
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    private static final ConcurrentMap<String, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

    private final InputStreamSupplier resourceSupplier;
    private final Path cacheRoot;
    private final String osName;
    private final String osArch;
    private RuntimeInfo runtimeInfo;
    private Manifest cachedManifest;
    private String cachedBundleId;

    public SlangBundledRuntime() {
        this(
                () -> SlangBundledRuntime.class.getResourceAsStream(RESOURCE_PATH),
                Path.of(PathManager.getSystemPath()),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown")
        );
    }

    SlangBundledRuntime(
            @NotNull InputStreamSupplier resourceSupplier,
            @NotNull Path cacheRoot,
            @NotNull String osName,
            @NotNull String osArch
    ) {
        this.resourceSupplier = resourceSupplier;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.osName = osName;
        this.osArch = osArch;
    }

    /** Returns a verified executable, installing the embedded bundle atomically when necessary. */
    public synchronized @NotNull Path resolveExecutable() throws ExecutionException {
        requireSupportedPlatform();

        try {
            byte[] archive = null;
            Manifest manifest = cachedManifest;
            String bundleId = cachedBundleId;
            if (manifest == null || bundleId == null) {
                archive = readArchive();
                manifest = readManifest(archive);
                bundleId = sha256(archive);
                cachedManifest = manifest;
                cachedBundleId = bundleId;
            }
            Path platformRoot = cacheRoot.resolve("slang").resolve("runtime").resolve(PLATFORM);
            Path installRoot = platformRoot.resolve(bundleId);

            ensureDirectoryWithoutSymbolicLinks(platformRoot);
            ensureInstalledRuntime(archive, manifest, bundleId, platformRoot, installRoot);

            Path executable = installRoot.resolve(EXECUTABLE_NAME);
            verifyRegularFile(executable, "Bundled slangd executable");
            runtimeInfo = new RuntimeInfo(
                    bundleId,
                    manifest.profile,
                    manifest.sourceDescribe,
                    manifest.platform
            );
            return executable;
        } catch (IOException | JsonParseException exception) {
            throw new ExecutionException("Cannot install bundled slangd: " + exception.getMessage(), exception);
        }
    }

    /** Compatibility alias for callers that treat the runtime as an executable locator. */
    public @NotNull Path resolve() throws ExecutionException {
        return resolveExecutable();
    }

    /** Returns immutable display metadata for the verified embedded bundle. */
    public synchronized @NotNull RuntimeInfo describe() throws ExecutionException {
        resolveExecutable();
        return runtimeInfo;
    }

    /** Returns the content-addressed bundle identifier (the embedded ZIP's SHA-256). */
    public synchronized @NotNull String getBundleId() throws ExecutionException {
        return describe().bundleId();
    }

    private void requireSupportedPlatform() throws ExecutionException {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        String normalizedArch = osArch.toLowerCase(Locale.ROOT);
        boolean windows = normalizedOs.startsWith("windows");
        boolean x64 = normalizedArch.equals("amd64") || normalizedArch.equals("x86_64");
        if (!windows || !x64) {
            throw new ExecutionException(
                    "The bundled slangd runtime supports only Windows x64; current platform is "
                            + osName + " / " + osArch + "."
            );
        }
    }

    private byte @NotNull [] readArchive() throws IOException {
        try (InputStream input = resourceSupplier.open()) {
            if (input == null) {
                throw new IOException("Plugin resource " + RESOURCE_PATH + " is missing");
            }
            return readLimited(input, MAX_ARCHIVE_BYTES, "Bundled runtime archive");
        }
    }

    private static @NotNull Manifest readManifest(byte @NotNull [] archive) throws IOException {
        byte[] manifestBytes = null;
        Set<String> names = new HashSet<>();
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("Bundled runtime archive contains too many entries");
                }
                String name = validateArchivePath(entry.getName(), entry.isDirectory());
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate archive path: " + name);
                }
                if (!entry.isDirectory() && name.equals(MANIFEST_NAME)) {
                    manifestBytes = readLimited(zip, MAX_MANIFEST_BYTES, "Runtime manifest");
                    totalBytes += manifestBytes.length;
                } else if (!entry.isDirectory()) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Bundled runtime archive exceeds the extraction size limit");
                        }
                    }
                }
                if (totalBytes > MAX_EXTRACTED_BYTES) {
                    throw new IOException("Bundled runtime archive exceeds the extraction size limit");
                }
                zip.closeEntry();
            }
        }
        if (manifestBytes == null) {
            throw new IOException("Bundled runtime archive does not contain " + MANIFEST_NAME);
        }
        return parseManifest(manifestBytes);
    }

    private static @NotNull Manifest parseManifest(byte @NotNull [] bytes) throws IOException {
        JsonElement rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!rootElement.isJsonObject()) {
            throw new IOException("Runtime manifest must be a JSON object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        int schemaVersion = requireInteger(root, "schemaVersion");
        if (schemaVersion != MANIFEST_SCHEMA_VERSION) {
            throw new IOException(
                    "Unsupported runtime manifest schemaVersion " + schemaVersion
                            + " (expected " + MANIFEST_SCHEMA_VERSION + ")"
            );
        }

        JsonObject build = requireObject(root, "build");
        String platform = requireString(build, "platform");
        if (!PLATFORM.equals(platform)) {
            throw new IOException(
                    "Bundled runtime targets " + platform + " instead of required " + PLATFORM
            );
        }

        JsonObject protocol = requireObject(root, "protocol");
        int protocolMajor = requireInteger(protocol, "major");
        int protocolMinor = requireInteger(protocol, "minor");
        if (protocolMajor != PROTOCOL_MAJOR || protocolMinor != PROTOCOL_MINOR) {
            throw new IOException(
                    "Unsupported bundled runtime protocol " + protocolMajor + "." + protocolMinor
                            + " (expected " + PROTOCOL_MAJOR + "." + PROTOCOL_MINOR + ")"
            );
        }
        JsonArray features = requireArray(protocol, "features");
        List<String> protocolFeatures = new ArrayList<>();
        for (JsonElement feature : features) {
            if (!feature.isJsonPrimitive() || !feature.getAsJsonPrimitive().isString()) {
                throw new IOException("Runtime manifest protocol features must contain only strings");
            }
            String featureName = feature.getAsString();
            if (protocolFeatures.contains(featureName)) {
                throw new IOException("Runtime manifest protocol features must not contain duplicates");
            }
            protocolFeatures.add(featureName);
        }
        if (!protocolFeatures.equals(REQUIRED_PROTOCOL_FEATURES)) {
            throw new IOException(
                    "Runtime manifest protocol features are " + protocolFeatures
                            + " instead of required " + REQUIRED_PROTOCOL_FEATURES
            );
        }

        JsonObject filesObject = requireObject(root, "files");
        if (filesObject.size() == 0) {
            throw new IOException("Runtime manifest files map must not be empty");
        }
        Map<String, String> files = new LinkedHashMap<>();
        Set<String> canonicalNames = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : filesObject.entrySet()) {
            String name = validateArchivePath(entry.getKey(), false);
            if (name.equals(MANIFEST_NAME)) {
                throw new IOException(MANIFEST_NAME + " must not list itself in the files map");
            }
            if (!canonicalNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Runtime manifest contains a case-insensitive duplicate path: " + name);
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IOException("Runtime manifest hash for " + name + " must be a string");
            }
            String hash = entry.getValue().getAsString().toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Runtime manifest hash for " + name + " is not a SHA-256 digest");
            }
            files.put(name, hash);
        }
        for (String requiredFile : new String[]{EXECUTABLE_NAME, COMPILER_NAME, GLSL_MODULE_NAME}) {
            if (!files.containsKey(requiredFile)) {
                throw new IOException("Runtime manifest does not declare " + requiredFile);
            }
        }
        String profile = requireString(root, "profile");
        if (!REQUIRED_PROFILE.equals(profile)) {
            throw new IOException(
                    "Bundled runtime profile is " + profile + " instead of required " + REQUIRED_PROFILE
            );
        }
        JsonElement sourceElement = root.get("source");
        String sourceDescribe = "unknown";
        if (sourceElement != null) {
            if (!sourceElement.isJsonObject()) {
                throw new IOException("Runtime manifest field source must be an object");
            }
            sourceDescribe = optionalString(sourceElement.getAsJsonObject(), "describe", "unknown");
        }
        return new Manifest(files, profile, sourceDescribe, platform);
    }

    private static int requireInteger(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Runtime manifest field " + name + " must be an integer");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("Runtime manifest field " + name + " must be an integer", exception);
        }
    }

    private static @NotNull JsonObject requireObject(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Runtime manifest field " + name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static @NotNull JsonArray requireArray(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Runtime manifest field " + name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static @NotNull String requireString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Runtime manifest field " + name + " must be a string");
        }
        return value.getAsString();
    }

    private static @NotNull String optionalString(JsonObject object, String name, String fallback)
            throws IOException {
        JsonElement value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Runtime manifest field " + name + " must be a string");
        }
        String result = value.getAsString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private void ensureInstalledRuntime(
            byte[] archive,
            @NotNull Manifest manifest,
            @NotNull String bundleId,
            @NotNull Path platformRoot,
            @NotNull Path installRoot
    ) throws IOException {
        Object jvmLock = INSTALL_LOCKS.computeIfAbsent(installRoot.toString(), ignored -> new Object());
        synchronized (jvmLock) {
            Path lockPath = platformRoot.resolve(bundleId + ".lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(lockPath)
                    || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Bundled runtime installation lock is not a regular file: " + lockPath);
            }

            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                IOException invalidCache = null;
                byte[] installArchive = archive;
                if (Files.exists(installRoot, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        prepareInstalledRuntime(installRoot, manifest);
                        return;
                    } catch (IOException exception) {
                        invalidCache = exception;
                        installArchive = loadArchiveForRepair(installArchive, bundleId);
                        try {
                            deleteOwnedInstallation(platformRoot, installRoot, bundleId);
                        } catch (IOException deleteException) {
                            deleteException.addSuppressed(exception);
                            throw deleteException;
                        }
                    }
                }

                try {
                    installArchive = loadArchiveForRepair(installArchive, bundleId);
                    installAtomically(installArchive, manifest, platformRoot, installRoot);
                } catch (IOException exception) {
                    if (invalidCache != null) {
                        exception.addSuppressed(invalidCache);
                    }
                    throw exception;
                }
            }
        }
    }

    private byte @NotNull [] loadArchiveForRepair(byte[] archive, @NotNull String bundleId)
            throws IOException {
        byte[] result = archive == null ? readArchive() : archive;
        String actualBundleId = sha256(result);
        if (!actualBundleId.equals(bundleId)) {
            throw new IOException("Embedded runtime changed while repairing bundle " + bundleId);
        }
        return result;
    }

    private static void deleteOwnedInstallation(
            @NotNull Path platformRoot,
            @NotNull Path installRoot,
            @NotNull String bundleId
    ) throws IOException {
        Path expected = platformRoot.resolve(bundleId).normalize();
        if (!installRoot.normalize().equals(expected)
                || installRoot.getParent() == null
                || !installRoot.getParent().normalize().equals(platformRoot.normalize())
                || !bundleId.matches("[0-9a-f]{64}")) {
            throw new IOException("Refusing to remove runtime cache outside the owned bundle path: " + installRoot);
        }
        deleteTree(installRoot);
    }

    private static void installAtomically(
            byte @NotNull [] archive,
            @NotNull Manifest manifest,
            @NotNull Path platformRoot,
            @NotNull Path installRoot
    ) throws IOException {
        Path staging = Files.createDirectory(platformRoot.resolve(".install-" + UUID.randomUUID()));
        boolean moved = false;
        try {
            extract(archive, manifest, staging);
            prepareInstalledRuntime(staging, manifest);
            try {
                Files.move(staging, installRoot, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (AtomicMoveNotSupportedException exception) {
                // Both paths are children of the same cache directory. A same-volume move still
                // publishes the fully verified directory in one step on supported Windows filesystems.
                try {
                    Files.move(staging, installRoot);
                    moved = true;
                } catch (IOException moveException) {
                    acceptConcurrentInstallation(installRoot, manifest, moveException);
                }
            } catch (IOException exception) {
                // Another IDE process won the installation race. Its result must still pass the
                // complete manifest verification before it can be used.
                acceptConcurrentInstallation(installRoot, manifest, exception);
            }
        } finally {
            if (!moved && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(staging);
            }
        }
    }

    private static void acceptConcurrentInstallation(
            @NotNull Path installRoot,
            @NotNull Manifest manifest,
            @NotNull IOException moveException
    ) throws IOException {
        if (!Files.exists(installRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw moveException;
        }
        prepareInstalledRuntime(installRoot, manifest);
    }

    private static void extract(
            byte @NotNull [] archive,
            @NotNull Manifest manifest,
            @NotNull Path staging
    ) throws IOException {
        Set<String> extracted = new HashSet<>();
        Set<String> archiveNames = new HashSet<>();
        long totalBytes = 0;
        int entryCount = 0;
        byte[] buffer = new byte[64 * 1024];

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("Bundled runtime archive contains too many entries");
                }
                String name = validateArchivePath(entry.getName(), entry.isDirectory());
                if (!archiveNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate archive path: " + name);
                }
                if (entry.isDirectory()) {
                    Path directory = safeResolve(staging, name);
                    createDirectoriesWithoutSymbolicLinks(staging, directory);
                    zip.closeEntry();
                    continue;
                }
                if (!name.equals(MANIFEST_NAME) && !manifest.files.containsKey(name)) {
                    throw new IOException("Archive contains undeclared runtime file: " + name);
                }

                Path output = safeResolve(staging, name);
                Path parent = output.getParent();
                if (parent == null) {
                    throw new IOException("Archive path has no parent: " + name);
                }
                createDirectoriesWithoutSymbolicLinks(staging, parent);

                MessageDigest digest = newSha256();
                long entryBytes = 0;
                // ZipInputStream does not restore Unix mode bits. Even if a producer marks an
                // entry as a symbolic link, CREATE_NEW materializes only its bytes as a regular
                // file, so an archive cannot introduce a link that escapes the staging root.
                try (OutputStream outputStream = Files.newOutputStream(
                        output,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                )) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > MAX_EXTRACTED_BYTES || totalBytes > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Bundled runtime archive exceeds the extraction size limit");
                        }
                        outputStream.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                    }
                }

                if (!name.equals(MANIFEST_NAME)) {
                    String expected = manifest.files.get(name);
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!actual.equals(expected)) {
                        throw new IOException("SHA-256 mismatch for bundled runtime file " + name);
                    }
                    extracted.add(name);
                }
                zip.closeEntry();
            }
        }

        if (!extracted.equals(manifest.files.keySet())) {
            Set<String> missing = new HashSet<>(manifest.files.keySet());
            missing.removeAll(extracted);
            throw new IOException("Archive is missing declared runtime files: " + String.join(", ", missing));
        }
    }

    private static void prepareInstalledRuntime(@NotNull Path root, @NotNull Manifest manifest) throws IOException {
        verifyInstalledRuntime(root, manifest);
        alignCompilerTimestampWithModuleCache(root);
    }

    private static void verifyInstalledRuntime(@NotNull Path root, @NotNull Manifest manifest) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bundled runtime cache entry is not a regular directory: " + root);
        }
        verifyRegularFile(root.resolve(MANIFEST_NAME), "Bundled runtime manifest");
        for (Map.Entry<String, String> entry : manifest.files.entrySet()) {
            Path file = safeResolve(root, entry.getKey());
            verifyNoSymbolicLinkComponents(root, file.getParent());
            verifyRegularFile(file, "Bundled runtime file " + entry.getKey());
            String actual = sha256(file);
            if (!actual.equals(entry.getValue())) {
                throw new IOException("SHA-256 mismatch for cached runtime file " + entry.getKey());
            }
        }
        verifyNoUnexpectedRuntimeFiles(root, manifest);
    }

    private static void verifyNoUnexpectedRuntimeFiles(@NotNull Path root, @NotNull Manifest manifest)
            throws IOException {
        Set<Path> allowedFiles = new HashSet<>();
        allowedFiles.add(root.resolve(MANIFEST_NAME).normalize());
        for (String name : manifest.files.keySet()) {
            allowedFiles.add(safeResolve(root, name));
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path normalized = file.normalize();
                if (!allowedFiles.contains(normalized)) {
                    throw new IOException("Bundled runtime cache contains an undeclared file: " + file);
                }
                if (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Bundled runtime cache contains a non-regular file: " + file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void alignCompilerTimestampWithModuleCache(@NotNull Path root) throws IOException {
        Path moduleCache = root.resolve(GLSL_MODULE_NAME);
        byte[] timestampBytes;
        try (InputStream input = Files.newInputStream(moduleCache)) {
            timestampBytes = input.readNBytes(Long.BYTES);
        }
        if (timestampBytes.length != Long.BYTES) {
            throw new IOException(GLSL_MODULE_NAME + " does not contain its compiler timestamp");
        }
        long compilerTimestamp = ByteBuffer.wrap(timestampBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
        if (compilerTimestamp <= 0) {
            throw new IOException(GLSL_MODULE_NAME + " contains an invalid compiler timestamp");
        }

        Path compiler = root.resolve(COMPILER_NAME);
        Files.setLastModifiedTime(compiler, FileTime.from(compilerTimestamp, TimeUnit.SECONDS));
        long installedTimestamp = Files.getLastModifiedTime(compiler, LinkOption.NOFOLLOW_LINKS)
                .to(TimeUnit.SECONDS);
        if (installedTimestamp != compilerTimestamp) {
            throw new IOException("Cannot preserve the compiler timestamp required by " + GLSL_MODULE_NAME);
        }
    }

    private static void verifyNoSymbolicLinkComponents(@NotNull Path root, Path end) throws IOException {
        if (end == null || !end.startsWith(root)) {
            throw new IOException("Bundled runtime file escapes its cache root: " + end);
        }
        Path current = root;
        for (Path part : root.relativize(end)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Bundled runtime path contains a symbolic link: " + current);
            }
        }
    }

    private static void verifyRegularFile(@NotNull Path file, @NotNull String description) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is missing or is not a regular file: " + file);
        }
    }

    private static @NotNull Path safeResolve(@NotNull Path root, @NotNull String name) throws IOException {
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Archive path escapes the runtime root: " + name);
        }
        return resolved;
    }

    private static @NotNull String validateArchivePath(String rawName, boolean directory) throws IOException {
        if (rawName == null || rawName.isEmpty() || rawName.indexOf('\0') >= 0) {
            throw new IOException("Runtime archive contains an empty or invalid path");
        }
        if (rawName.indexOf('\\') >= 0 || rawName.indexOf(':') >= 0 || rawName.startsWith("/")) {
            throw new IOException("Runtime archive contains an absolute or unsafe path: " + rawName);
        }
        String name = directory && rawName.endsWith("/")
                ? rawName.substring(0, rawName.length() - 1)
                : rawName;
        if (name.isEmpty() || (!directory && name.endsWith("/"))) {
            throw new IOException("Runtime archive contains an invalid path: " + rawName);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IOException("Runtime archive contains an unsafe path: " + rawName);
            }
        }
        return name;
    }

    private static void ensureDirectoryWithoutSymbolicLinks(@NotNull Path directory) throws IOException {
        Files.createDirectories(directory);
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current == null ? part : current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Runtime cache path contains a symbolic link: " + current);
            }
        }
    }

    private static void createDirectoriesWithoutSymbolicLinks(@NotNull Path root, @NotNull Path directory)
            throws IOException {
        if (!directory.startsWith(root)) {
            throw new IOException("Runtime extraction directory escapes staging root: " + directory);
        }
        Files.createDirectories(directory);
        Path relative = root.relativize(directory);
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Runtime staging path is a symbolic link: " + current);
        }
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Runtime extraction path contains a symbolic link: " + current);
            }
        }
    }

    private static byte @NotNull [] readLimited(@NotNull InputStream input, long limit, @NotNull String label)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException(label + " exceeds the " + limit + " byte size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static @NotNull String sha256(byte @NotNull [] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static @NotNull String sha256(@NotNull Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static @NotNull MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JVM does not provide SHA-256", exception);
        }
    }

    private static void deleteTree(@NotNull Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    interface InputStreamSupplier {
        InputStream open() throws IOException;
    }

    /** Stable, presentation-friendly metadata for settings and diagnostics. */
    public record RuntimeInfo(
            @NotNull String bundleId,
            @NotNull String profile,
            @NotNull String sourceDescribe,
            @NotNull String platform
    ) {
    }

    private record Manifest(
            Map<String, String> files,
            String profile,
            String sourceDescribe,
            String platform
    ) {
        private Manifest {
            files = Map.copyOf(files);
        }
    }
}
