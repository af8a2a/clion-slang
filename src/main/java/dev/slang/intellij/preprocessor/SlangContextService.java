package dev.slang.intellij.preprocessor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.platform.lsp.api.LspServerManager;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import dev.slang.intellij.lsp.SlangWorkspaceConfiguration;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.eclipse.lsp4j.ConfigurationItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** A bounded, lazy project include snapshot. No disk scans or waits on the UI thread. */
@Service(Service.Level.PROJECT)
public final class SlangContextService implements Disposable {
    public record Discovery(List<SlangIncludeGraph.Candidate> candidates, boolean limited) {}
    public record ResolvedContext(Path root, String label, SlangVariantCatalog.Variant variant) {}
    private record Snapshot(long epoch, SlangIncludeGraph graph, boolean limited) {}
    private static final int MAX_FILES = 8192, MAX_ATTEMPTS = 100_000;
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private final Project project;
    private volatile long epoch;
    private volatile boolean disposed;
    private Snapshot snapshot;
    // Status belongs to the most recent accepted display request, not just a popup selection.
    private final Map<String, String> status = new HashMap<>();
    private final SlangPreviewState previews = new SlangPreviewState();

    public SlangContextService(Project project) { this.project = project; }
    public static SlangContextService getInstance(Project project) { return project.getService(SlangContextService.class); }
    public void invalidate() { epoch++; status.clear(); updateWidget(); }
    public long revision() { return epoch; }
    public SlangPreviewState previews() { return previews; }

    public String selection(VirtualFile target) {
        var settings = SlangProjectSettings.getInstance(project);
        String variant = settings.getShaderVariant(target.getPath());
        if (variant != null) return "variant:" + variant;
        String pinned = settings.getPreprocessorContext(target.getPath());
        return pinned == null ? "auto" : "root:" + pinned;
    }

    public SlangPreviewState.Baseline baseline(VirtualFile target, ResolvedContext resolved) {
        return new SlangPreviewState.Baseline(selection(target), resolved.root().toString(),
                resolved.variant() == null ? null : resolved.variant().buildContext().fingerprint());
    }

    public void stopPreview(VirtualFile target) {
        previews.stop(target.getPath());
        SlangBranchDisplayService.getInstance(project).refresh();
    }

    public static Path path(VirtualFile file) { return file.toNioPath().toAbsolutePath().normalize(); }

    /** Called only on a background thread; a shared scan serves all open editors. */
    public synchronized Discovery discover(VirtualFile target) {
        long requestedEpoch = epoch;
        if (snapshot == null || snapshot.epoch != requestedEpoch) snapshot = scan(requestedEpoch);
        check(requestedEpoch);
        return new Discovery(snapshot.graph == null ? List.of() : snapshot.graph.candidates(path(target)), snapshot.limited);
    }

    public Path resolve(VirtualFile target) {
        String pinned = SlangProjectSettings.getInstance(project).getPreprocessorContext(target.getPath());
        if (pinned != null) return Path.of(pinned).toAbsolutePath().normalize();
        Discovery discovery = discover(target);
        return automaticRoot(path(target), discovery);
    }

    public Path variantsFile() {
        Path configured = Path.of(SlangProjectSettings.getInstance(project).getShaderVariantsPath());
        if (!configured.isAbsolute()) {
            if (project.getBasePath() == null) throw new IllegalArgumentException("Shader variants need a project directory or absolute manifest path");
            configured = Path.of(project.getBasePath()).resolve(configured);
        }
        return configured.normalize();
    }

    /** Saved UTF-8 data, read off EDT on each use; selected IDs never silently fall back. */
    public SlangVariantCatalog variants() {
        return SlangVariantCatalog.read(variantsFile(), project.getBasePath() == null ? null : Path.of(project.getBasePath()));
    }

    public ResolvedContext resolveBuildContext(VirtualFile target) {
        String id = SlangProjectSettings.getInstance(project).getShaderVariant(target.getPath());
        if (id == null) {
            Path root = resolve(target);
            return new ResolvedContext(root, relative(root), null);
        }
        var catalog = variants();
        if (catalog.error() != null) throw new IllegalArgumentException(catalog.error());
        var variant = catalog.find(id);
        if (variant == null) throw new IllegalArgumentException("Selected shader variant is missing: " + id);
        return new ResolvedContext(variant.root(), variant.label() + " · " + relative(variant.root()), variant);
    }

    public boolean isVariantCurrent(VirtualFile target, ResolvedContext resolved) {
        if (resolved.variant == null) return SlangProjectSettings.getInstance(project).getShaderVariant(target.getPath()) == null;
        if (!resolved.variant.id().equals(SlangProjectSettings.getInstance(project).getShaderVariant(target.getPath()))) return false;
        var current = variants().find(resolved.variant.id());
        return current != null && current.buildContext().fingerprint().equals(resolved.variant.buildContext().fingerprint());
    }

    public void selectVariant(VirtualFile target, String id) {
        previews.stop(target.getPath());
        SlangProjectSettings.getInstance(project).setShaderVariant(target.getPath(), id);
        SlangBranchDisplayService.getInstance(project).refresh();
    }

