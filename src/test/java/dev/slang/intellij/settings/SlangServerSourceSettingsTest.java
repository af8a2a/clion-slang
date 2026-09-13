package dev.slang.intellij.settings;

import org.junit.Test;
import static org.junit.Assert.*;

public class SlangServerSourceSettingsTest {
    @Test
    public void automaticLegacySettingsUseBundleOnSupportedHosts() {
        var state = new SlangProjectSettings.SettingsState();
        assertEquals(SlangServerSource.BUNDLED, SlangProjectSettings.effectiveServerSource(state, true));
        assertEquals(SlangServerSource.EXTERNAL, SlangProjectSettings.effectiveServerSource(state, false));
    }

    @Test
    public void legacyManualConfigurationIsPreserved() {
        var state = new SlangProjectSettings.SettingsState();
        state.slangdPath = "tools/slangd.exe";
        assertEquals(SlangServerSource.EXTERNAL, SlangProjectSettings.effectiveServerSource(state, true));
        var migrated = new SlangProjectSettings();
        migrated.loadState(state);
        assertFalse(migrated.isAutoDetectSlangd());
        assertEquals("tools/slangd.exe", migrated.getSlangdPath());
        assertEquals(SlangServerSource.EXTERNAL, migrated.getServerSource());
        state.slangdPath = "";
        state.autoDetectSlangd = false;
        assertEquals(SlangServerSource.EXTERNAL, SlangProjectSettings.effectiveServerSource(state, true));
    }

    @Test
    public void explicitChoicesSurviveSaveReloadWithoutLosingManualPath() {
        var settings = new SlangProjectSettings();
        settings.setAutoDetectSlangd(false);
        settings.setSlangdPath("tools/official/slangd.exe");
        settings.setServerSource(SlangServerSource.BUNDLED);
        var snapshot = settings.getState();
        var restored = new SlangProjectSettings();
        restored.loadState(snapshot);
        assertEquals(SlangServerSource.BUNDLED, restored.getServerSource());
        assertEquals("tools/official/slangd.exe", restored.getSlangdPath());
        restored.setServerSource(SlangServerSource.EXTERNAL);
        settings.loadState(restored.getState());
        assertEquals(SlangServerSource.EXTERNAL, settings.getServerSource());
        assertFalse(settings.isAutoDetectSlangd());
        assertEquals("tools/official/slangd.exe", settings.getSlangdPath());
        snapshot.serverSource = SlangServerSource.EXTERNAL;
        snapshot.slangdPath = "changed";
        assertEquals("tools/official/slangd.exe", restored.getSlangdPath());
    }

    @Test
    public void explicitExternalAutoDetectionDoesNotMigrateBackToBundle() {
        var state = new SlangProjectSettings.SettingsState();
        state.serverSource = SlangServerSource.EXTERNAL;
        assertEquals(SlangServerSource.EXTERNAL, SlangProjectSettings.effectiveServerSource(state, true));
        var restored = new SlangProjectSettings();
        restored.loadState(state);
        assertEquals(SlangServerSource.EXTERNAL, restored.getState().serverSource);
    }

    @Test
    public void supportedPlatformDetectionIsExact() {
        assertTrue(SlangServerSource.isBundledSupported("Windows 11", "amd64"));
        assertTrue(SlangServerSource.isBundledSupported("Windows 10", "x86_64"));
        assertFalse(SlangServerSource.isBundledSupported("Windows 11", "aarch64"));
        assertFalse(SlangServerSource.isBundledSupported("Linux", "amd64"));
        assertFalse(SlangServerSource.isBundledSupported("Darwin", "x86_64"));
    }
}
