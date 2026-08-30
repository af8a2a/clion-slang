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
        public boolean useExternalSlangd;
        public String externalSlangdPath = "";

        // Kept nullable for one-way migration from the pre-M2b settings schema.
        // New state snapshots leave both legacy fields null, so they disappear
        // from the workspace file after the next save.
        @Deprecated
        public Boolean autoDetectSlangd;
        @Deprecated
        public String slangdPath;

        public SettingsState() {
        }

        private SettingsState(@NotNull SettingsState other) {
            useExternalSlangd = other.useExternalSlangd;
            externalSlangdPath = other.externalSlangdPath;
            autoDetectSlangd = other.autoDetectSlangd;
            slangdPath = other.slangdPath;
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
        if (this.state.externalSlangdPath == null) {
            this.state.externalSlangdPath = "";
        }

        // The old locator always gave a non-empty slangdPath priority, even
        // when autoDetectSlangd was true. Preserve that effective behavior;
        // only legacy projects without an explicit path move to the bundle.
        if (!this.state.useExternalSlangd
                && this.state.externalSlangdPath.isBlank()
                && this.state.slangdPath != null
                && !this.state.slangdPath.isBlank()) {
            this.state.useExternalSlangd = true;
            this.state.externalSlangdPath = this.state.slangdPath;
        }
        this.state.autoDetectSlangd = null;
        this.state.slangdPath = null;
    }

    public synchronized boolean isUseExternalSlangd() {
        return state.useExternalSlangd;
    }

    public synchronized void setUseExternalSlangd(boolean useExternalSlangd) {
        state.useExternalSlangd = useExternalSlangd;
    }

    public synchronized @NotNull String getExternalSlangdPath() {
        return state.externalSlangdPath == null ? "" : state.externalSlangdPath;
    }

    public synchronized void setExternalSlangdPath(@NotNull String externalSlangdPath) {
        state.externalSlangdPath = externalSlangdPath;
    }
}
