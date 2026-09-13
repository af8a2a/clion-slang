package dev.slang.intellij.lsp;

import com.google.gson.JsonParser;
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.impl.features.documentation.LspDocumentationDataKt;
import org.eclipse.lsp4j.MarkupContent;

import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Standalone rendering probe using the installed IDE's LSP splitter and doc Markdown converter. */
public class SlangStructHoverRenderProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var samples = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class[]{Project.class},
                (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : null);
        int index = 0;
        for (var entry : samples.entrySet()) {
            String source = entry.getValue().getAsString();
            for (boolean dark : new boolean[]{false, true}) {
                var style = dark ? new SlangStructHoverPresentation.Style("#cf8e6d", "#bcbec4", "#bcbec4", "#2aacb8", "#bcbec4")
                        : new SlangStructHoverPresentation.Style("#0f54d6", "#300073", "#6b2fba", "#ab2f6b", "#383838");
                String markdown = SlangStructHoverPresentation.format(source, style, Locale.SIMPLIFIED_CHINESE);
                markdown = SlangFieldHoverPresentation.format(markdown, style,
                        dark ? "#bcbec4" : "#6b2fba", dark ? "#bcbec4" : "#300073", Locale.SIMPLIFIED_CHINESE);
                if (markdown.equals(source)) throw new AssertionError("Unrecognized layout hover: " + entry.getKey());
                var data = LspDocumentationDataKt.createLspDocumentationData(new MarkupContent("markdown", markdown));
                if (data.getDefinitionCodeBlock() != null) throw new AssertionError("Unexpected separately bordered signature");
                String converted = DocMarkdownToHtmlConverter.convert(project, data.getDescription());
                String foreground = dark ? "#bcbec4" : "#000000";
                String background = dark ? "#2b2d30" : "#ffffff";
                String page = "<html><head><meta charset=\"utf-8\"><style>body{font-family:'Microsoft YaHei UI';font-size:16px;color:"
                        + foreground + ";background:" + background + ";margin:16px;}pre{font-family:Consolas,monospace;font-size:17px;}"
                        + "p{margin-top:18px;margin-bottom:12px;}code{font-family:Consolas,monospace;font-size:100%;background:"
                        + (dark ? "#393b40" : "#f1f1f2") + ";}a{text-decoration:none;}hr{border:0;border-top:1px solid "
                        + (dark ? "#43454a" : "#e5e5e8") + ";}</style></head><body>" + converted + "</body></html>";
                String name = index + (dark ? "-dark" : "-light");
                Files.writeString(output.resolve(name + ".html"), page, StandardCharsets.UTF_8);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        JEditorPane pane = new JEditorPane();
                        pane.setEditorKit(new HTMLEditorKit());
                        pane.setEditable(false);
                        pane.setText(page);
                        pane.setSize(560, 1200);
                        Dimension preferred = pane.getPreferredSize();
                        pane.setSize(560, preferred.height);
                        BufferedImage image = new BufferedImage(560, preferred.height, BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        graphics.setColor(Color.decode(background)); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                        pane.paint(graphics); graphics.dispose();
                        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                });
            }
            index++;
        }
        System.out.println("Rendered " + index + " real layout hovers in light and dark palettes: " + output);
        System.exit(0);
    }
}
