package com.apimarketplace.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Source discovery for the tests that read OTHER modules' code: drift guards and invariant
 * scans that grep every service for a pattern.
 *
 * <p><b>Why this exists.</b> Such tests used to {@code Files.walk} the whole {@code backend/}
 * directory and keep only the paths containing {@code src/main} afterwards. The walk itself
 * still entered every module's {@code target/}, and in CI a backend shard runs a second Maven
 * process at the same time as the first (the {@code alongside} lane of
 * {@code scripts/ci/backend-shards.json}). That process's surefire creates and deletes
 * temporary files under its own {@code target/} while the scan walks there, and
 * {@code Files.walk} throws {@code NoSuchFileException} on an entry that disappears between
 * being listed and being read. The guard then failed on a file it never meant to read (CI run
 * 36747538564: {@code BillingContextHeadersTest} in the gateway, while orchestrator-service
 * cleaned up its {@code target/surefire-*} files).
 *
 * <p><b>What it does instead.</b> It lists the reactor's direct children by NAME only and walks
 * nothing but each module's {@code src/main/java}. Build output ({@code target/}), dependency
 * trees ({@code node_modules/}), Gradle-style {@code build/} and VCS data ({@code .git/}) are
 * never opened, so a concurrent build cannot change, or break, what is read. Every backend
 * module sits directly under the reactor root, which is the layout this relies on; a caller
 * must still assert that it found a plausible number of files, so a layout change fails
 * loudly instead of turning the scan vacuous.
 */
public final class SourceTrees {

    /** A module's main Java sources, relative to the module directory. */
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /**
     * Direct children of the reactor root that are never modules, skipped by name before any
     * of their contents is touched.
     */
    static final Set<String> NON_MODULE_DIRS = Set.of("target", "build", "node_modules", ".git");

    private SourceTrees() {
    }

    /**
     * Every {@code .java} file under {@code <module>/src/main/java}, for each module directly
     * under {@code reactorRoot}, sorted.
     *
     * @param reactorRoot the Maven reactor root (the {@code backend/} directory)
     * @return the main Java sources of every module, never including build output
     * @throws NoSuchFileException   if {@code reactorRoot} does not exist: a scan rooted at the
     *                               wrong place must fail, not return nothing
     * @throws NotDirectoryException if {@code reactorRoot} is a file, for the same reason
     * @throws IOException           if a source directory cannot be read
     */
    public static List<Path> mainJavaSources(Path reactorRoot) throws IOException {
        if (!Files.exists(reactorRoot)) {
            throw new NoSuchFileException(reactorRoot.toAbsolutePath().toString(), null,
                    "no reactor root here, so no module sources can be read from it");
        }
        if (!Files.isDirectory(reactorRoot)) {
            throw new NotDirectoryException(reactorRoot.toAbsolutePath().toString());
        }
        List<Path> sourceRoots;
        try (Stream<Path> children = Files.list(reactorRoot)) {
            sourceRoots = children
                    .filter(child -> !NON_MODULE_DIRS.contains(child.getFileName().toString()))
                    .map(module -> module.resolve(MAIN_JAVA))
                    .filter(Files::isDirectory)
                    .toList();
        }
        List<Path> sources = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(file -> file.getFileName().toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(sources::add);
            }
        }
        sources.sort(null);
        return List.copyOf(sources);
    }
}
