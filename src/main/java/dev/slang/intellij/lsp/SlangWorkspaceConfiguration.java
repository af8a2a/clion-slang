package dev.slang.intellij.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.eclipse.lsp4j.ConfigurationItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Supplies slangd with the same setting names and defaults as the official VS Code extension. */
public final class SlangWorkspaceConfiguration {
    private static final Logger LOG = Logger.getInstance(SlangWorkspaceConfiguration.class);
    private static final Gson GSON = new Gson();
    private static final String CONFIG_FILE_NAME = "slangdconfig.json";
    private static final String WORKSPACE_FOLDER_VARIABLE = "${workspaceFolder}";

    private final Project project;

    public SlangWorkspaceConfiguration(@NotNull Project project) {
        this.project = project;
    }

    public @Nullable Object get(@NotNull ConfigurationItem item) {
        Path workspaceFolder = project.getBasePath() == null
                ? null
                : safeAbsolutePath(project.getBasePath());
        JsonObject settings = createDefaults();

        Path configFile = findConfigurationFile(item, workspaceFolder);
        if (configFile != null) {
            mergeConfigurationFile(settings, configFile);
        }

        JsonElement selected = selectSection(settings, item.getSection());
        if (selected == null || selected.isJsonNull()) {
            return null;
        }
        JsonElement expanded = expandWorkspaceFolder(selected, workspaceFolder);
        // LSP4J's JSON-RPC layer reliably serializes ordinary Maps/Lists/primitives.
        // Returning Gson's JsonElement directly would couple this plugin to its
        // internal serializer implementation.
        return GSON.fromJson(expanded, Object.class);
    }

    private @Nullable Path findConfigurationFile(
            @NotNull ConfigurationItem item,
            @Nullable Path workspaceFolder
    ) {
        Path directory = directoryForScope(item.getScopeUri());
        if (directory == null) {
            directory = workspaceFolder;
        }

        while (directory != null) {
            Path candidate = directory.resolve(CONFIG_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static @Nullable Path directoryForScope(@Nullable String scopeUri) {
        if (scopeUri == null || scopeUri.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(scopeUri);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            if (scopeUri.endsWith("/") || scopeUri.endsWith("\\") || Files.isDirectory(path)) {
                return path;
            }
            return path.getParent();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private static void mergeConfigurationFile(@NotNull JsonObject target, @NotNull Path configFile) {
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOG.warn("Ignoring " + configFile + ": root value must be a JSON object");
                return;
            }

            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("slang.")) {
                    target.add(key, entry.getValue().deepCopy());
                } else if (key.equals("slang") && entry.getValue().isJsonObject()) {
                    // Accept the nested LSP form as a convenience, while the documented
                    // slangdconfig.json format remains the flat "slang.*" form.
                    flattenObject(target, "slang", entry.getValue().getAsJsonObject());
                }
            }
        } catch (IOException | JsonParseException exception) {
            LOG.warn("Cannot read " + configFile + "; using Slang defaults", exception);
        }
    }

    private static void flattenObject(
            @NotNull JsonObject target,
            @NotNull String prefix,
            @NotNull JsonObject source
    ) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                flattenObject(target, key, value.getAsJsonObject());
            } else {
                target.add(key, value.deepCopy());
            }
        }
    }

    private static @Nullable JsonElement selectSection(
            @NotNull JsonObject flatSettings,
            @Nullable String section
    ) {
        if (section == null || section.isBlank()) {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : flatSettings.entrySet()) {
                insertDottedValue(root, entry.getKey(), entry.getValue());
            }
            return root;
        }

        JsonElement exact = flatSettings.get(section);
        if (exact != null) {
            return exact.deepCopy();
        }

        String prefix = section + ".";
        JsonObject nested = new JsonObject();
        boolean found = false;
        for (Map.Entry<String, JsonElement> entry : flatSettings.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                insertDottedValue(nested, entry.getKey().substring(prefix.length()), entry.getValue());
                found = true;
            }
        }
        return found ? nested : null;
    }

    private static void insertDottedValue(
            @NotNull JsonObject root,
            @NotNull String dottedKey,
            @NotNull JsonElement value
    ) {
        String[] components = dottedKey.split("\\.");
        JsonObject current = root;
        for (int index = 0; index < components.length - 1; index++) {
            String component = components[index];
            JsonElement child = current.get(component);
            if (child == null || !child.isJsonObject()) {
                JsonObject object = new JsonObject();
                current.add(component, object);
                current = object;
            } else {
                current = child.getAsJsonObject();
            }
        }
        current.add(components[components.length - 1], value.deepCopy());
    }

    private static @NotNull JsonElement expandWorkspaceFolder(
            @NotNull JsonElement value,
            @Nullable Path workspaceFolder
    ) {
        if (workspaceFolder == null) {
            return value.deepCopy();
        }
        String workspace = workspaceFolder.toString();
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) {
                return new JsonPrimitive(primitive.getAsString().replace(WORKSPACE_FOLDER_VARIABLE, workspace));
            }
            return primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                result.add(expandWorkspaceFolder(child, workspaceFolder));
            }
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                result.add(entry.getKey(), expandWorkspaceFolder(entry.getValue(), workspaceFolder));
            }
            return result;
        }
        return value.deepCopy();
    }

    private static @NotNull JsonObject createDefaults() {
        JsonObject defaults = new JsonObject();
        defaults.add("slang.predefinedMacros", new JsonArray());
        defaults.addProperty("slang.searchInAllWorkspaceDirectories", true);
        defaults.add("slang.additionalSearchPaths", new JsonArray());
        defaults.addProperty("slang.enableCommitCharactersInAutoCompletion", "membersOnly");
        defaults.addProperty("slang.format.enableFormatOnType", true);
        defaults.addProperty("slang.format.clangFormatLocation", "");
        defaults.addProperty("slang.format.clangFormatStyle", "file");
        defaults.addProperty(
                "slang.format.clangFormatFallbackStyle",
                "{BasedOnStyle: Microsoft, BreakBeforeBraces: Allman, ColumnLimit: 0}"
        );
        defaults.addProperty("slang.format.allowLineBreakChangesInOnTypeFormatting", false);
        defaults.addProperty("slang.format.allowLineBreakChangesInRangeFormatting", false);
        defaults.addProperty("slang.inlayHints.deducedTypes", true);
        defaults.addProperty("slang.inlayHints.parameterNames", true);
        defaults.addProperty("slang.workspaceFlavor", "standard");
        return defaults;
    }

    private static @Nullable Path safeAbsolutePath(@NotNull String value) {
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException ignored) {
            return null;
        }
    }
}
