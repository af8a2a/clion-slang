package dev.slang.intellij.preprocessor;

import com.intellij.openapi.Disposable;
import com.intellij.ProjectTopics;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerManagerListener;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.slang.intellij.lsp.SlangLanguageServer;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import dev.slang.intellij.lsp.SlangPreprocessorTrace;
import dev.slang.intellij.settings.SlangProjectSettings;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Debounced project-wide invalidation; requests run off EDT, decorations are owned on EDT. */
@Service(Service.Level.PROJECT)
public final class SlangBranchDisplayService implements Disposable {
    private static final Logger LOG = Logger.getInstance(SlangBranchDisplayService.class);
    private static final int DEBOUNCE_MS = 350;
    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final Map<Document, Entry> entries = new IdentityHashMap<>();
    private final Map<Editor, SlangBranchDecorations> decorations = new IdentityHashMap<>();
    private final SlangBranchSyncBarrier awaitingSync = new SlangBranchSyncBarrier();
    private long generation;
    private boolean restartNeeded;
    private volatile boolean disposed;

    private static final class Entry {
        Future<?> request;
    }

    private record ContextStamp(VirtualFile file, Document document, String uri, int version,
                                long fileStamp, long documentStamp) {
        boolean isCurrent() {
            return file.isValid() && file.getModificationStamp() == fileStamp
                    && (document == null || document.getModificationStamp() == documentStamp);
        }
    }

    public static SlangBranchDisplayService getInstance(Project project) {
        return project.getService(SlangBranchDisplayService.class);
    }

