package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (real H2-in-PostgreSQL-mode DB) test for the EPOCH virtual-folder LABEL.
 *
 * <p>Unlike {@code StorageExplorerVirtualScopeTest} (which mocks the repository and so cannot catch a
 * mismatch between the real SQL grouping and the service's labeling), this wires the REAL
 * {@link StorageExplorerService} over the REAL {@link StorageExplorerRepository} against a real database.
 * It seeds {@code storage.storage} rows and drives {@link StorageExplorerService#searchVirtualScope} so the
 * full path is exercised: native grouping query (returns the RAW epoch as the group key) -> service labeling
 * -> {@link StorageExplorerDto}.</p>
 *
 * <p>Guards the reported bug: a file produced at a LATE epoch (e.g. epoch 4, while epochs 0-3 produced no
 * file) must be labeled with its REAL epoch ("Epoch 4"), matching the run-inspector / application EpochSlider
 * which reads the same raw {@code workflow_epochs.epoch} - NOT a positional "Epoch 1".</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StorageExplorerRepository.class, StorageExplorerService.class})
@DisplayName("StorageExplorerService - EPOCH folder label = real stored epoch (real DB)")
class StorageExplorerVirtualEpochLabelPersistenceTest {

    private static final String ORG = "org-epoch-label";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StorageExplorerService service;

    @Test
    @DisplayName("REGRESSION: a workflow whose ONLY file is at raw epoch 4 (epochs 0-3 produced none) shows "
            + "'Epoch 4', not the positional 'Epoch 1' - matches the EpochSlider")
    void loneLateEpochShowsItsRealValue() {
        // One run, one real file, at epoch 4. Nothing at epochs 0-3.
        String wf = "wf-lone-late";
        persist(realFile(wf, "run-X", 4, "late.pdf", "k/late.pdf"));
        entityManager.flush();
        entityManager.clear();

        Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                new VirtualFolderAddress(wf, null, null, null), null, null, null,
                /* filesOnly */ true, /* s3Only */ true, null, List.of(), PageRequest.of(0, 20));

        assertThat(p.getContent()).hasSize(1);
        StorageExplorerDto epochFolder = p.getContent().get(0);
        assertThat(epochFolder.virtualKind()).isEqualTo("EPOCH");
        // Single run collapses → no run segment; the raw epoch (4) is in BOTH the navigation token…
        assertThat(epochFolder.virtualId()).isEqualTo("wf:" + wf + "/e4");
        // …and the label coordinate (the frontend renders this as "Epoch 4"). Pre-fix this was 1.
        assertThat(epochFolder.epoch()).isEqualTo(4);
        assertThat(epochFolder.childCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two late epochs in ONE run (raw 2 and 4) show 'Epoch 2' and 'Epoch 4' oldest-first, "
            + "NOT renumbered 'Epoch 1'/'Epoch 2'")
    void multipleLateEpochsShowRealValuesOldestFirst() {
        // SAME run for both files so the workflow collapses to epoch folders (≥2 distinct runs would
        // instead render RUN folders). Real files only at epochs 2 and 4.
        String wf = "wf-two-late";
        persist(realFile(wf, "run-Y", 2, "e2.png", "k/e2.png"));
        persist(realFile(wf, "run-Y", 4, "e4.png", "k/e4.png"));
        entityManager.flush();
        entityManager.clear();

        Page<StorageExplorerDto> p = service.searchVirtualScope("1", ORG,
                new VirtualFolderAddress(wf, null, null, null), null, null, null,
                true, true, null, List.of(), PageRequest.of(0, 20));

        assertThat(p.getContent()).hasSize(2);
        assertThat(p.getContent()).allMatch(d -> "EPOCH".equals(d.virtualKind()));
        // Oldest-first display; the label coordinate is the REAL epoch (2 then 4), never positional (1,2).
        assertThat(p.getContent()).extracting(StorageExplorerDto::epoch).containsExactly(2, 4);
        assertThat(p.getContent()).extracting(StorageExplorerDto::virtualId)
                .containsExactly("wf:" + wf + "/e2", "wf:" + wf + "/e4");
    }

    // ---- helpers ----

    private void persist(StorageEntity e) {
        entityManager.persist(e);
    }

    /** A real workflow file row (file_name + s3_key present) pinned to wf/run/epoch. */
    private static StorageEntity realFile(String wf, String runId, int epoch, String fileName, String s3Key) {
        StorageEntity e = new StorageEntity();
        e.setTenantId(ORG);
        e.setOrganizationId(ORG);
        e.setFileName(fileName);
        e.setS3Key(s3Key);
        e.setMimeType("application/octet-stream");
        e.setContentType("application/octet-stream");
        e.setData("{}");
        e.setStorageType("S3_FILE");
        e.setSourceType("STEP_OUTPUT");
        e.setSizeBytes(10);
        e.setStatus(StorageStatus.ACTIVE);
        e.setCreatedAt(Instant.now());
        e.setAccessedAt(Instant.now());
        e.setWorkflowId(wf);
        e.setRunId(runId);
        e.setEpoch(epoch);
        e.setSpawn(0);
        e.setItemIndex(0);
        e.setIsFolder(false);
        // parent_folder_id left null → a virtual-root row (participates in the virtual tree)
        return e;
    }
}
