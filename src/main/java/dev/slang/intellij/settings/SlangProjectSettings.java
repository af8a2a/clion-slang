package dev.slang.intellij.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.HashMap;
import java.util.Map;

@Service(Service.Level.PROJECT)
@State(
        name = "SlangProjectSettings",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class SlangProjectSettings implements PersistentStateComponent<SlangProjectSettings.SettingsState> {
    public static final class SettingsState {
        /** Null identifies settings saved before the server-source selector existed. */
        public SlangServerSource serverSource;
        public boolean autoDetectSlangd = true;
        public String slangdPath = "";
        public boolean showPreprocessorBranches = true;
        public boolean showPreprocessorBranchLabels = true;
        /** Target local path -> explicitly chosen root path. Absence means automatic. */
        public Map<String, String> preprocessorContexts = new HashMap<>();
        public String shaderVariantsPath = "slang-variants.json";
        public Map<String, String> shaderVariants = new HashMap<>();

        public SettingsState() {
        }

        private SettingsState(@NotNull SettingsState other) {
            serverSource = other.serverSource;
            autoDetectSlangd = other.autoDetectSlangd;
            slangdPath = other.slangdPath;
            showPreprocessorBranches = other.showPreprocessorBranches;
            showPreprocessorBranchLabels = other.showPreprocessorBranchLabels;
            if (other.preprocessorContexts != null) preprocessorContexts.putAll(other.preprocessorContexts);
            shaderVariantsPath = other.shaderVariantsPath == null || other.shaderVariantsPath.isBlank()
                    ? "slang-variants.json" : other.shaderVariantsPath;
            if (other.shaderVariants != null) shaderVariants.putAll(other.shaderVariants);
        }
    }

    private SettingsState state = new SettingsState();

    public static @NotNull SlangProjectSettings getInstance(@NotNull Project project) {
        return project.getService(SlangProjectSettings.class);
    }

    @Override
    public synchronized @NotNull SettingsState getState() {
        SettingsState snapshot = new SettingsState(state);
        snapshot.serverSource = getServerSource();
        return snapshot;
    }

    @Override
    public synchronized void loadState(@NotNull SettingsState state) {
        this.state = new SettingsState(state);
        if (this.state.slangdPath == null) {
            this.state.slangdPath = "";
        }
        // Older releases gave any saved path priority, even with auto-detection enabled.
        if (this.state.serverSource == null && !this.state.slangdPath.isBlank()) {
            this.state.autoDetectSlangd = false;
        }
    }

    public synchronized @NotNull SlangServerSource getServerSource() {
        return effectiveServerSource(state, SlangServerSource.isBundledSupported());
    }

    static @NotNull SlangServerSource effectiveServerSource(SettingsState state, boolean bundledSupported) {
        if (state.serverSource != null) return state.serverSource;
        // Preserve a deliberate external path (or deliberately disabled discovery) on upgrade.
        boolean manual = !state.autoDetectSlangd || (state.slangdPath != null && !state.slangdPath.isBlank());
        return bundledSupported && !manual ? SlangServerSource.BUNDLED : SlangServerSource.EXTERNAL;
    }

    public synchronized void setServerSource(@NotNull SlangServerSource source) {
        state.serverSource = source;
    }

    public synchronized boolean isAutoDetectSlangd() {
        return state.autoDetectSlangd;
    }

    public synchronized void setAutoDetectSlangd(boolean autoDetectSlangd) {
        state.autoDetectSlangd = autoDetectSlangd;
    }

    public synchronized @NotNull String getSlangdPath() {
        return state.slangdPath == null ? "" : state.slangdPath;
    }

    public synchronized void setSlangdPath(@NotNull String slangdPath) {
        state.slangdPath = slangdPath;
    }

    public synchronized boolean isShowPreprocessorBranches() { return state.showPreprocessorBranches; }

    public synchronized void setShowPreprocessorBranches(boolean value) { state.showPreprocessorBranches = value; }

    public synchronized boolean isShowPreprocessorBranchLabels() { return state.showPreprocessorBranchLabels; }

    public synchronized void setShowPreprocessorBranchLabels(boolean value) { state.showPreprocessorBranchLabels = value; }

    public synchronized String getPreprocessorContext(String target) { return state.preprocessorContexts.get(target); }

    public synchronized void setPreprocessorContext(String target, String root) {
        state.shaderVariants.remove(target);
        if (root == null) state.preprocessorContexts.remove(target);
        else state.preprocessorContexts.put(target, root);
    }

    public synchronized String getShaderVariantsPath() { return state.shaderVariantsPath; }
    public synchronized void setShaderVariantsPath(String path) {
        state.shaderVariantsPath = path == null || path.isBlank() ? "slang-variants.json" : path.trim();
    }
    public synchronized String getShaderVariant(String target) { return state.shaderVariants.get(target); }
    public synchronized void setShaderVariant(String target, String id) {
        if (id == null) state.shaderVariants.remove(target);
        else state.shaderVariants.put(target, id);
    }
}
