package dev.slang.intellij.navigation;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.tree.IElementType;
import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * File-local declaration indexer, independent of imports, compiler state and open editors.
 * Skips executable bodies and initializers rather than treating every identifier/call as a symbol.
 * This is a conservative declaration scanner, not a semantic Slang parser.
 */
public final class SlangSymbolExtractor {
    private static final Set<String> TYPES = Set.of("struct", "class", "interface", "enum", "cbuffer", "tbuffer");
    private static final Set<String> SKIP = Set.of("import", "__import", "__include", "implementing", "module",
            "using", "return", "if", "else", "for", "while", "switch", "case", "break", "continue",
            "discard", "throw", "static_assert");
    private static final Set<String> MODIFIERS = Set.of("public", "private", "internal", "protected", "static",
            "const", "constexpr", "extern", "export", "__exported", "inline", "forceinline", "uniform",
            "groupshared", "shared", "precise", "volatile", "row_major", "column_major", "no_diff",
            "mutating", "nonmutating", "differentiable", "virtual", "override", "final", "sealed");
    private record Token(String text, int start, int end, IElementType type) {}
    private final List<Token> tokens = new ArrayList<>();
    private final List<SlangSymbol> symbols = new ArrayList<>();
    private List<SlangSymbol.NamespaceImport> usingNamespaces = List.of();
    private List<String> typeParameters = List.of();
    private int[] close;

    private SlangSymbolExtractor(CharSequence source) {
        var lexer = new SlangLexer(); lexer.start(source);
        int count = 0;
        while (lexer.getTokenType() != null) {
            if ((count++ & 255) == 0) ProgressManager.checkCanceled();
            var type = lexer.getTokenType();
            if (type != SlangTokenTypes.WHITE_SPACE && !SlangTokenTypes.COMMENTS.contains(type)
                    && type != SlangTokenTypes.PREPROCESSOR) {
                tokens.add(new Token(source.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString(),
                        lexer.getTokenStart(), lexer.getTokenEnd(), type));
            }
            lexer.advance();
        }
        close = new int[tokens.size()]; Arrays.fill(close, -1);
        var stack = new ArrayList<Integer>();
        for (int i = 0; i < tokens.size(); i++) {
            if ((i & 255) == 0) ProgressManager.checkCanceled();
            String t = text(i);
            if (t.equals("(") || t.equals("[") || t.equals("{")) stack.add(i);
            else if (t.equals(")") || t.equals("]") || t.equals("}")) {
                if (!stack.isEmpty() && matches(text(stack.getLast()), t)) close[stack.removeLast()] = i;
                else stack.clear(); // Broken input must not connect unrelated declarations.
            }
        }
    }

    public static List<SlangSymbol> extract(CharSequence source) {
        var parser = new SlangSymbolExtractor(source);
        parser.scope(0, parser.tokens.size(), "", false, 0);
        return List.copyOf(parser.symbols);
    }

