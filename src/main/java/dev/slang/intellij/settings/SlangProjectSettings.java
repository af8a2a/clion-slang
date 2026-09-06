package dev.slang.intellij.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(
        name = "SlangProjectSettings",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class SlangProjectSettings implements PersistentStateComponent<SlangProjectSettings.SettingsState> {
    public static final class SettingsState {
        public boolean autoDetectSlangd = true;
        public String slangdPath = "";
        public boolean showPreprocessorBranches = true;
        public boolean showPreprocessorBranchLabels = true;

        public SettingsState() {
        }

        private SettingsState(@NotNull SettingsState other) {
            autoDetectSlangd = other.autoDetectSlangd;
            slangdPath = other.slangdPath;
            showPreprocessorBranches = other.showPreprocessorBranches;
            showPreprocessorBranchLabels = other.showPreprocessorBranchLabels;
        }
    }

    private SettingsState state = new SettingsState();

    public static @NotNull SlangProjectSettings getInstance(@NotNull Project project) {
        return project.getService(SlangProjectSettings.class);
    }

    @Override
    public synchronized @NotNull SettingsState getState() {
        return new SettingsState(state);
    }

    @Override
    public synchronized void loadState(@NotNull SettingsState state) {
        this.state = new SettingsState(state);
        if (this.state.slangdPath == null) {
            this.state.slangdPath = "";
        }
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
}
