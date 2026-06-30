package com.apimarketplace.common.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StorageExplorerDto#withCreatedAt(Instant)} - the helper that stamps a MANUAL
 * folder's last-activity date into the same {@code createdAt} field a VIRTUAL workflow folder already
 * carries, so every folder exposes one uniform "last modified" instant the frontend sorts/displays by.
 */
@DisplayName("StorageExplorerDto.withCreatedAt")
class StorageExplorerDtoWithCreatedAtTest {

    private static StorageExplorerDto folder(Instant ownCreatedAt) {
        return StorageExplorerDto.from(
                new StorageExplorerProjection(
                        java.util.UUID.randomUUID(), "FOLDER", "FOLDER", "MyFolder", null, 0,
                        ownCreatedAt, null, null, null, null, 0, null, "application/x-directory",
                        /* isFolder */ true, /* parentFolderId */ null),
                null, /* childCount */ 4,
                List.of(new StoragePreviewFile("p1", "image/png", "p1.png"),
                        new StoragePreviewFile("p2", "image/png", "p2.png")));
    }

    @Test
    @DisplayName("replaces createdAt with the supplied last-activity instant, preserving every other field")
    void replacesCreatedAt() {
        Instant own = Instant.parse("2026-01-01T00:00:00Z");
        Instant activity = Instant.parse("2026-06-17T10:30:00Z");
        StorageExplorerDto stamped = folder(own).withCreatedAt(activity);

        assertThat(stamped.createdAt()).isEqualTo(activity);
        // Every other folder field is preserved (not rebuilt with defaults).
        assertThat(stamped.isFolder()).isTrue();
        assertThat(stamped.fileName()).isEqualTo("MyFolder");
        assertThat(stamped.childCount()).isEqualTo(4);
        assertThat(stamped.previewFiles()).extracting(StoragePreviewFile::id).containsExactly("p1", "p2");
    }

    @Test
    @DisplayName("a null replacement is a no-op - the folder keeps its own createdAt (childless-folder fallback)")
    void nullReplacementIsNoOp() {
        Instant own = Instant.parse("2026-03-03T09:00:00Z");
        StorageExplorerDto original = folder(own);
        StorageExplorerDto result = original.withCreatedAt(null);

        assertThat(result.createdAt()).isEqualTo(own);
        assertThat(result).isSameAs(original); // returns this - no needless copy
    }
}
