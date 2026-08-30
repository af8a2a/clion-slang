package dev.slang.intellij.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlangProjectSettingsTest {
    @Test
    public void defaultsToBundledRuntime() {
        SlangProjectSettings settings = new SlangProjectSettings();

        assertFalse(settings.isUseExternalSlangd());
        assertEquals("", settings.getExternalSlangdPath());
    }

    @Test
    public void migratesLegacyManualSelectionToExternalOverride() {
        SlangProjectSettings.SettingsState legacy = new SlangProjectSettings.SettingsState();
        legacy.autoDetectSlangd = false;
        legacy.slangdPath = "tools/slangd.exe";

        SlangProjectSettings settings = new SlangProjectSettings();
        settings.loadState(legacy);

        assertTrue(settings.isUseExternalSlangd());
        assertEquals("tools/slangd.exe", settings.getExternalSlangdPath());
        assertNull(settings.getState().autoDetectSlangd);
        assertNull(settings.getState().slangdPath);
    }

    @Test
    public void migratesLegacyExplicitPathEvenWhenAutoDetectionWasEnabled() {
        SlangProjectSettings.SettingsState legacy = new SlangProjectSettings.SettingsState();
        legacy.autoDetectSlangd = true;
        legacy.slangdPath = "tools/slangd.exe";

        SlangProjectSettings settings = new SlangProjectSettings();
        settings.loadState(legacy);

        assertTrue(settings.isUseExternalSlangd());
        assertEquals("tools/slangd.exe", settings.getExternalSlangdPath());
        assertNull(settings.getState().autoDetectSlangd);
        assertNull(settings.getState().slangdPath);
    }

    @Test
    public void legacyAutoDetectionMovesToBundledRuntime() {
        SlangProjectSettings.SettingsState legacy = new SlangProjectSettings.SettingsState();
        legacy.autoDetectSlangd = true;

        SlangProjectSettings settings = new SlangProjectSettings();
        settings.loadState(legacy);

        assertFalse(settings.isUseExternalSlangd());
        assertEquals("", settings.getExternalSlangdPath());
    }
}
