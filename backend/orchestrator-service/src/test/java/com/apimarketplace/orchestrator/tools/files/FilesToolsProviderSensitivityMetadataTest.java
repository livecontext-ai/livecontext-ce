package com.apimarketplace.orchestrator.tools.files;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.storage.domain.StorageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-004: a file's tool-result metadata carries the restricted tag when its backing storage row
 * is RESTRICTED (a Gmail attachment, a Drive download, or a step output of a run that read them),
 * so the agent loop and the CLI bridge withhold it from a provider outside the allow-list - see
 * {@link FilesToolsProvider#sensitivityMetadata}, used by both {@code get} and {@code view}.
 */
@DisplayName("FilesToolsProvider.sensitivityMetadata")
class FilesToolsProviderSensitivityMetadataTest {

    @Test
    @DisplayName("RESTRICTED storage row: metadata carries the __dataSensitivity__ tag")
    void restrictedRowIsTagged() {
        StorageEntity entity = new StorageEntity();
        entity.setDataSensitivity(DataSensitivity.RESTRICTED.name());

        Map<String, Object> metadata = FilesToolsProvider.sensitivityMetadata(entity);

        assertThat(metadata).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");
    }

    @Test
    @DisplayName("NORMAL storage row: no tag, so ordinary files stay untouched")
    void normalRowIsNotTagged() {
        StorageEntity entity = new StorageEntity();
        entity.setDataSensitivity("NORMAL");

        Map<String, Object> metadata = FilesToolsProvider.sensitivityMetadata(entity);

        assertThat(metadata).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("null entity or null/blank sensitivity: never throws, never tags")
    void handlesAbsentSensitivityGracefully() {
        assertThat(FilesToolsProvider.sensitivityMetadata(null)).isEmpty();

        StorageEntity noTag = new StorageEntity();
        assertThat(FilesToolsProvider.sensitivityMetadata(noTag)).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }
}
