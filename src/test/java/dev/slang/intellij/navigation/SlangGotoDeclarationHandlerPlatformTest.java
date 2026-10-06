package dev.slang.intellij.navigation;

import com.intellij.codeInsight.navigation.CtrlMouseData;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.ExtensionTestUtil;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.platform.lsp.api.LspServerDescriptor;
import java.util.HashMap;
import java.util.Map;
import dev.slang.intellij.lsp.SlangLspServerSupportProvider;
import kotlin.jvm.functions.Function1;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SlangGotoDeclarationHandlerPlatformTest extends BasePlatformTestCase {
    private static final String SOURCE = """
            float twice(float value) { return value * 2.0; }
            float useTwice(float value) { return twice(value); }
            """;

    private final AtomicReference<DefinitionParams> receivedDefinition = new AtomicReference<>();
    private final AtomicReference<Location> definitionResult = new AtomicReference<>();

    private LspServerDescriptor descriptor;
    private LspServer server;
    private PsiFile file;
    private Editor editor;
    private int targetOffset;
    private int callOffset;
    private SlangGotoDeclarationHandler handler;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        ExtensionTestUtil.maskExtensions(LspServerSupportProvider.Companion.getEP_NAME(),
                List.of(), getTestRootDisposable());

        // Fixture files live in TempFileSystem, not on disk. Preserve the LSP URI mapping
        // explicitly instead of asking LocalFileSystem to resolve /src/Definition.slang.
        Map<String, VirtualFile> fixtureFiles = new HashMap<>();
        descriptor = new LspServerDescriptor(getProject(), "Test Slang") {
            @Override public boolean isSupportedFile(VirtualFile file) {
                return "slang".equals(file.getExtension());
            }
            @Override public String getFileUri(VirtualFile file) {
                String uri = super.getFileUri(file);
                fixtureFiles.put(uri, file);
                return uri;
            }
            @Override public VirtualFile findFileByUri(String uri) {
                return fixtureFiles.get(uri);
            }
        };
        server = createServer();
        handler = new SlangGotoDeclarationHandler(project -> List.of(server));
        ExtensionTestUtil.maskExtensions(GotoDeclarationHandler.EP_NAME, List.of(handler), getTestRootDisposable());

        PsiFile projectFile = myFixture.addFileToProject("Definition.slang", SOURCE);
        myFixture.configureFromExistingVirtualFile(projectFile.getVirtualFile());
        file = myFixture.getFile();
        editor = myFixture.getEditor();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        targetOffset = SOURCE.indexOf("twice");
        callOffset = SOURCE.lastIndexOf("twice");
        VirtualFile virtualFile = file.getVirtualFile();
        definitionResult.set(new Location(
                descriptor.getFileUri(virtualFile),
                new Range(new Position(0, 6), new Position(0, 11))
        ));
    }

    public void testMacroBodyCtrlHoverUnderlinesOnlyTheIdentifier() throws Exception {
        String source = "#define VALUE resolveDescriptor(parameters.source)\n";
        myFixture.configureByText("MacroNavigation.slang", source);
        PsiFile macroFile = myFixture.getFile();
        Editor macroEditor = myFixture.getEditor();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        for (String name : List.of("VALUE", "resolveDescriptor", "parameters", "source")) {
            int start = source.indexOf(name);
            PsiElement leaf = macroFile.findElementAt(start + 1);
            assertNotNull(leaf);
            assertEquals(new TextRange(start, start + name.length()), leaf.getTextRange());
            CtrlMouseData data = ReadAction.nonBlocking(() ->
                            new GotoDeclarationAction().getCtrlMouseData(macroEditor, macroFile, start + 1))
                    .withDocumentsCommitted(getProject())
                    .submit(AppExecutorUtil.getAppExecutorService()).get(10, TimeUnit.SECONDS);
            assertNotNull(data);
            assertEquals(List.of(new TextRange(start, start + name.length())), data.getRanges());
        }
    }

    public void testCtrlHoverUsesTheRegisteredHandlerAndExactIdentifierRange() throws Exception {
        int hoverOffset = callOffset + 2;
        CtrlMouseData data = ReadAction.nonBlocking(() ->
                        new GotoDeclarationAction().getCtrlMouseData(editor, file, hoverOffset))
                .withDocumentsCommitted(getProject())
                .submit(AppExecutorUtil.getAppExecutorService())
                .get(10, TimeUnit.SECONDS);

        assertNotNull(data);
        assertTrue(data.isNavigatable());
        assertEquals(List.of(new TextRange(callOffset, callOffset + "twice".length())), data.getRanges());

        DefinitionParams request = receivedDefinition.get();
        assertNotNull(request);
        assertEquals(positionAt(editor.getDocument(), hoverOffset), request.getPosition());
    }

    public void testWordEndCaretIsClampedInsideTheIdentifierAndReturnsTheDeclaration() {
        int wordEndOffset = callOffset + "twice".length();
        PsiElement sourceElement = file.findElementAt(callOffset);
        assertNotNull(sourceElement);

        PsiElement[] targets = handler.getGotoDeclarationTargets(
                sourceElement,
                wordEndOffset,
                editor
        );

        assertNotNull("Definition request was not issued", receivedDefinition.get());
        assertNotNull("Fixture URI did not resolve: " + definitionResult.get().getUri(),
                descriptor.findFileByUri(definitionResult.get().getUri()));
        assertNotNull(targets);
        assertEquals(1, targets.length);
        assertEquals("twice", targets[0].getText());
        assertEquals(targetOffset, targets[0].getTextOffset());
        assertEquals(
                positionAt(editor.getDocument(), wordEndOffset - 1),
                receivedDefinition.get().getPosition()
        );
    }

    public void testDeclarationCtrlHoverKeepsSelfTargetAndExactNameRange() throws Exception {
        String source = "struct Item { float field; };\nfloat globalValue;\n"
                + "float f(float parameter) { float localValue = parameter; return localValue; }\n";
        myFixture.configureByText("Declarations.slang", source);
        PsiFile declarationFile = myFixture.getFile();
        Editor declarationEditor = myFixture.getEditor();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        for (String name : List.of("field", "globalValue", "parameter", "localValue")) {
            int start = source.indexOf(name);
            definitionResult.set(new Location(descriptor.getFileUri(declarationFile.getVirtualFile()),
                    new Range(positionAt(declarationEditor.getDocument(), start),
                            positionAt(declarationEditor.getDocument(), start + name.length()))));
            CtrlMouseData data = ReadAction.nonBlocking(() ->
                            new GotoDeclarationAction().getCtrlMouseData(declarationEditor, declarationFile, start + 1))
                    .withDocumentsCommitted(getProject())
                    .submit(AppExecutorUtil.getAppExecutorService()).get(10, TimeUnit.SECONDS);
            assertNotNull(name, data);
            assertTrue(name, data.isNavigatable());
            assertEquals(List.of(new TextRange(start, start + name.length())), data.getRanges());
            PsiElement[] targets = handler.getGotoDeclarationTargets(
                    declarationFile.findElementAt(start), start + name.length(), declarationEditor);
            assertNotNull(targets);
            assertEquals(1, targets.length);
            assertEquals(start, targets[0].getTextOffset());
            assertEquals(name, targets[0].getText());
        }
    }

    private LspServer createServer() {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setDefinitionProvider(true);
        capabilities.setPositionEncoding("utf-16");
        InitializeResult initializeResult = new InitializeResult(capabilities);
        LanguageServer languageServer = createLanguageServer();

        return (LspServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{LspServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeSlangLspServer";
                    case "getProviderClass" -> SlangLspServerSupportProvider.class;
                    case "getProject" -> getProject();
                    case "getDescriptor" -> descriptor;
                    case "getState" -> LspServerState.Running;
                    case "getInitializeResult" -> initializeResult;
                    case "getDocumentIdentifier" -> new TextDocumentIdentifier(
                            descriptor.getFileUri((VirtualFile) arguments[0])
                    );
                    case "getDocumentVersion" -> 1;
                    case "sendRequestSync" -> executeRequest(arguments, languageServer);
                    case "sendNotification" -> null;
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
    }

    private LanguageServer createLanguageServer() {
        TextDocumentService textDocuments = (TextDocumentService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{TextDocumentService.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("definition")) {
                        receivedDefinition.set((DefinitionParams) arguments[0]);
                        Either<List<? extends Location>, List<? extends LocationLink>> response =
                                Either.forLeft(List.of(definitionResult.get()));
                        return CompletableFuture.completedFuture(response);
                    }
                    return objectMethodOrUnsupported(proxy, method.getName(), arguments, method.toString());
                }
        );

        return (LanguageServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{LanguageServer.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getTextDocumentService")) {
                        return textDocuments;
                    }
                    return objectMethodOrUnsupported(proxy, method.getName(), arguments, method.toString());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static Object executeRequest(Object[] arguments, LanguageServer languageServer) {
        Function1<LanguageServer, CompletableFuture<Object>> request =
                (Function1<LanguageServer, CompletableFuture<Object>>) arguments[1];
        return request.invoke(languageServer).join();
    }

    private static Object objectMethodOrUnsupported(
            Object proxy,
            String methodName,
            Object[] arguments,
            String description
    ) {
        return switch (methodName) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + " proxy";
            default -> throw new UnsupportedOperationException(description);
        };
    }

    private static Position positionAt(Document document, int offset) {
        int line = document.getLineNumber(offset);
        return new Position(line, offset - document.getLineStartOffset(line));
    }
}
