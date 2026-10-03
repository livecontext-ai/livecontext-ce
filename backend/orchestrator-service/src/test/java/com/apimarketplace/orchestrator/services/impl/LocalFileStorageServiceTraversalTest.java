package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for LC-062: arbitrary file write in {@code storage.type=local}.
 *
 * <p>{@code buildKey} interpolated the user-supplied file name into the storage key with no
 * sanitization (the S3 sibling already sanitized it), and {@code basePath.resolve(key)} collapses
 * a parent reference, so a file named with enough {@code ../} segments was written anywhere the
 * service account could write. The mode is config-gated and CE ships S3, but
 * {@code STORAGE_TYPE=local} is an operator-facing switch, so the primitive is reachable by
 * configuration alone.
 */
@DisplayName("LocalFileStorageService - path traversal containment (LC-062)")
class LocalFileStorageServiceTraversalTest {

    /** The whole point: nothing may be written outside this directory. */
    @TempDir
    Path root;

    private LocalFileStorageService service(Path base) {
        return new LocalFileStorageService(base.toString());
    }

    @Test
    @DisplayName("a traversing file name is written INSIDE the storage root, not above it")
    void traversingFileNameStaysInsideRoot() throws Exception {
        Path base = root.resolve("files");
        Path outside = root.resolve("pwned.txt");
        LocalFileStorageService service = service(base);

        FileRef ref = service.upload("58", "wf-1", "run-1", "upload",
                "../../../../pwned.txt", "text/plain",
                new ByteArrayInputStream("owned".getBytes(StandardCharsets.UTF_8)), 5);

        assertThat(Files.exists(outside))
                .as("a file written above the storage root is the vulnerability itself")
                .isFalse();
        assertThat(ref.path()).doesNotContain("..");
        // And the upload still succeeded: the fix sanitizes, it does not refuse ordinary uploads
        // that merely have an awkward name.
        Path written = base.resolve(ref.path()).normalize();
        assertThat(written.startsWith(base.normalize())).isTrue();
        assertThat(Files.exists(written)).isTrue();
    }

