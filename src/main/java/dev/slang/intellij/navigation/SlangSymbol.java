package dev.slang.intellij.navigation;

/** A source declaration. Offsets use IntelliJ's UTF-16 document coordinates. */
public record SlangSymbol(String name, String container, String signature, Kind kind, int offset) {
    public enum Kind { FUNCTION, TYPE, NAMESPACE, FIELD, VARIABLE, ENUM_MEMBER, ALIAS }
    public String qualifiedName() { return container.isEmpty() ? name : container + "." + name; }
}