    private void scope(int start, int end, String owner, boolean member, int depth) {
        if (depth > 64) return;
        var outerUsings = usingNamespaces;
        var scopedUsings = new ArrayList<>(outerUsings);
        // Namespace imports apply to this scope, never to sibling scopes or executable bodies.
        for (int at = start; at < end; at++) {
            if ((at & 255) == 0) ProgressManager.checkCanceled();
            if (text(at).equals("{") && close[at] >= at) { at = close[at]; continue; }
            if (text(at).equals("using")) {
                int stop = boundary(at, end);
                String imported = typeName(at + 1, stop);
                if (!imported.isEmpty()) scopedUsings.add(new SlangSymbol.NamespaceImport(imported, owner));
            }
        }
        usingNamespaces = List.copyOf(scopedUsings);
        int i = start;
        while (i < end) {
            ProgressManager.checkCanceled();
            while (i < end && (text(i).equals(";") || text(i).equals("}"))) i++;
            if (i >= end) break;
            int begin = i;
            // Attributes are not declarations, even if their arguments look like function calls.
            while (i < end && text(i).equals("[") && close[i] >= i) i = close[i] + 1;
            int declaration = i;
            while (i < end && MODIFIERS.contains(text(i))) i++;
            int kindAt = i;
            String kind = text(kindAt);
            int boundary = boundary(declaration, end);
            if (boundary >= end) break;
            boolean body = text(boundary).equals("{");
            int bodyEnd = body && close[boundary] >= boundary ? close[boundary] : end;

            if (kind.equals("namespace") || TYPES.contains(kind)) {
                int nameAt = kindAt + 1;
                if (name(kindAt + 1)) {
                    int nameEnd = nameAt + 1;
                    if (kind.equals("namespace")) {
                        while (nameEnd + 1 < boundary && (text(nameEnd).equals(".") || text(nameEnd).equals("::"))
                                && name(nameEnd + 1)) nameEnd += 2;
                    }
                    String declaredName = join(nameAt, nameEnd).replace("::", ".");
                    add(nameAt, declaredName, owner, kind + " " + declaredName,
                            kind.equals("namespace") ? SlangSymbol.Kind.NAMESPACE : SlangSymbol.Kind.TYPE);
                    if (Set.of("struct", "class", "interface").contains(kind)) {
                        var symbol = symbols.removeLast();
                        symbols.add(new SlangSymbol(symbol.name(), symbol.container(), symbol.signature(),
                                symbol.kind(), symbol.offset(), withoutParameters(baseTypes(nameEnd, boundary),
                                genericParameters(nameEnd, boundary)), usingNamespaces));
                    }
                    if (body) {
                        String nested = qualify(owner, declaredName);
                        if (kind.equals("enum")) enumMembers(boundary + 1, bodyEnd, nested);
                        else {
                            var outerParameters = typeParameters;
                            var nestedParameters = new ArrayList<>(outerParameters);
                            nestedParameters.addAll(genericParameters(nameEnd, boundary));
                            typeParameters = List.copyOf(nestedParameters);
                            scope(boundary + 1, bodyEnd, nested, !kind.equals("namespace"), depth + 1);
                            typeParameters = outerParameters;
                        }
                    }
                }
            } else if (kind.equals("extension")) {
                // Extensions can start with a generic parameter list; only index their members.
                int typeAt = kindAt + 1;
                if (text(typeAt).equals("<")) typeAt = afterAngles(typeAt, boundary);
                if (body && typeAt < boundary) {
                    var outerParameters = typeParameters;
                    var extensionParameters = new ArrayList<>(outerParameters);
                    extensionParameters.addAll(genericParameters(kindAt + 1, boundary));
                    typeParameters = List.copyOf(extensionParameters);
                    scope(boundary + 1, bodyEnd, qualify(owner, join(typeAt, boundary)), true, depth + 1);
                    typeParameters = outerParameters;
                }
            } else if (!SKIP.contains(kind)) {
                if (kind.equals("typealias") || kind.equals("associatedtype")) {
                    if (name(kindAt + 1)) {
                        add(kindAt + 1, text(kindAt + 1), owner, join(declaration, boundary), SlangSymbol.Kind.ALIAS);
                        if (kind.equals("typealias")) {
                            int afterName = kindAt + 2;
                            int equals = text(afterName).equals("<") ? afterAngles(afterName, boundary) : afterName;
                            if (text(equals).equals("=")) aliasTarget(typeName(equals + 1, boundary),
                                    genericParameters(afterName, boundary));
                        }
                    }
                } else if (kind.equals("typedef")) {
                    int alias = lastName(kindAt + 1, boundary);
                    if (alias >= 0) {
                        add(alias, text(alias), owner, join(declaration, boundary), SlangSymbol.Kind.ALIAS);
                        // Arrays/pointers/functions are not aliases of the underlying interface/type.
                        if (alias + 1 == boundary) aliasTarget(typeName(kindAt + 1, alias), List.of());
                    }
                } else {
                    int paren = functionParen(declaration, boundary);
                    int function = paren < 0 ? -1 : functionName(paren, declaration);
                    String shortOwner = owner.substring(owner.lastIndexOf('.') + 1);
                    if (function >= 0 && (function > kindAt || text(function).equals(shortOwner)
                            || Set.of("init", "__init").contains(text(function)))) {
                        add(function, text(function), owner, join(function, close[paren] + 1), SlangSymbol.Kind.FUNCTION);
                    } else if (!body || hasEquals(declaration, boundary)) {
                        variables(declaration, boundary, owner, member);
                    }
                }
            }
            i = body ? bodyEnd + 1 : boundary + 1;
            if (i <= begin) i = begin + 1;
        }
        usingNamespaces = outerUsings;
    }

