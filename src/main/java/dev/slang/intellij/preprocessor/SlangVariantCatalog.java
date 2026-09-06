package dev.slang.intellij.preprocessor;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Declarative data only. Never executes a build tool, shell command, response file or generator. */
public record SlangVariantCatalog(Path file, List<Variant> variants, String error, boolean present) {
    private static final int MAX_BYTES = 1024 * 1024, MAX_VARIANTS = 512;
    private static final Set<String> CONTEXT_KEYS = Set.of("id", "name", "root", "entryPoint", "stage", "target", "profile",
            "inheritWorkspace", "defines", "undefines", "includePaths", "variants", "buildTarget", "configuration");
    private static final Set<String> VARIANT_KEYS = Set.of("id", "name", "entryPoint", "stage", "target", "profile",
            "defines", "undefines", "includePaths", "buildTarget", "configuration");
    private static final Set<String> TARGETS = Set.of("", "spirv", "dxil", "hlsl", "glsl", "cpp", "cuda", "ptx");
    private static final Set<String> STAGES = Set.of("", "compute", "vertex", "fragment", "geometry", "hull", "domain",
            "mesh", "amplification", "raygeneration", "intersection", "anyhit", "closesthit", "miss", "callable");

    public record Variant(String id, String label, Path root, String entryPoint, String stage,
                          String buildTarget, String configuration,
                          SlangPreprocessorTrace.BuildContext buildContext) {
        public String summary() {
            String macros = String.join(", ", buildContext.defines().stream().limit(6).map(d -> d.name() + "=" + shortText(d.value(), 32)).toList());
            if (buildContext.defines().size() > 6) macros += ", …";
            return shortText(label, 160) + " [" + id + "] | " + root.getFileName() + " | " + entryPoint + " " + stage + " | "
                    + shortText(buildTarget + " " + configuration, 128) + " | "
                    + buildContext.target() + " " + buildContext.profile() + (macros.isEmpty() ? "" : " | " + macros);
        }
    }

    public Variant find(String id) { return variants.stream().filter(v -> v.id.equals(id)).findFirst().orElse(null); }

