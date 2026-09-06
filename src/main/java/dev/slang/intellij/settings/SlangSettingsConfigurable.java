package dev.slang.intellij.settings;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.LspServerManager;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import dev.slang.intellij.lsp.SlangServerLocator;
import dev.slang.intellij.preprocessor.SlangBranchDisplayService;
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
    private JCheckBox autoDetect;
    private JTextField slangdPath;
    private JLabel resolvedPath;
    private JCheckBox branchDisplay;
    private JCheckBox branchLabels;

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

        autoDetect = new JCheckBox("Automatically detect slangd (SLANGD_PATH, VULKAN_SDK, PATH)");
        form.add(autoDetect, constraints);

        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 4, 8);
        form.add(new JLabel("slangd executable:"), constraints);

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

        constraints.gridy++;
        branchDisplay = new JCheckBox("Show preprocessor branches (requires M4a-enabled slangd)");
        form.add(branchDisplay, constraints);
        constraints.gridy++;
        branchLabels = new JCheckBox("Show branch source labels after #elif / #else / #endif");
        form.add(branchLabels, constraints);
        constraints.gridy++;
        form.add(new JLabel("Choose the branch context from the editor popup or Slang status-bar widget (M4c slangd)."), constraints);
        branchDisplay.addActionListener(event -> branchLabels.setEnabled(branchDisplay.isSelected()));

        autoDetect.addActionListener(event -> {
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
        if (autoDetect == null || slangdPath == null) {
            return false;
        }
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        String uiPath = autoDetect.isSelected() ? "" : slangdPath.getText().trim();
        return autoDetect.isSelected() != settings.isAutoDetectSlangd()
                || !Objects.equals(uiPath, settings.getSlangdPath())
                || branchDisplay.isSelected() != settings.isShowPreprocessorBranches()
                || branchLabels.isSelected() != settings.isShowPreprocessorBranchLabels();
    }

    @Override
    public void apply() {
        if (autoDetect == null || slangdPath == null) {
            return;
        }

        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        String uiPath = autoDetect.isSelected() ? "" : slangdPath.getText().trim();
        boolean serverChanged = autoDetect.isSelected() != settings.isAutoDetectSlangd()
                || !Objects.equals(uiPath, settings.getSlangdPath());
        settings.setAutoDetectSlangd(autoDetect.isSelected());
        // Clearing the manual value makes the resolution contract unambiguous:
        // any stored non-empty path is always the highest-priority choice.
        settings.setSlangdPath(autoDetect.isSelected() ? "" : slangdPath.getText().trim());
        settings.setShowPreprocessorBranches(branchDisplay.isSelected());
        settings.setShowPreprocessorBranchLabels(branchLabels.isSelected());
        updateResolvedPathPreview();
        SlangBranchDisplayService.getInstance(project).refresh();

        if (serverChanged) {
            LspServerManager.getInstance(project)
                    .stopAndRestartIfNeeded(SlangLspServerSupportProvider.class);
        }
    }

    @Override
    public void reset() {
        if (autoDetect == null || slangdPath == null) {
            return;
        }
        SlangProjectSettings settings = SlangProjectSettings.getInstance(project);
        autoDetect.setSelected(settings.isAutoDetectSlangd());
        slangdPath.setText(settings.getSlangdPath());
        branchDisplay.setSelected(settings.isShowPreprocessorBranches());
        branchLabels.setSelected(settings.isShowPreprocessorBranchLabels());
        branchLabels.setEnabled(branchDisplay.isSelected());
        updateFieldEnabledState();
        updateResolvedPathPreview();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        autoDetect = null;
        slangdPath = null;
        resolvedPath = null;
        branchDisplay = null;
        branchLabels = null;
    }

    private void updateFieldEnabledState() {
        if (slangdPath != null && autoDetect != null) {
            slangdPath.setEnabled(!autoDetect.isSelected());
        }
    }

    private void updateResolvedPathPreview() {
        if (resolvedPath == null || autoDetect == null || slangdPath == null) {
            return;
        }

        try {
            Path path = new SlangServerLocator().resolve(
                    project,
                    autoDetect.isSelected() ? "" : slangdPath.getText().trim(),
                    autoDetect.isSelected()
            );
            resolvedPath.setText("Resolved slangd: " + path);
            resolvedPath.setToolTipText(path.toString());
        } catch (ExecutionException exception) {
            resolvedPath.setText("slangd not found");
            resolvedPath.setToolTipText(exception.getMessage());
        }
    }
}
