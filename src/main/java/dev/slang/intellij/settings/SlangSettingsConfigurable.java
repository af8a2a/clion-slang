package dev.slang.intellij.settings;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.LspServerManager;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import dev.slang.intellij.lsp.SlangServerLocator;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.Objects;

public final class SlangSettingsConfigurable implements Configurable {
    private final Project project;

    private JPanel panel;
    private JCheckBox useExternalSlangd;
    private JTextField slangdPath;
    private JLabel resolvedPath;

    public SlangSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Slang";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(4, 0, 8, 0);

        useExternalSlangd = new JCheckBox("Use external slangd (advanced)");
        form.add(useExternalSlangd, constraints);

        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 4, 8);
        form.add(new JLabel("External slangd executable:"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(0, 0, 4, 0);
        slangdPath = new JTextField();
        form.add(slangdPath, constraints);

        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(4, 0, 0, 0);
        resolvedPath = new JLabel();
        form.add(resolvedPath, constraints);

        useExternalSlangd.addActionListener(event -> {
            updateFieldEnabledState();
            updateResolvedPathPreview();
        });
        slangdPath.getDocument().addDocumentListener(new SimpleDocumentListener(this::updateResolvedPathPreview));

        panel.add(form, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (useExternalSlangd == null || slangdPath == null) {
            return false;
        }
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        return useExternalSlangd.isSelected() != settings.isUseExternalSlangd()
                || !Objects.equals(slangdPath.getText().trim(), settings.getExternalSlangdPath());
    }

    @Override
    public void apply() {
        if (useExternalSlangd == null || slangdPath == null) {
            return;
        }

        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        boolean changed = isModified();
        settings.setUseExternalSlangd(useExternalSlangd.isSelected());
        settings.setExternalSlangdPath(slangdPath.getText().trim());
        updateResolvedPathPreview();

        if (changed) {
            LspServerManager.getInstance(project)
                    .stopAndRestartIfNeeded(SlangLspServerSupportProvider.class);
        }
    }

    @Override
    public void reset() {
        if (useExternalSlangd == null || slangdPath == null) {
            return;
        }
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        useExternalSlangd.setSelected(settings.isUseExternalSlangd());
        slangdPath.setText(settings.getExternalSlangdPath());
        updateFieldEnabledState();
        updateResolvedPathPreview();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        useExternalSlangd = null;
        slangdPath = null;
        resolvedPath = null;
    }

    private void updateFieldEnabledState() {
        if (slangdPath != null && useExternalSlangd != null) {
            slangdPath.setEnabled(useExternalSlangd.isSelected());
        }
    }

    private void updateResolvedPathPreview() {
        if (resolvedPath == null || useExternalSlangd == null || slangdPath == null) {
            return;
        }

        if (!useExternalSlangd.isSelected()) {
            // Configurable callbacks run on Swing's event-dispatch thread. Resolving the
            // bundle here would read, hash, and potentially extract the native runtime
            // while the Settings dialog is opening. Installation is intentionally left
            // to the background LSP startup path.
            resolvedPath.setText("Bundled slangd: installed and verified automatically on first use");
            resolvedPath.setToolTipText("Plugin-managed Windows x64 runtime");
            return;
        }

        try {
            Path path = new SlangServerLocator().resolve(
                    project,
                    slangdPath.getText().trim(),
                    true
            );
            resolvedPath.setText("External slangd: " + path);
            resolvedPath.setToolTipText(path.toString());
        } catch (ExecutionException exception) {
            resolvedPath.setText("External slangd not found");
            resolvedPath.setToolTipText(exception.getMessage());
        }
    }
}
