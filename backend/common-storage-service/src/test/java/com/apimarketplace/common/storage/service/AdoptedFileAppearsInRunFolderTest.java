package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress.Level;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.VirtualFileFilter;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.VirtualGroup;
import com.apimarketplace.common.storage.repository.StorageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof, against a real database, that adoption fixes the reported symptom.
 *
 * <p>The bug was never "a column is null" - it was "the mp3 my workflow generated shows at the ROOT
 * of Files instead of inside xAI Video Sequence / Run 12". Every other test on this feature is a
 * unit test and would stay green if the run coordinates were stamped but the virtual grouping still
 * failed to pick the row up. This one persists a row exactly as a generic catalog-binary upload
 * leaves it, asserts it is INVISIBLE in the run, adopts it, and asserts it is now listed there.</p>
 *
 * <p>H2 in PostgreSQL mode, same harness as {@code StorageExplorerRepositoryVirtualPersistenceTest}.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Only the hand-written repository needs importing; StorageRepository is a Spring Data
// interface that @DataJpaTest builds for us.
@Import(StorageExplorerRepository.class)
@DisplayName("A catalog-produced file becomes visible in its run once adopted")
class AdoptedFileAppearsInRunFolderTest {

    private static final String TENANT = "1";
    private static final String WORKFLOW = "7a1c2e3d-4b5f-4a6e-8d9c-0b1a2c3d4e5f";
    private static final String RUN = "run-12";
    private static final String STEP = "mcp:elevenlabs_tts";

    /** What the Files browser sends: real object-storage files only. */
    private static final VirtualFileFilter FILES_ONLY =
            new VirtualFileFilter(true, true, null, null, null, null, null, null);

    @Autowired private TestEntityManager entityManager;
    @Autowired private StorageExplorerRepository explorerRepository;
    @Autowired private StorageRepository storageRepository;

    private UUID looseFileId;

    @BeforeEach
    void seed() {
        // A row exactly as catalog-service's generic upload leaves it: stored and indexed, but with
        // no workflow, no run, no step - because catalog-service only ever knew the tenant.
        StorageEntity mp3 = new StorageEntity();
        mp3.setTenantId(TENANT);
        mp3.setOrganizationId(TENANT);
        mp3.setContentType("audio/mpeg");
        mp3.setData("{}");
        mp3.setStorageType("S3_FILE");
        mp3.setSourceType(StorageSourceTypes.S3_FILE);
        mp3.setS3Key("1/general/catalog-binary/c31d2812_elevenlabs-text-to-speech_2ba53530.mp3");
        mp3.setFileName("elevenlabs-text-to-speech.mp3");
        mp3.setMimeType("audio/mpeg");
        mp3.setSizeBytes(218800);
        mp3.setStatus(StorageStatus.ACTIVE);
        mp3.setCreatedAt(Instant.now());
        mp3.setAccessedAt(Instant.now());
        mp3.setEpoch(0);
        mp3.setSpawn(0);
        mp3.setIsFolder(false);
        entityManager.persist(mp3);
        entityManager.flush();
        looseFileId = mp3.getId();
        entityManager.clear();
    }

    // The folder TREE is what this test is about, so it asserts on the grouping queries that
    // compute it. (The leaf-file listing is deliberately not used: it goes through mapRows, which
    // casts the UUID column, and H2 hands UUIDs back as byte[] - the same reason the sibling
    // virtual persistence test sticks to the grouping queries.)

    private List<VirtualGroup> workflowFolders() {
        return explorerRepository.listVirtualGroups(TENANT, Level.WORKFLOW, null, null, null, null,
                List.of(), FILES_ONLY);
    }

    private List<VirtualGroup> runFoldersOfTheWorkflow() {
        return explorerRepository.listVirtualGroups(TENANT, Level.RUN, WORKFLOW, null, null, null,
                List.of(), FILES_ONLY);
    }

    private List<VirtualGroup> epochFoldersOfTheRun() {
        return explorerRepository.listVirtualGroups(TENANT, Level.EPOCH, WORKFLOW, RUN, null, null,
                List.of(), FILES_ONLY);
    }

    /** True while the row is in the root's "loose files" bucket ({@code workflow_id IS NULL}). */
    private boolean isAtTheRoot() {
        return entityManager.find(StorageEntity.class, looseFileId).getWorkflowId() == null;
    }

    private StorageService service() {
        return new StorageService(storageRepository, null, null,
                new com.apimarketplace.common.storage.util.StorageUtils(),
                null, new com.fasterxml.jackson.databind.ObjectMapper(), null);
    }

    @Test
    @DisplayName("THE BUG: before adoption the file is a loose root file and the run folder does not exist")
    void beforeAdoptionTheFileIsAtTheRoot() {
        assertThat(isAtTheRoot()).isTrue();
        // No workflow owns it, so there is no "xAI Video Sequence" folder at all - which is exactly
        // what the user saw: the mp3 at the root of Files, and no run folder holding it.
        assertThat(workflowFolders()).isEmpty();
        assertThat(runFoldersOfTheWorkflow()).isEmpty();
        assertThat(epochFoldersOfTheRun()).isEmpty();
    }

    @Test
    @DisplayName("THE FIX: after adoption the workflow, run and epoch folders all hold the file")
    void afterAdoptionTheFileIsInTheRun() {
        int adopted = service().adoptRunContext(TENANT, List.of(looseFileId), WORKFLOW, RUN, STEP, 4, 0, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(adopted).isEqualTo(1);
        // The folders are COMPUTED from the stamped columns, so this is the real proof that the
        // columns adoption writes are the ones the tree groups on - the whole point of the fix.
        assertThat(workflowFolders()).extracting(VirtualGroup::key).containsExactly(WORKFLOW);
        assertThat(runFoldersOfTheWorkflow()).extracting(VirtualGroup::key).containsExactly(RUN);
        assertThat(epochFoldersOfTheRun()).extracting(VirtualGroup::key).containsExactly("4");
        // Each level counts the one file.
        assertThat(workflowFolders()).singleElement().extracting(VirtualGroup::count).isEqualTo(1L);
        // And it has left the root: a file is in exactly one place.
        assertThat(isAtTheRoot()).isFalse();
    }

    @Test
    @DisplayName("the adopted row keeps its bytes, its name and its source type")
    void adoptionOnlyChangesTheFiling() {
        service().adoptRunContext(TENANT, List.of(looseFileId), WORKFLOW, RUN, STEP, 4, 0, null);
        entityManager.flush();
        entityManager.clear();

        StorageEntity row = entityManager.find(StorageEntity.class, looseFileId);
        assertThat(row.getS3Key()).isEqualTo("1/general/catalog-binary/c31d2812_elevenlabs-text-to-speech_2ba53530.mp3");
        assertThat(row.getFileName()).isEqualTo("elevenlabs-text-to-speech.mp3");
        assertThat(row.getSizeBytes()).isEqualTo(218800);
        // Left as-is on purpose: re-typing would desync the usage ledger (booked FILES, deleted
        // as STEP_OUTPUTS) and buys nothing, since the folder tree groups on the run columns.
        assertThat(row.getSourceType()).isEqualTo(StorageSourceTypes.S3_FILE);
        assertThat(row.getStepKey()).isEqualTo(STEP);
    }
}
