package dev.slang.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.FakePsiElement;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import org.eclipse.lsp4j.DefinitionOptions;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bridges CLion's declaration action directly to slangd.
 *
 * <p>CLion 2026.1's implicit Native LSP reference provider only recognizes a
 * declaration request while a regular {@code GotoDeclarationAction} is being
 * executed. Ctrl+mouse hover bypasses that action context, so the platform never
 * asks slangd for a definition. A language-specific handler uses the public LSP
 * API for both Ctrl+hover and Ctrl+click while continuing to share the project-wide
 * server managed by {@link SlangLspServerSupportProvider}.</p>
 */
public final class SlangGotoDeclarationHandler implements GotoDeclarationHandler {
    private static final Logger LOG = Logger.getInstance(SlangGotoDeclarationHandler.class);
    private static final int REQUEST_TIMEOUT_MS = 2_500;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final long POSITIVE_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long NEGATIVE_CACHE_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final String UTF_8 = "utf-8";
    private static final String UTF_32 = "utf-32";

    private final Map<CacheKey, CacheEntry> definitionCache = new ConcurrentHashMap<>();

    @Override
    public @Nullable PsiElement[] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            @NotNull Editor editor
    ) {
        Project project = sourceElement != null ? sourceElement.getProject() : editor.getProject();
        if (project == null || project.isDisposed()) {
            return null;
        }

        Document sourceDocument = editor.getDocument();
        VirtualFile sourceFile = FileDocumentManager.getInstance().getFile(sourceDocument);
        if (sourceFile == null || !isSlangFile(sourceFile)) {
            return null;
        }

        int sourceOffset = normalizeSourceOffset(sourceDocument, offset, editor);
        TextRange identifierRange = identifierRangeAt(sourceDocument.getCharsSequence(), sourceOffset);
        if (identifierRange == null) {
            return null;
        }
        int requestOffset = requestOffsetForIdentifier(identifierRange, sourceOffset);

        Collection<LspServer> servers = LspServerManager.getInstance(project)
                .getServersForProvider(SlangLspServerSupportProvider.class);
        for (LspServer server : servers) {
            if (server.getState() != LspServerState.Running
                    || !server.getDescriptor().isSupportedFile(sourceFile)
                    || !supportsDefinitions(server)) {
                continue;
            }

            String positionEncoding = positionEncoding(server);
            CacheKey cacheKey = new CacheKey(
                    server,
                    sourceFile.getUrl(),
                    sourceDocument.getModificationStamp(),
                    identifierRange.getStartOffset(),
                    identifierRange.getEndOffset()
            );

            long now = System.nanoTime();
            CacheEntry cached = definitionCache.get(cacheKey);
            if (cached != null && cached.expiresAtNanos() <= now) {
                definitionCache.remove(cacheKey, cached);
                cached = null;
            }

            List<TargetLocation> locations;
            if (cached != null) {
                locations = cached.locations();
            } else {
                List<TargetLocation> requested = requestDefinitions(
                        server,
                        sourceFile,
                        sourceDocument,
                        requestOffset,
                        positionEncoding
                );
                // null specifically means a transient request failure and must remain retryable.
                locations = requested == null ? List.of() : requested;
                if (requested != null) {
                    putCached(cacheKey, requested, now);
                }
            }

            PsiElement[] targets = createTargets(
                    project,
                    server,
                    locations,
                    sourceFile,
                    requestOffset,
                    positionEncoding
            );
            if (targets.length != 0) {
                return targets;
            }
        }

        return null;
    }

    private @Nullable List<TargetLocation> requestDefinitions(
            @NotNull LspServer server,
            @NotNull VirtualFile sourceFile,
            @NotNull Document sourceDocument,
            int sourceOffset,
            @NotNull String positionEncoding
    ) {
        int line = sourceDocument.getLineNumber(sourceOffset);
        Position position = new Position(line, toLspCharacter(sourceDocument, line, sourceOffset, positionEncoding));
        DefinitionParams params = new DefinitionParams(server.getDocumentIdentifier(sourceFile), position);

        try {
            Either<List<? extends Location>, List<? extends LocationLink>> response = server.sendRequestSync(
                    REQUEST_TIMEOUT_MS,
                    languageServer -> languageServer.getTextDocumentService().definition(params)
            );
            if (response == null) {
                return List.of();
            }

            List<TargetLocation> locations = new ArrayList<>();
            if (response.isLeft()) {
                List<? extends Location> plainLocations = response.getLeft();
                if (plainLocations != null) {
                    for (Location location : plainLocations) {
                        if (location != null && location.getUri() != null && location.getRange() != null) {
                            locations.add(new TargetLocation(location.getUri(), location.getRange()));
                        }
                    }
                }
            } else {
                List<? extends LocationLink> links = response.getRight();
                if (links != null) {
                    for (LocationLink link : links) {
                        if (link == null || link.getTargetUri() == null) {
                            continue;
                        }
                        Range range = link.getTargetSelectionRange();
                        if (range == null) {
                            range = link.getTargetRange();
                        }
                        if (range != null) {
                            locations.add(new TargetLocation(link.getTargetUri(), range));
                        }
                    }
                }
            }
            return List.copyOf(locations);
        } catch (ProcessCanceledException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.debug("slangd definition request failed for " + sourceFile.getPath(), exception);
            return null;
        }
    }

    private static @NotNull PsiElement[] createTargets(
            @NotNull Project project,
            @NotNull LspServer server,
            @NotNull List<TargetLocation> locations,
            @NotNull VirtualFile sourceFile,
            int sourceOffset,
            @NotNull String positionEncoding
    ) {
        Map<String, PsiElement> targets = new LinkedHashMap<>();
        PsiManager psiManager = PsiManager.getInstance(project);

        for (TargetLocation location : locations) {
            VirtualFile targetFile = server.getDescriptor().findFileByUri(location.uri());
            if (targetFile == null || !targetFile.isValid()) {
                continue;
            }

            Document targetDocument = FileDocumentManager.getInstance().getDocument(targetFile);
            PsiFile targetPsiFile = psiManager.findFile(targetFile);
            if (targetDocument == null || targetPsiFile == null) {
                continue;
            }

            int startOffset = toDocumentOffset(targetDocument, location.range().getStart(), positionEncoding);
            int endOffset = toDocumentOffset(targetDocument, location.range().getEnd(), positionEncoding);
            endOffset = Math.max(startOffset, endOffset);

            boolean sourceIsInsideTarget = sourceOffset >= startOffset
                    && (sourceOffset < endOffset || (startOffset == endOffset && sourceOffset == startOffset));
            if (targetFile.equals(sourceFile) && sourceIsInsideTarget) {
                continue;
            }

            String key = targetFile.getUrl() + ':' + startOffset + ':' + endOffset;
            targets.putIfAbsent(
                    key,
                    new SlangNavigationTarget(project, targetFile, targetPsiFile, targetDocument, startOffset, endOffset)
            );
        }

        return targets.values().toArray(PsiElement[]::new);
    }

    private static boolean supportsDefinitions(@NotNull LspServer server) {
        InitializeResult initializeResult = server.getInitializeResult();
        if (initializeResult == null) {
            return false;
        }
        ServerCapabilities capabilities = initializeResult.getCapabilities();
        if (capabilities == null) {
            return false;
        }
        Either<Boolean, DefinitionOptions> provider = capabilities.getDefinitionProvider();
        if (provider == null) {
            return false;
        }
        return provider.isRight() || Boolean.TRUE.equals(provider.getLeft());
    }

    private static @NotNull String positionEncoding(@NotNull LspServer server) {
        InitializeResult initializeResult = server.getInitializeResult();
        if (initializeResult == null || initializeResult.getCapabilities() == null) {
            return "utf-16";
        }
        String encoding = initializeResult.getCapabilities().getPositionEncoding();
        return encoding == null ? "utf-16" : encoding.toLowerCase(Locale.ROOT);
    }

    private void putCached(
            @NotNull CacheKey key,
            @NotNull List<TargetLocation> locations,
            long nowNanos
    ) {
        if (definitionCache.size() >= MAX_CACHE_ENTRIES) {
            definitionCache.clear();
        }
        long ttl = locations.isEmpty() ? NEGATIVE_CACHE_TTL_NANOS : POSITIVE_CACHE_TTL_NANOS;
        definitionCache.put(key, new CacheEntry(locations, nowNanos + ttl));
    }

    private static int normalizeSourceOffset(
            @NotNull Document document,
            int requested,
            @NotNull Editor editor
    ) {
        int textLength = document.getTextLength();
        if (requested >= 0 && requested <= textLength) {
            return requested;
        }
        int candidate = editor.getCaretModel().getOffset();
        return Math.max(0, Math.min(candidate, textLength));
    }

    private static boolean isSlangFile(@NotNull VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.equals("slang") || normalized.equals("slangh");
    }

    static @Nullable TextRange identifierRangeAt(@NotNull CharSequence text, int offset) {
        if (text.length() == 0) {
            return null;
        }

        int probe = Math.min(Math.max(offset, 0), text.length() - 1);
        if (!isIdentifierPart(text.charAt(probe)) && probe > 0 && isIdentifierPart(text.charAt(probe - 1))) {
            probe--;
        }
        if (!isIdentifierPart(text.charAt(probe))) {
            return null;
        }

        int start = probe;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = probe + 1;
        while (end < text.length() && isIdentifierPart(text.charAt(end))) {
            end++;
        }
        return new TextRange(start, end);
    }

    /** Keeps keyboard carets at an identifier boundary inside slangd's lookup token. */
    static int requestOffsetForIdentifier(@NotNull TextRange identifierRange, int sourceOffset) {
        return Math.max(
                identifierRange.getStartOffset(),
                Math.min(sourceOffset, identifierRange.getEndOffset() - 1)
        );
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isJavaIdentifierPart(value) || value == '$';
    }

    static int toLspCharacter(
            @NotNull Document document,
            int line,
            int offset,
            @NotNull String positionEncoding
    ) {
        int lineStart = document.getLineStartOffset(line);
        String prefix = document.getCharsSequence().subSequence(lineStart, offset).toString();
        if (UTF_8.equals(positionEncoding)) {
            return prefix.getBytes(StandardCharsets.UTF_8).length;
        }
        if (UTF_32.equals(positionEncoding)) {
            return prefix.codePointCount(0, prefix.length());
        }
        return prefix.length();
    }

    static int toDocumentOffset(
            @NotNull Document document,
            @Nullable Position position,
            @NotNull String positionEncoding
    ) {
        if (position == null || document.getLineCount() == 0) {
            return 0;
        }

        int line = Math.max(0, Math.min(position.getLine(), document.getLineCount() - 1));
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String lineText = document.getCharsSequence().subSequence(lineStart, lineEnd).toString();
        int character = Math.max(0, position.getCharacter());

        if (UTF_8.equals(positionEncoding)) {
            return lineStart + utf8ByteOffsetToCharOffset(lineText, character);
        }
        if (UTF_32.equals(positionEncoding)) {
            int codePoints = Math.min(character, lineText.codePointCount(0, lineText.length()));
            return lineStart + lineText.offsetByCodePoints(0, codePoints);
        }
        return lineStart + Math.min(character, lineText.length());
    }

    private static int utf8ByteOffsetToCharOffset(@NotNull String text, int byteOffset) {
        int bytes = 0;
        int charOffset = 0;
        while (charOffset < text.length()) {
            int codePoint = text.codePointAt(charOffset);
            int encodedLength;
            if (codePoint <= 0x7f) {
                encodedLength = 1;
            } else if (codePoint <= 0x7ff) {
                encodedLength = 2;
            } else if (codePoint <= 0xffff) {
                encodedLength = 3;
            } else {
                encodedLength = 4;
            }
            if (bytes + encodedLength > byteOffset) {
                break;
            }
            bytes += encodedLength;
            charOffset += Character.charCount(codePoint);
        }
        return charOffset;
    }

    private record CacheKey(
            LspServer server,
            String fileUrl,
            long modificationStamp,
            int identifierStart,
            int identifierEnd
    ) {
    }

    private record CacheEntry(@NotNull List<TargetLocation> locations, long expiresAtNanos) {
    }

    private record TargetLocation(@NotNull String uri, @NotNull Range range) {
    }

    private static final class SlangNavigationTarget extends FakePsiElement {
        private final Project project;
        private final VirtualFile file;
        private final PsiFile containingFile;
        private final int startOffset;
        private final TextRange textRange;
        private final String text;
        private final String presentableText;

        private SlangNavigationTarget(
                @NotNull Project project,
                @NotNull VirtualFile file,
                @NotNull PsiFile containingFile,
                @NotNull Document document,
                int startOffset,
                int endOffset
        ) {
            this.project = project;
            this.file = file;
            this.containingFile = containingFile;
            this.startOffset = startOffset;
            this.textRange = new TextRange(startOffset, endOffset);
            this.text = document.getCharsSequence().subSequence(startOffset, endOffset).toString();
            int line = document.getLineNumber(startOffset) + 1;
            this.presentableText = text.isBlank() ? file.getName() + ':' + line : text;
        }

        @Override
        public @NotNull Project getProject() {
            return project;
        }

        @Override
        public @NotNull PsiElement getParent() {
            return containingFile;
        }

        @Override
        public @NotNull PsiFile getContainingFile() {
            return containingFile;
        }

        @Override
        public @NotNull String getName() {
            return presentableText;
        }

        @Override
        public @NotNull String getPresentableText() {
            return presentableText;
        }

        @Override
        public @NotNull String getLocationString() {
            return file.getPresentableUrl();
        }

        @Override
        public @NotNull String getText() {
            return text;
        }

        @Override
        public @NotNull TextRange getTextRange() {
            return textRange;
        }

        @Override
        public int getTextOffset() {
            return startOffset;
        }

        @Override
        public int getTextLength() {
            return textRange.getLength();
        }

        @Override
        public boolean isValid() {
            return file.isValid() && containingFile.isValid();
        }

        @Override
        public void navigate(boolean requestFocus) {
            new OpenFileDescriptor(project, file, startOffset).navigate(requestFocus);
        }

        @Override
        public boolean canNavigate() {
            return file.isValid();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }
    }
}