    public SlangBranchDisplayService(Project project) {
        this.project = project;
        var connection = project.getMessageBus().connect(this);
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
            @Override public void rootsChanged(@NotNull ModuleRootEvent event) {
                ui(() -> { restartNeeded = true; invalidate(); });
            }
        });
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void fileOpened(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
                if (isSlangFile(file)) refresh();
            }
            @Override public void fileClosed(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
                if (isSlangFile(file)) refresh();
            }
        });
        var appConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
        appConnection.subscribe(EditorColorsManager.TOPIC, scheme -> refresh());
        appConnection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(@NotNull List<? extends VFileEvent> events) {
                ui(() -> filesChanged(events));
            }
        });
        EditorFactory factory = EditorFactory.getInstance();
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
                if (event.getEditor().getProject() == project) refresh();
            }
            @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
                if (event.getEditor().getProject() == project) {
                    ui(() -> {
                        var owned = decorations.remove(event.getEditor());
                        if (owned != null) owned.dispose();
                        invalidate();
                    });
                }
            }
        }, this);
        factory.getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                Document document = event.getDocument();
                long stamp = document.getModificationStamp();
                ui(() -> {
                    if (hasEditor(document) && isSlangFile(FileDocumentManager.getInstance().getFile(document))) {
                        awaitingSync.edited(document, stamp);
                        invalidate();
                    }
                });
            }
        }, this);
        LspServerManager.getInstance(project).addLspServerManagerListener(new LspServerManagerListener() {
            @Override public void serverStateChanged(@NotNull LspServer server) {
                if (isOurServer(server)) ui(() -> {
                    if (server.getState() != LspServerState.Running) awaitingSync.clear();
                    invalidate();
                });
            }
            @Override public void fileOpened(@NotNull LspServer server, @NotNull VirtualFile file) {
                synchronizedFile(server, file);
            }
            @Override public void fileEdited(@NotNull LspServer server, @NotNull VirtualFile file) {
                synchronizedFile(server, file);
            }
        }, this, true);
        refresh();
    }

    private void synchronizedFile(LspServer server, VirtualFile file) {
        if (!isOurServer(server)) return;
        ReadAction.run(() -> {
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document == null) return;
            long stamp = document.getModificationStamp();
            // Native fileEdited is emitted *before* didChange and may precede our DocumentListener.
            // Always defer, even on EDT, until all listeners and native incremental sends have run.
            awaitingSync.synchronizedLater(document, stamp, document::getModificationStamp,
                    task -> ApplicationManager.getApplication().invokeLater(() -> ui(task), ModalityState.any()),
                    this::invalidate);
        });
    }

    /** Settings and color-scheme changes use the same cleanup path as document edits. */
    public void refresh() { ui(this::invalidate); }

    private void invalidate() {
        generation++;
        SlangContextService.getInstance(project).invalidate();
        alarm.cancelAllRequests();
        for (Entry entry : entries.values()) {
            if (entry.request != null) entry.request.cancel(true);
        }
        entries.clear();
        clearDecorations();
        alarm.addRequest(this::requestOpenDocuments, DEBOUNCE_MS, ModalityState.any());
    }

    private void requestOpenDocuments() {
        if (disposed || project.isDisposed()) return;
        var settings = SlangProjectSettings.getInstance(project);
        if (!settings.isShowPreprocessorBranches()) return;
        LspServer server = runningServer();
        if (server == null) return;
        if (restartNeeded) {
            restartNeeded = false;
            awaitingSync.clear();
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(SlangLspServerSupportProvider.class);
            return;
        }
        Map<Document, VirtualFile> documents = new IdentityHashMap<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (!eligibleEditor(editor)) continue;
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (isSlangFile(file) && file.isValid() && file.isInLocalFileSystem()) documents.put(editor.getDocument(), file);
        }
        awaitingSync.retain(documents.keySet());
        // Dependencies may change while the root's own version stays unchanged.
        if (!awaitingSync.isReady()) return;
        for (var item : documents.entrySet()) request(server, item.getKey(), item.getValue());
    }

    private void request(LspServer server, Document document, VirtualFile file) {
        var identifier = server.getDocumentIdentifier(file);
        var stamp = new SlangBranchRequestStamp(server, identifier.getUri(), server.getDocumentVersion(document),
                document.getModificationStamp(), generation);
        Entry entry = new Entry();
        entries.put(document, entry);
        entry.request = AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                var contexts = SlangContextService.getInstance(project);
                boolean supportsContexts = SlangPreprocessorTrace.supportsContexts(server.getInitializeResult().getCapabilities());
                String pinned = SlangProjectSettings.getInstance(project).getPreprocessorContext(file.getPath());
                if (!supportsContexts && pinned != null
                        && !java.nio.file.Path.of(pinned).equals(SlangContextService.path(file))) {
                    report(document, entry, file, "Selected context requires M4c slangd");
                    return;
                }
                var root = supportsContexts ? contexts.resolve(file) : SlangContextService.path(file);
                VirtualFile rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
                if (rootFile == null || !rootFile.isValid()) {
                    report(document, entry, file, "Selected root is missing: " + contexts.relative(root));
                    return;
                }
                ContextStamp context = ReadAction.compute(() -> {
                    Document rootDocument = FileEditorManager.getInstance(project).isFileOpen(rootFile)
                            ? FileDocumentManager.getInstance().getCachedDocument(rootFile) : null;
                    return new ContextStamp(rootFile, rootDocument, server.getDocumentIdentifier(rootFile).getUri(),
                            rootDocument == null ? -1 : server.getDocumentVersion(rootDocument),
                            rootFile.getModificationStamp(), rootDocument == null ? -1 : rootDocument.getModificationStamp());
                });
                SlangPreprocessorTrace trace = server.sendRequestSync(5_000, remote ->
                        remote instanceof SlangLanguageServer slang
                                ? slang.preprocessorTrace(new SlangPreprocessorTrace.Params(identifier, supportsContexts ? context.uri : null))
                                : CompletableFuture.completedFuture(null));
                if (supportsContexts && (trace == null || !trace.matchesContext(context.uri, context.version))) {
                    String reason = trace == null ? "Trace unavailable" : switch (String.valueOf(trace.status())) {
                        case "notIncluded" -> "Not included under this root's macros";
                        case "ambiguous" -> "Included " + trace.occurrenceCount() + " times — occurrence selection not yet supported";
                        default -> "Context trace unavailable or stale";
                    };
                    report(document, entry, file, contexts.relative(root) + " — " + reason);
                    return;
                }
                SlangBranchPresentation presentation = ReadAction.compute(() ->
                        document.getModificationStamp() == stamp.documentStamp()
                                && trace != null && stamp.uri().equals(trace.uri()) && stamp.version() == trace.version()
                                ? SlangBranchPresentation.create(document, trace) : null);
                ui(() -> {
                    if (entries.get(document) != entry || !file.isValid() || !context.isCurrent() || !awaitingSync.isReady()
                            || !SlangProjectSettings.getInstance(project).isShowPreprocessorBranches()
                            || !stamp.accepts(trace, runningServer(), server.getDocumentVersion(document),
                            document.getModificationStamp(), generation) || presentation == null) return;
                    contexts.report(file, (pinned == null ? "Auto: " : "") + contexts.relative(root)
                            + (supportsContexts ? "" : " (current-file only; M4c slangd required for contexts)"));
                    for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) {
                        if (!eligibleEditor(editor)) continue;
                        var old = decorations.remove(editor);
                        if (old != null) old.dispose();
                        decorations.put(editor, new SlangBranchDecorations(editor, presentation,
                                SlangProjectSettings.getInstance(project).isShowPreprocessorBranchLabels()));
                    }
                });
            } catch (ProcessCanceledException ignored) {
                // Superseded request, closed editor or disposed project.
            } catch (RuntimeException exception) {
                LOG.debug("slangd preprocessor trace unavailable for " + file.getPath(), exception);
                report(document, entry, file, "Context trace unavailable; retry on edit or selection");
            }
        });
    }

    private void report(Document document, Entry entry, VirtualFile file, String message) {
        ui(() -> { if (entries.get(document) == entry) SlangContextService.getInstance(project).report(file, message); });
    }

    private void filesChanged(List<? extends VFileEvent> events) {
        if (events.stream().anyMatch(SlangBranchDisplayService::affectsContexts))
            SlangContextService.getInstance(project).invalidate();
        if (runningServer() == null || !SlangProjectSettings.getInstance(project).isShowPreprocessorBranches()) return;
        for (VFileEvent event : events) {
            if (!affectsContexts(event)) continue;
            VirtualFile file = event.getFile();
            Document document = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
            // Native LSP already sent didChange for a saved, opened Slang document.
            if (event instanceof VFileContentChangeEvent content && content.isFromSave()
                    && isSlangFile(file) && document != null && hasEditor(document)) continue;
            // M4a slangd has no watched-files invalidation. Restart to avoid a cached old include/config.
            restartNeeded = true;
            invalidate();
            break;
        }
    }

    private static boolean affectsContexts(VFileEvent event) {
        return isShaderDependency(event.getPath()) || event.getFile() != null && event.getFile().isDirectory()
                || event instanceof VFilePropertyChangeEvent property && VirtualFile.PROP_NAME.equals(property.getPropertyName())
                && isShaderDependency(String.valueOf(property.getOldValue()));
    }

    static boolean isShaderDependency(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("slangdconfig.json") || name.endsWith(".slang") || name.endsWith(".slangh")
                || name.endsWith(".hlsl") || name.endsWith(".hlsli") || name.endsWith(".h")
                || name.endsWith(".hpp") || name.endsWith(".inc");
    }

    private LspServer runningServer() {
        for (LspServer server : LspServerManager.getInstance(project).getServersForProvider(SlangLspServerSupportProvider.class)) {
            var result = server.getInitializeResult();
            if (server.getState() == LspServerState.Running && result != null
                    && SlangPreprocessorTrace.isSupported(result.getCapabilities())) return server;
        }
        return null;
    }

    private static boolean isOurServer(LspServer server) {
        return server.getProviderClass() == SlangLspServerSupportProvider.class;
    }

    private static boolean isSlangFile(VirtualFile file) {
        return file != null && ("slang".equalsIgnoreCase(file.getExtension()) || "slangh".equalsIgnoreCase(file.getExtension()));
    }

    private boolean eligibleEditor(Editor editor) {
        return !editor.isDisposed() && editor.getProject() == project && editor.getEditorKind() == EditorKind.MAIN_EDITOR;
    }

    private boolean hasEditor(Document document) {
        for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) {
            if (eligibleEditor(editor)) return true;
        }
        return false;
    }

    private void clearDecorations() {
        for (var owned : decorations.values()) owned.dispose();
        decorations.clear();
    }

    private void ui(Runnable action) {
        if (disposed || project.isDisposed()) return;
        Runnable guarded = () -> { if (!disposed && !project.isDisposed()) action.run(); };
        if (ApplicationManager.getApplication().isDispatchThread()) guarded.run();
        else ApplicationManager.getApplication().invokeLater(guarded, ModalityState.any());
    }

    @Override public void dispose() {
        disposed = true;
        alarm.cancelAllRequests();
        Runnable cleanup = () -> {
            for (Entry entry : entries.values()) if (entry.request != null) entry.request.cancel(true);
            entries.clear();
            awaitingSync.clear();
            clearDecorations();
        };
        if (ApplicationManager.getApplication().isDispatchThread()) cleanup.run();
        else ApplicationManager.getApplication().invokeLater(cleanup, ModalityState.any());
    }
}
