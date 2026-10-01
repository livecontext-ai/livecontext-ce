package com.apimarketplace.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SourceTrees} is what the repo-scanning guards (BillingContextHeadersTest,
 * NotificationCategoryCoverageTest, TextBlockSqlConcatGuardTest) read other modules through. Two
 * ways it can break them, both pinned here: entering a build directory that a parallel Maven
 * process is rewriting (the CI failure of run_<id>), and quietly returning less than the
 * sources, which would turn every guard built on it into a green no-op.
 */
@DisplayName("SourceTrees - main-source discovery that never reads build output")
class SourceTreesTest {

    @Test
    @DisplayName("returns exactly the main Java sources of every module, nothing from target, build, node_modules, .git or test trees")
    void readsOnlyMainJavaSources(@TempDir Path reactor) throws IOException {
        Path a = write(reactor, "mod-a/src/main/java/com/x/A.java");
        Path nested = write(reactor, "mod-a/src/main/java/com/x/deep/er/Nested.java");
        Path b = write(reactor, "mod-b/src/main/java/B.java");
        write(reactor, "mod-a/src/main/java/com/x/notes.txt");
        write(reactor, "mod-a/src/main/resources/Template.java");
        write(reactor, "mod-a/src/test/java/com/x/ATest.java");
        write(reactor, "mod-a/target/generated-sources/annotations/Generated.java");
        write(reactor, "mod-a/target/src/main/java/Copied.java");
        write(reactor, "mod-a/build/src/main/java/Gradle.java");
        write(reactor, "mod-a/node_modules/pkg/src/main/java/Npm.java");
        write(reactor, "target/src/main/java/ReactorOutput.java");
        write(reactor, "node_modules/src/main/java/Npm.java");
        write(reactor, "build/src/main/java/Gradle.java");
        write(reactor, ".git/src/main/java/Object.java");
        write(reactor, "mod-c/src/test/java/OnlyTests.java");
        write(reactor, "pom.xml");

        assertThat(SourceTrees.mainJavaSources(reactor)).containsExactly(a, nested, b);
    }

    @Test
    @DisplayName("the result is sorted by path, whatever order the file system lists modules in")
    void resultIsSorted(@TempDir Path reactor) throws IOException {
        Path z = write(reactor, "zeta/src/main/java/z.java");
        Path c = write(reactor, "alpha/src/main/java/c.java");
        Path b = write(reactor, "alpha/src/main/java/b/b.java");

        List<Path> sources = SourceTrees.mainJavaSources(reactor);

        assertThat(sources).containsExactly(b, c, z);
    }

    @Test
    @DisplayName("a reactor root that does not exist fails the scan instead of returning nothing")
    void missingReactorRootFails(@TempDir Path parent) {
        Path absent = parent.resolve("no-such-backend");

        assertThatThrownBy(() -> SourceTrees.mainJavaSources(absent))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("no-such-backend")
                .hasMessageContaining("no reactor root here");
    }

    @Test
    @DisplayName("a reactor root that is a file fails the scan instead of returning nothing")
    void fileAsReactorRootFails(@TempDir Path parent) throws IOException {
        Path pom = write(parent, "pom.xml");

        assertThatThrownBy(() -> SourceTrees.mainJavaSources(pom))
                .isInstanceOf(NotDirectoryException.class)
                .hasMessageContaining("pom.xml");
    }

    /**
     * The CI failure, reproduced: a second Maven lane's surefire creates and deletes files and
     * directories under its module's target/ while the scan runs. A walk that enters target/
     * throws NoSuchFileException on an entry that vanished after being listed (a file on Linux,
     * a directory on every OS); this one never lists target/, so it cannot.
     */
    @Test
    @DisplayName("files and directories appearing and vanishing under target/ during the scan, as a parallel surefire does, never break it")
    void concurrentChurnUnderTargetNeverBreaksTheScan(@TempDir Path reactor) throws Exception {
        Path a = write(reactor, "mod-a/src/main/java/com/x/A.java");
        Path b = write(reactor, "mod-b/src/main/java/com/y/B.java");
        Path surefire = Files.createDirectories(reactor.resolve("mod-a/target/surefire"));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger churnRounds = new AtomicInteger();
        AtomicReference<Throwable> churnFailure = new AtomicReference<>();
        Thread churn = new Thread(() -> {
            try {
                while (!stop.get()) {
                    churnOnce(surefire);
                    churnRounds.incrementAndGet();
                }
            } catch (Throwable t) {
                churnFailure.set(t);
            }
        }, "surefire-churn");
        churn.setDaemon(true);
        churn.start();

        int scans = 0;
        try {
            long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
            while (System.nanoTime() < deadline) {
                assertThat(SourceTrees.mainJavaSources(reactor)).containsExactly(a, b);
                scans++;
            }
        } finally {
            stop.set(true);
            churn.join(Duration.ofSeconds(10).toMillis());
        }

        assertThat(churnFailure.get()).as("the churn thread itself must not have died").isNull();
        assertThat(churnRounds.get()).as("target/ must actually have churned during the scans").isPositive();
        assertThat(scans).as("the scan must actually have run while target/ churned").isPositive();
    }

    /** One burst of surefire-like temp entries: files and directories created, then deleted. */
    private static void churnOnce(Path surefire) throws IOException {
        for (int i = 0; i < 20; i++) {
            Files.writeString(surefire.resolve("surefire-execution" + i + ".tmp"), "x");
            Path dir = Files.createDirectories(surefire.resolve("surefire-dir" + i));
            Files.writeString(dir.resolve("stream.bin"), "x");
        }
        for (int i = 0; i < 20; i++) {
            Path dir = surefire.resolve("surefire-dir" + i);
            deleteQuietly(surefire.resolve("surefire-execution" + i + ".tmp"));
            deleteQuietly(dir.resolve("stream.bin"));
            deleteQuietly(dir);
        }
    }

    /**
     * A delete the OS refuses for a moment (a Windows scanner holding the file) is not what this
     * test measures; the next burst retries it. Creation failures still stop the churn loudly.
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // retried on the next burst
        }
    }

    private static Path write(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "// " + relative);
        return file;
    }
}