    private void aliasTarget(String target, List<String> parameters) {
        if (target.isEmpty()) return;
        var symbol = symbols.removeLast();
        symbols.add(new SlangSymbol(symbol.name(), symbol.container(), symbol.signature(), symbol.kind(),
                symbol.offset(), withoutParameters(List.of(target), parameters), usingNamespaces));
    }

    private List<String> withoutParameters(List<String> names, List<String> localParameters) {
        return names.stream().filter(n -> {
            String first = n.split("\\.", 2)[0];
            return !typeParameters.contains(first) && !localParameters.contains(first);
        }).toList();
    }

    private List<String> genericParameters(int start, int end) {
        if (!text(start).equals("<")) return List.of();
        var result = new ArrayList<String>();
        int level = 1;
        boolean expected = true;
        for (int i = start + 1; i < end && level > 0; i++) {
            String token = text(i);
            if (level == 1 && expected && name(i)) { result.add(token); expected = false; }
            if (token.equals("<")) level++;
            else if (token.equals(">")) level--;
            else if (token.equals(">>")) level -= 2;
            else if (token.equals(",") && level == 1) expected = true;
        }
        return result;
    }

    /** Erase generic arguments while retaining nested qualified names; reject other type expressions. */
    private String typeName(int start, int end) {
        var result = new StringBuilder();
        int i = start;
        while (i < end && name(i)) {
            if (!result.isEmpty()) result.append('.');
            result.append(text(i++));
            if (text(i).equals("<")) {
                int angle = 0;
                do {
                    String token = text(i++);
                    if (token.equals("<")) angle++;
                    else if (token.equals(">")) angle--;
                    else if (token.equals(">>")) angle -= 2;
                } while (i < end && angle > 0);
                if (angle != 0) return "";
            }
            if (i == end) return result.toString();
            if (!text(i).equals(".") && !text(i).equals("::")) return "";
            i++;
        }
        return "";
    }

    private int boundary(int start, int end) {
        for (int i = start; i < end; i++) {
            if ((i & 255) == 0) ProgressManager.checkCanceled();
            String t = text(i);
            if (t.equals(";") || t.equals("{") || t.equals("}")) return i;
            if ((t.equals("(") || t.equals("[")) && close[i] >= i) i = close[i];
        }
        return end;
    }

    private List<String> baseTypes(int start, int end) {
        // Constraints inside <T : IFoo> describe T, not the enclosing type.
        if (text(start).equals("<")) start = afterAngles(start, end);
        if (!text(start).equals(":")) return List.of();
        var result = new ArrayList<String>();
        int i = start + 1;
        while (i < end && name(i)) {
            var base = new StringBuilder(text(i++));
            if (text(i).equals("<")) i = afterAngles(i, end);
            while (i + 1 < end && (text(i).equals(".") || text(i).equals("::")) && name(i + 1)) {
                base.append('.').append(text(i + 1));
                i += 2;
                if (text(i).equals("<")) i = afterAngles(i, end);
            }
            result.add(base.toString());
            if (!text(i).equals(",")) break;
            i++;
        }
        return List.copyOf(result);
    }

    private int functionParen(int start, int end) {
        int angle = 0;
        for (int i = start; i < end; i++) {
            String t = text(i);
            if (t.equals("<")) angle++;
            else if (t.equals(">")) angle = Math.max(0, angle - 1);
            else if (t.equals(">>")) angle = Math.max(0, angle - 2);
            else if (t.equals("=") && angle == 0) return -1;
            else if (t.equals("[") && close[i] >= i) i = close[i];
            else if (t.equals("(") && close[i] >= i) {
                if (angle == 0) return i;
                i = close[i];
            }
        }
        return -1;
    }

