package dev.slang.intellij.preprocessor;

import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Potential textual includes, never macro evaluation. The compiler confirms each selected root. */
public final class SlangIncludeGraph {
    public record Include(String path, boolean quoted) {}
    public record Candidate(Path root, List<Path> chain, boolean shortened) {
        public Candidate(Path root, List<Path> chain) { this(root, chain, false); }
    }
    private final Map<Path, Set<Path>> parents = new HashMap<>();
    private final Set<Path> roots;

    public SlangIncludeGraph(Collection<Path> roots, Collection<Path> searchPaths,
                             Function<Path, String> loader, Runnable checkCancelled) {
        this.roots = new HashSet<>(roots);
        Set<Path> visited = new HashSet<>();
        Map<Path, String> loaded = new HashMap<>();
        ArrayDeque<Path> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            checkCancelled.run();
            Path file = queue.removeFirst();
            if (!visited.add(file)) continue;
            String source = read(file, loaded, loader);
            if (source == null) continue;
            for (Include include : includes(source)) {
                LinkedHashSet<Path> paths = new LinkedHashSet<>();
                try {
                    String name = include.path.replace('\\', '/');
                    if (include.quoted && file.getParent() != null) paths.add(file.getParent().resolve(name).normalize());
                    for (Path directory : searchPaths) paths.add(directory.resolve(name).normalize());
                } catch (InvalidPathException ignored) { continue; }
                // Preserve all existing alternatives: workspace search ordering may differ in slangd.
                for (Path path : paths) {
                    checkCancelled.run();
                    if (read(path, loaded, loader) == null) continue;
                    parents.computeIfAbsent(path, key -> new HashSet<>()).add(file);
                    queue.addLast(path);
                }
            }
        }
    }

    private static String read(Path path, Map<Path, String> loaded, Function<Path, String> loader) {
        if (!loaded.containsKey(path)) loaded.put(path, loader.apply(path));
        return loaded.get(path);
    }

    public List<Candidate> candidates(Path target) {
        Map<Path, Path> next = new HashMap<>();
        next.put(target, target);
        ArrayDeque<Path> queue = new ArrayDeque<>(List.of(target));
        while (!queue.isEmpty()) {
            Path child = queue.removeFirst();
            for (Path parent : parents.getOrDefault(child, Set.of()).stream().sorted().toList()) {
                if (next.containsKey(parent)) continue;
                next.put(parent, child);
                queue.addLast(parent);
            }
        }
        return next.keySet().stream().filter(root -> !root.equals(target) && roots.contains(root)).sorted().map(root -> {
            List<Path> chain = new ArrayList<>();
            Path cursor = root;
            while (!cursor.equals(target) && chain.size() < 64) {
                chain.add(cursor);
                cursor = next.get(cursor);
            }
            boolean shortened = !cursor.equals(target);
            if (!shortened) chain.add(target);
            return new Candidate(root, List.copyOf(chain), shortened);
        }).toList();
    }

    public static List<Include> includes(String source) {
        List<Include> result = new ArrayList<>();
        SlangLexer lexer = new SlangLexer();
        lexer.start(source);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == SlangTokenTypes.INCLUDE_PATH) {
                String token = source.substring(lexer.getTokenStart(), lexer.getTokenEnd());
                boolean quoted = token.charAt(0) == '"';
                if (token.length() > 2 && token.charAt(token.length() - 1) == (quoted ? '"' : '>'))
                    result.add(new Include(token.substring(1, token.length() - 1), quoted));
            }
            lexer.advance();
        }
        return List.copyOf(result);
    }
}