    public boolean isVariantsPath(String eventPath) {
        try { return variantsFile().equals(Path.of(eventPath).toAbsolutePath().normalize()); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static Path automaticRoot(Path target, Discovery discovery) {
        return !discovery.limited && discovery.candidates.size() == 1 ? discovery.candidates.getFirst().root() : target;
    }

    public void select(VirtualFile target, Path root) {
        previews.stop(target.getPath());
        SlangProjectSettings.getInstance(project).setPreprocessorContext(target.getPath(),
                root == null ? null : root.toString());
        SlangBranchDisplayService.getInstance(project).refresh();
    }

    public void report(VirtualFile target, String message) {
        status.put(target.getPath(), message == null ? "Context unavailable" : message);
        updateWidget();
    }

    public String description(VirtualFile target) {
        var preview = target == null ? null : previews.peek(target.getPath());
        return (preview == null ? "" : "PREVIEW (" + preview.macros().summary() + ") · ") + baseDescription(target);
    }

    private String baseDescription(VirtualFile target) {
        if (target == null) return "Select a Slang file";
        if (!SlangProjectSettings.getInstance(project).isShowPreprocessorBranches()) return "Branch display disabled";
        var servers = LspServerManager.getInstance(project).getServersForProvider(SlangLspServerSupportProvider.class);
        if (servers.isEmpty()) return "Waiting for slangd";
        if (servers.stream().allMatch(server -> server.getInitializeResult() != null
                && !SlangPreprocessorTrace.isSupported(server.getInitializeResult().getCapabilities())))
            return "This slangd does not support branch traces";
        String selected = SlangProjectSettings.getInstance(project).getPreprocessorContext(target.getPath());
        String variant = SlangProjectSettings.getInstance(project).getShaderVariant(target.getPath());
        if (variant != null) return status.getOrDefault(target.getPath(), "Variant: " + variant + " — awaiting trace");
        return status.getOrDefault(target.getPath(), selected == null ? "Auto — awaiting trace" : "Selected: " + selected);
    }

    public String relative(Path file) {
        Path base = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        return base != null && file.startsWith(base) ? base.relativize(file).toString() : file.toString();
    }

    private Snapshot scan(long requestedEpoch) {
        try {
            Set<Path> roots = new TreeSet<>();
            Set<Path> workspaceDirectories = new TreeSet<>();
            ReadAction.run(() -> ProjectFileIndex.getInstance(project).iterateContent(file -> {
                check(requestedEpoch);
                if (!file.isDirectory() && file.isInLocalFileSystem() && SlangBranchDisplayService.isShaderDependency(file.getPath())) {
                    workspaceDirectories.add(path(file).getParent());
                    if ("slang".equalsIgnoreCase(file.getExtension())) roots.add(path(file));
                    if (workspaceDirectories.size() + roots.size() > MAX_FILES) throw new ScanLimit();
                }
                return true;
            }));
            SlangWorkspaceConfiguration config = new SlangWorkspaceConfiguration(project);
            ConfigurationItem pathsItem = new ConfigurationItem();
            pathsItem.setSection("slang.additionalSearchPaths");
            LinkedHashSet<Path> searchPaths = new LinkedHashSet<>();
            Object configured = config.get(pathsItem);
            if (configured instanceof List<?> list) for (Object entry : list) {
                if (!(entry instanceof String value)) continue;
                try {
                    Path directory = Path.of(value);
                    if (!directory.isAbsolute() && project.getBasePath() != null) directory = Path.of(project.getBasePath()).resolve(directory);
                    searchPaths.add(directory.toAbsolutePath().normalize());
                } catch (IllegalArgumentException ignored) { }
            }
            ConfigurationItem searchItem = new ConfigurationItem();
            searchItem.setSection("slang.searchInAllWorkspaceDirectories");
            if (!Boolean.FALSE.equals(config.get(searchItem))) searchPaths.addAll(workspaceDirectories);
            else ReadAction.run(() -> {
                for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles())
                    if (SlangSelectContextAction.supports(file)) searchPaths.add(path(file).getParent());
            });
            long[] bytes = {0};
            int[] probes = {0}, files = {0};
            boolean[] limited = {false};
            var graph = new SlangIncludeGraph(roots, searchPaths, file -> {
                check(requestedEpoch);
                if (!Files.isRegularFile(file)) return null;
                if (++files[0] > MAX_FILES) throw new ScanLimit();
                try {
                    if (Files.size(file) > 1024 * 1024) { limited[0] = true; return null; }
                    VirtualFile virtual = LocalFileSystem.getInstance().findFileByNioFile(file);
                    String text = ReadAction.compute(() -> {
                        var document = virtual == null ? null : FileDocumentManager.getInstance().getCachedDocument(virtual);
                        return document == null ? null : document.getText();
                    });
                    if (text == null) text = virtual == null ? Files.readString(file)
                            : new String(virtual.contentsToByteArray(), virtual.getCharset());
                    bytes[0] += text.length() * 2L;
                    if (bytes[0] > MAX_BYTES) throw new ScanLimit();
                    return text;
                } catch (IOException | SecurityException ignored) { limited[0] = true; return null; }
            }, () -> {
                check(requestedEpoch);
                if (++probes[0] > MAX_ATTEMPTS) throw new ScanLimit();
            });
            return new Snapshot(requestedEpoch, graph, limited[0]);
        } catch (ScanLimit ignored) {
            return new Snapshot(requestedEpoch, null, true);
        }
    }

    private void check(long requestedEpoch) {
        ProgressManager.checkCanceled();
        if (disposed || project.isDisposed() || requestedEpoch != epoch || Thread.currentThread().isInterrupted())
            throw new ProcessCanceledException();
    }

    private void updateWidget() {
        if (!ApplicationManager.getApplication().isDispatchThread() || project.isDisposed()) return;
        var bar = WindowManager.getInstance().getStatusBar(project);
        if (bar != null) bar.updateWidget(SlangContextWidgetFactory.ID);
    }

    @Override public void dispose() { disposed = true; epoch++; previews.clear(); }
    private static final class ScanLimit extends RuntimeException {}
}