    public static SlangVariantCatalog read(Path file, Path workspace) {
        if (!Files.exists(file)) return new SlangVariantCatalog(file, List.of(), null, false);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid("Manifest exceeds 1 MiB");
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return parse(file, workspace, json);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return new SlangVariantCatalog(file, List.of(), "Shader variants: " + exception.getMessage(), true);
        }
    }

    public static SlangVariantCatalog parse(Path file, Path workspace, String json) {
        if (json.length() > MAX_BYTES) throw invalid("Manifest exceeds size limit");
        if (json.startsWith("\uFEFF")) json = json.substring(1);
        JsonObject document;
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            document = object(readValue(reader, 0), "manifest");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Trailing manifest data");
        } catch (IOException exception) { throw invalid("Invalid JSON: " + exception.getMessage()); }
        keys(document, Set.of("version", "contexts"));
        if (!document.has("version") || !document.get("version").isJsonPrimitive()
                || !document.getAsJsonPrimitive("version").isNumber() || !"1".equals(document.get("version").getAsString()))
            throw invalid("Expected shader variants version 1");
        JsonArray contexts = array(document, "contexts", true);
        if (contexts.size() > MAX_VARIANTS) throw invalid("Too many build contexts");
        List<Variant> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : contexts) {
            JsonObject context = object(element, "context");
            keys(context, CONTEXT_KEYS);
            String contextId = id(context);
            if (!ids.add(contextId)) throw invalid("Duplicate context id: " + contextId);
            Path root = resolvePath(file, workspace, string(context, "root", null));
            if (!root.toString().toLowerCase(Locale.ROOT).endsWith(".slang")) throw invalid("Context root must be a .slang file");
            String contextName = string(context, "name", contextId);
            JsonArray variants = array(context, "variants", false);
            if (!context.has("variants")) {
                JsonObject defaultVariant = new JsonObject();
                defaultVariant.addProperty("id", "default");
                variants.add(defaultVariant);
            } else if (variants.isEmpty()) throw invalid("Explicit variants list must not be empty");
            if (variants.size() + result.size() > MAX_VARIANTS) throw invalid("Too many shader variants (maximum 512)");
            Set<String> variantIds = new HashSet<>();
            for (JsonElement variantElement : variants) {
                JsonObject variant = object(variantElement, "variant");
                keys(variant, VARIANT_KEYS);
                String variantId = id(variant);
                if (!variantIds.add(variantId)) throw invalid("Duplicate variant id in " + contextId + ": " + variantId);
                TreeMap<String, String> defines = new TreeMap<>();
                TreeSet<String> undefines = new TreeSet<>();
                mergeMacros(context, defines, undefines);
                mergeMacros(variant, defines, undefines);
                if (defines.size() > 256 || undefines.size() > 256) throw invalid("Too many macros");
                LinkedHashSet<String> paths = new LinkedHashSet<>();
                for (JsonObject source : List.of(variant, context))
                    for (JsonElement path : array(source, "includePaths", false))
                        paths.add(resolvePath(file, workspace, stringValue(path, "include path")).toString());
                if (paths.size() > 256) throw invalid("Too many include paths");
                String entry = override(context, variant, "entryPoint");
                String stage = override(context, variant, "stage");
                String target = override(context, variant, "target");
                String profile = override(context, variant, "profile");
                String buildTarget = override(context, variant, "buildTarget");
                String configuration = override(context, variant, "configuration");
                if (!STAGES.contains(stage)) throw invalid("Unsupported stage: " + stage);
                if (!TARGETS.contains(target)) throw invalid("Unsupported target: " + target);
                if (entry.length() > 128 || profile.length() > 128) throw invalid("Entry point/profile too long");
                boolean inherit = bool(context, "inheritWorkspace", false);
                String fullId = contextId + "/" + variantId;
                String label = contextName + " / " + string(variant, "name", variantId);
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("manifest", file.toAbsolutePath().normalize().toString());
                identity.put("id", fullId); identity.put("root", root.toString()); identity.put("entryPoint", entry);
                identity.put("stage", stage); identity.put("target", target); identity.put("profile", profile);
                identity.put("buildTarget", buildTarget); identity.put("configuration", configuration);
                identity.put("inheritWorkspace", inherit); identity.put("defines", defines);
                identity.put("undefines", undefines); identity.put("includePaths", paths);
                String fingerprint = sha256(new Gson().toJson(identity));
                var options = new SlangPreprocessorTrace.BuildContext(1, fingerprint, inherit,
                        defines.entrySet().stream().map(d -> new SlangPreprocessorTrace.Macro(d.getKey(), d.getValue())).toList(),
                        List.copyOf(undefines), List.copyOf(paths), target, profile);
                result.add(new Variant(fullId, label, root, entry, stage, buildTarget, configuration, options));
            }
        }
        return new SlangVariantCatalog(file, List.copyOf(result), null, true);
    }

    private static void mergeMacros(JsonObject source, Map<String, String> defines, Set<String> undefines) {
        JsonObject additions = source.has("defines") ? object(source.get("defines"), "defines") : new JsonObject();
        JsonArray removals = array(source, "undefines", false);
        if (additions.size() > 256 || removals.size() > 256) throw invalid("Too many macros");
        Set<String> seen = new HashSet<>();
        for (JsonElement element : removals) {
            String name = stringValue(element, "undefine");
            macroName(name);
            if (!seen.add(name) || additions.has(name)) throw invalid("Conflicting/duplicate macro: " + name);
            defines.remove(name); undefines.add(name);
        }
        for (var entry : additions.entrySet()) {
            macroName(entry.getKey());
            defines.put(entry.getKey(), stringValue(entry.getValue(), "macro value (use a JSON string)"));
            undefines.remove(entry.getKey());
        }
    }

    private static void macroName(String name) {
        if (name.length() > 128 || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalid("Invalid macro name: " + name);
    }

    private static Path resolvePath(Path manifest, Path workspace, String value) {
        if (value.isBlank()) throw invalid("Empty path is not allowed");
        if (workspace != null) value = value.replace("${workspaceFolder}", workspace.toAbsolutePath().normalize().toString());
        if (value.contains("${") || value.contains("$<")) throw invalid("Unexpanded path variable: " + value);
        Path path = Path.of(value.replace('\\', '/'));
        return (path.isAbsolute() ? path : manifest.toAbsolutePath().getParent().resolve(path)).normalize();
    }

    private static String override(JsonObject context, JsonObject variant, String name) {
        return string(variant, name, string(context, name, ""));
    }
    private static String id(JsonObject object) {
        String id = string(object, "id", null);
        if (id.length() > 128 || !id.matches("[A-Za-z0-9_.-]+")) throw invalid("Invalid context/variant id: " + id);
        return id;
    }
    private static String string(JsonObject object, String key, String fallback) {
        if (!object.has(key)) {
            if (fallback == null) throw invalid("Missing " + key);
            return fallback;
        }
        return stringValue(object.get(key), key);
    }
    private static String stringValue(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw invalid("Expected string: " + name);
        String value = element.getAsString();
        if (value.length() > 4096 || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw invalid("Invalid string: " + name);
        return value;
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) throw invalid("Expected boolean: " + key);
        return object.get(key).getAsBoolean();
    }
    private static JsonObject object(JsonElement element, String name) {
        if (element == null || !element.isJsonObject()) throw invalid("Expected object: " + name);
        return element.getAsJsonObject();
    }
    private static JsonArray array(JsonObject object, String key, boolean required) {
        if (!object.has(key) && !required) return new JsonArray();
        if (!object.has(key) || !object.get(key).isJsonArray()) throw invalid("Expected array: " + key);
        return object.getAsJsonArray(key);
    }
    private static void keys(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw invalid("Unknown field: " + key);
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static String shortText(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit) + "…"; }

    /** Bounded, strict JSON with duplicate-key rejection (including macro maps). */
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw invalid("Manifest nesting limit exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw invalid("Duplicate JSON field: " + key);
                    object.add(key, readValue(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (array.size() >= 1024) throw invalid("Manifest array limit exceeded");
                    array.add(readValue(reader, depth + 1));
                }
                reader.endArray(); return array;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new java.math.BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw invalid("Unexpected JSON token");
        }
    }
}