    private int functionName(int paren, int start) {
        int i = paren - 1;
        if (text(i).equals(">") || text(i).equals(">>")) {
            int depth = 0;
            for (; i >= start; i--) {
                if (text(i).equals(">")) depth++;
                else if (text(i).equals(">>")) depth += 2;
                else if (text(i).equals("<") && --depth == 0) { i--; break; }
            }
        }
        return i >= start && (name(i) || Set.of("init", "__init", "get", "set", "sample").contains(text(i))) ? i : -1;
    }

    private void variables(int start, int end, String owner, boolean member) {
        int part = start, angles = 0;
        boolean initializer = false;
        for (int i = start; i <= end; i++) {
            String t = text(i);
            if (i == end || (t.equals(",") && angles == 0)) {
                int stop = part;
                while (stop < i && !text(stop).equals("=") && !text(stop).equals(":")) {
                    if (text(stop).equals("[") && close[stop] >= stop) break;
                    stop++;
                }
                int at = lastName(part, stop);
                int typeStart = start;
                while (MODIFIERS.contains(text(typeStart))) typeStart++;
                if (at >= 0 && (part > start || at > typeStart))
                    add(at, text(at), owner, join(start, stop), member ? SlangSymbol.Kind.FIELD : SlangSymbol.Kind.VARIABLE);
                part = i + 1; initializer = false;
            } else if (t.equals("=")) initializer = true;
            else if (!initializer && t.equals("<")) angles++;
            else if (!initializer && t.equals(">")) angles = Math.max(0, angles - 1);
            else if (!initializer && t.equals(">>")) angles = Math.max(0, angles - 2);
            else if ((t.equals("(") || t.equals("[") || t.equals("{")) && close[i] >= i) i = close[i];
        }
    }

    private void enumMembers(int start, int end, String owner) {
        boolean expect = true;
        for (int i = start; i < end; i++) {
            if (expect && name(i)) {
                add(i, text(i), owner, text(i), SlangSymbol.Kind.ENUM_MEMBER); expect = false;
            }
            if (text(i).equals(",")) expect = true;
            else if ((text(i).equals("(") || text(i).equals("[")) && close[i] >= i) i = close[i];
        }
    }

    private int afterAngles(int start, int end) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            if (text(i).equals("<")) depth++;
            else if (text(i).equals(">")) depth--;
            else if (text(i).equals(">>")) depth -= 2;
            if (depth <= 0) return i + 1;
        }
        return end;
    }
    private boolean hasEquals(int start, int end) {
        for (int i = start; i < end; i++) if (text(i).equals("=")) return true;
        return false;
    }
    private int lastName(int start, int end) {
        for (int i = end - 1; i >= start; i--) if (name(i)) return i;
        return -1;
    }
    private boolean name(int i) {
        if (i < 0 || i >= tokens.size()) return false;
        var type = tokens.get(i).type;
        return type == SlangTokenTypes.IDENTIFIER || type == SlangTokenTypes.NAMESPACE_NAME
                || type == SlangTokenTypes.MODULE_NAME || type == SlangTokenTypes.SEMANTIC
                || type == SlangTokenTypes.ATTRIBUTE;
    }
    private String text(int i) { return i >= 0 && i < tokens.size() ? tokens.get(i).text : ""; }
    private String join(int start, int end) {
        var out = new StringBuilder();
        for (int i = start; i < end && out.length() < 1024; i++) {
            String t = text(i), prev = text(i - 1);
            if (i > start && !Set.of(")", "]", ",", ".", "::", ">", ">>", "(", "[", "<").contains(t)
                    && !Set.of("(", "[", "<", ".", "::").contains(prev)) out.append(' ');
            out.append(t);
        }
        return out.toString();
    }
    private void add(int at, String name, String owner, String signature, SlangSymbol.Kind kind) {
        symbols.add(new SlangSymbol(name, owner, signature, kind, tokens.get(at).start, List.of(), usingNamespaces));
    }
    private static boolean matches(String open, String end) {
        return (open.equals("(") && end.equals(")")) || (open.equals("[") && end.equals("]"))
                || (open.equals("{") && end.equals("}"));
    }
    private static String qualify(String owner, String name) { return owner.isEmpty() ? name : owner + "." + name; }
}