    @Test
    @DisplayName("a traversing stepAlias cannot escape either - every segment is user-influenced")
    void traversingStepAliasStaysInsideRoot() throws Exception {
        Path base = root.resolve("files");
        LocalFileStorageService service = service(base);

        FileRef ref = service.upload("58", "wf-1", "run-1", "../../..",
                "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        Path written = base.resolve(ref.path()).normalize();
        assertThat(written.startsWith(base.normalize()))
                .as("stepAlias is an author-chosen label, so it is attacker-influenced too")
                .isTrue();
        assertThat(Files.exists(written)).isTrue();
    }

    @Test
    @DisplayName("a traversing key handed to a READ path is refused rather than followed")
    void traversingReadKeyIsRefused() throws Exception {
        Path base = root.resolve("files");
        Files.createDirectories(base);
        Files.writeString(root.resolve("secret.txt"), "top secret");
        LocalFileStorageService service = service(base);

        assertThatThrownBy(() -> service.download("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the storage root");
    }

    @Test
    @DisplayName("a traversing key handed to delete/exists is refused too - same primitive, other verbs")
    void traversingDeleteAndExistsAreRefused() {
        Path base = root.resolve("files");
        LocalFileStorageService service = service(base);

        assertThatThrownBy(() -> service.delete("../../anything.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.exists("../../anything.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an absolute key, or one that resolves to the root itself, is refused")
    void absoluteAndRootKeysAreRefused() throws Exception {
        Path base = root.resolve("files");
        Path secret = Files.writeString(root.resolve("abs-secret.txt"), "top secret");
        LocalFileStorageService service = service(base);

        assertThatThrownBy(() -> service.download(secret.toAbsolutePath().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete("sub/.."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(secret)).isTrue();
    }

    @Test
    @DisplayName("containment holds when the configured base path is not normalized")
    void unnormalizedBasePathStillContains() {
        LocalFileStorageService service = new LocalFileStorageService(
                root.resolve("rel").toAbsolutePath().toString() + "/./x/../files");

        assertThatThrownBy(() -> service.download("../../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a symbolic link inside the root that points outside is refused on read, write and delete")
    void symlinkEscapeIsRefused() throws Exception {
        Path base = root.resolve("files");
        Path outsideDir = Files.createDirectories(root.resolve("outside"));
        Path victim = Files.writeString(outsideDir.resolve("victim.txt"), "keep me");
        LocalFileStorageService service = service(base);
        Path link = base.resolve("58").resolve("escape");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Windows without the symlink privilege: the scenario cannot be staged here.
            org.junit.jupiter.api.Assumptions.abort("cannot create symlinks on this host: " + e);
        }

        assertThatThrownBy(() -> service.download("58/escape/victim.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link");
        assertThatThrownBy(() -> service.delete("58/escape/victim.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(victim)).isEqualTo("keep me");
    }

    @Test
    @DisplayName("a key that is not a valid path (NUL byte) is refused cleanly, not with InvalidPathException")
    void invalidPathIsRefusedCleanly() {
        LocalFileStorageService service = service(root.resolve("files"));

        assertThatThrownBy(() -> service.download("58/bad\u0000name.txt"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid path");
        assertThatThrownBy(() -> service.exists("58/bad\u0000name.txt"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deleteRunFiles with traversing ids stays inside the root and deletes nothing outside")
    void deleteRunFilesCannotEscape() throws Exception {
        Path base = root.resolve("files");
        LocalFileStorageService service = service(base);
        Path outside = Files.writeString(root.resolve("run-outside.txt"), "keep me");
        Path runDir = Files.createDirectories(root.resolve("t").resolve("w").resolve("r"));
        Files.writeString(runDir.resolve("f.txt"), "keep me too");

        int deleted = service.deleteRunFiles("..", "../t/w", "..");

        assertThat(deleted).isZero();
        assertThat(Files.exists(outside)).isTrue();
        assertThat(Files.exists(runDir.resolve("f.txt"))).isTrue();
    }

    @Test
    @DisplayName("deleteRunFiles still deletes an ordinary run's files")
    void deleteRunFilesOrdinaryRun() throws Exception {
        LocalFileStorageService service = service(root.resolve("files"));
        service.upload("58", "wf-1", "run-1", "upload", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(service.deleteRunFiles("58", "wf-1", "run-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("embedded double dots and trailing dots are neutralised, ordinary dots are kept")
    void embeddedAndTrailingDots() {
        assertThat(LocalFileStorageService.sanitizeSegment("a..b")).isEqualTo("a_b");
        assertThat(LocalFileStorageService.sanitizeSegment("report.")).isEqualTo("report");
        assertThat(LocalFileStorageService.sanitizeSegment("name. ")).isEqualTo("name");
        assertThat(LocalFileStorageService.sanitizeSegment(".hidden")).isEqualTo("hidden");
        assertThat(LocalFileStorageService.sanitizeSegment("report.v1.pdf")).isEqualTo("report.v1.pdf");
        assertThat(LocalFileStorageService.sanitizeSegment("...")).isEqualTo("unnamed");
    }

    @Test
    @DisplayName("an ordinary round trip is unaffected - the fix must be a no-op for normal names")
    void ordinaryUploadRoundTripsUnchanged() throws Exception {
        Path base = root.resolve("files");
        LocalFileStorageService service = service(base);

        FileRef ref = service.upload("58", "wf-1", "run-1", "upload",
                "quarterly report.pdf", "application/pdf",
                "bytes".getBytes(StandardCharsets.UTF_8));

        assertThat(ref.path()).contains("58/wf-1/run-1/upload/");
        assertThat(ref.path()).endsWith("quarterly report.pdf");
        assertThat(service.exists(ref.path())).isTrue();
        assertThat(service.download(ref.path())).isPresent();
        assertThat(service.delete(ref.path())).isTrue();
    }

    @Test
    @DisplayName("a dots-only segment degrades to a name instead of a directory hop")
    void dotsOnlySegmentIsReplaced() {
        assertThat(LocalFileStorageService.sanitizeSegment("..")).isEqualTo("unnamed");
        assertThat(LocalFileStorageService.sanitizeSegment(".")).isEqualTo("unnamed");
        assertThat(LocalFileStorageService.sanitizeSegment("a/b")).isEqualTo("a_b");
        assertThat(LocalFileStorageService.sanitizeSegment("a\\b")).isEqualTo("a_b");
        assertThat(LocalFileStorageService.sanitizeSegment(null)).isEqualTo("unnamed");
    }
}
