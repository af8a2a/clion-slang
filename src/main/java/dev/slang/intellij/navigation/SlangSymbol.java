package dev.slang.intellij.navigation;

/** A source declaration. Offsets use IntelliJ's UTF-16 document coordinates.
 * baseTypes contains explicit bases for types, or the transparent target for a type alias.
 * Generic arguments are erased for declaration navigation, not evaluated for conformance.
 */
public record SlangSymbol(String name, String container, String signature, Kind kind, int offset,
                          java.util.List<String> baseTypes, java.util.List<NamespaceImport> usingNamespaces) {
    public record NamespaceImport(String name, String owner) {}
    public SlangSymbol {
        baseTypes = java.util.List.copyOf(baseTypes);
        usingNamespaces = java.util.List.copyOf(usingNamespaces);
    }
    public SlangSymbol(String name, String container, String signature, Kind kind, int offset,
                       java.util.List<String> baseTypes) {
        this(name, container, signature, kind, offset, baseTypes, java.util.List.of());
    }
    public SlangSymbol(String name, String container, String signature, Kind kind, int offset) {
        this(name, container, signature, kind, offset, java.util.List.of());
    }
    public boolean isInterface() { return kind == Kind.TYPE && signature.startsWith("interface "); }
    public enum Kind { FUNCTION, TYPE, NAMESPACE, FIELD, VARIABLE, ENUM_MEMBER, ALIAS }
    public String qualifiedName() { return container.isEmpty() ? name : container + "." + name; }
}
