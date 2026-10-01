package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorageService#adoptRunContext}.
 *
 * <p>This is how a file produced by a catalog API (uploaded from catalog-service, which knows only
 * the tenant) is filed under the run that produced it. The behaviour that matters is what it
 * REFUSES to do: it fills a gap, so it must never move a file that already belongs to a run, never
 * touch another tenant's row, and never throw at a step that has already succeeded.</p>
 */
@DisplayName("StorageService.adoptRunContext")
@ExtendWith(MockitoExtension.class)
class StorageServiceAdoptRunContextTest {

    private static final String TENANT = "tenant-1";
    private static final String WORKFLOW = "3f2b8c1e-5d4a-4e7b-9c6d-1a2b3c4d5e6f";
    private static final String RUN = "run-7";
    private static final String STEP = "mcp:elevenlabs_tts";

    @Mock private StorageRepository storageRepository;
    @Mock private QuotaOperations quotaService;
    @Mock private MappingOperations mappingService;
    @Mock private StorageUtils storageUtils;
    @Mock private JsonSkeletonGenerator skeletonGenerator;
    @Mock private StorageBreakdownService breakdownService;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(
                storageRepository, quotaService, mappingService,
                storageUtils, skeletonGenerator, new ObjectMapper(), breakdownService);
    }

    /** A row as a generic catalog-binary upload leaves it: stored, but with no run context. */
    private static StorageEntity looseFile(UUID id) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setStatus(StorageStatus.ACTIVE);
        e.setStorageType("S3_FILE");
        e.setSourceType(StorageSourceTypes.S3_FILE);
        e.setS3Key("1/general/catalog-binary/c31d2812_elevenlabs-text-to-speech.mp3");
        e.setFileName("elevenlabs-text-to-speech.mp3");
        e.setMimeType("audio/mpeg");
        return e;
    }

    @Nested
    @DisplayName("adopts a file that has no run context")
    class Adopts {

        @Test
        @DisplayName("stamps every run coordinate the Files browser groups on")
        void stampsRunCoordinates() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(looseFile(id)));

            int adopted = storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 4, 1, 2);

            assertThat(adopted).isEqualTo(1);
            ArgumentCaptor<StorageEntity> saved = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(saved.capture());
            StorageEntity row = saved.getValue();
            // These five columns ARE the virtual workflow folder tree.
            assertThat(row.getWorkflowId()).isEqualTo(WORKFLOW);
            assertThat(row.getRunId()).isEqualTo(RUN);
            assertThat(row.getStepKey()).isEqualTo(STEP);
            assertThat(row.getEpoch()).isEqualTo(4);
            assertThat(row.getSpawn()).isEqualTo(1);
            assertThat(row.getItemIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("leaves sourceType alone - re-typing would desync the usage ledger")
        void doesNotRetypeTheRow() {
            // Re-typing to STEP_OUTPUT looks harmless (the folder tree groups on the run
            // coordinates, so only a badge would move) but the usage ledger books bytes under a
            // hard-coded FILES bucket at insert and derives the bucket from the CURRENT sourceType
            // on delete: the row would be saved as FILES and deleted as STEP_OUTPUTS, skewing the
            // tenant's breakdown for good.
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(looseFile(id)));

            storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null);

            ArgumentCaptor<StorageEntity> saved = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(saved.capture());
            assertThat(saved.getValue().getSourceType()).isEqualTo(StorageSourceTypes.S3_FILE);
        }

        @Test
        @DisplayName("a legacy row with no sourceType is still adoptable")
        void adoptsRowWithNoSourceType() {
            UUID id = UUID.randomUUID();
            StorageEntity legacy = looseFile(id);
            legacy.setSourceType(null);
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(legacy));

            assertThat(storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("adopts several files in one call and counts them")
        void adoptsSeveral() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(a, TENANT)).thenReturn(Optional.of(looseFile(a)));
            when(storageRepository.findByIdAndTenantId(b, TENANT)).thenReturn(Optional.of(looseFile(b)));

            assertThat(storageService.adoptRunContext(TENANT, List.of(a, b), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a null itemIndex leaves the column null - the file is not inside a split")
        void nullItemIndexStaysNull() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(looseFile(id)));

            storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null);

            ArgumentCaptor<StorageEntity> saved = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(saved.capture());
            assertThat(saved.getValue().getItemIndex()).isNull();
        }
    }

    @Nested
    @DisplayName("refuses to re-home a file")
    class Refuses {

        @Test
        @DisplayName("a file already filed under a run is left untouched")
        void neverOverwritesAnExistingRun() {
            // Replaying a step, or a step handing back a file another run produced, must not
            // move that file out of its own run folder.
            UUID id = UUID.randomUUID();
            StorageEntity owned = looseFile(id);
            owned.setWorkflowId("wf-other");
            owned.setRunId("run-other");
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(owned));

            int adopted = storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null);

            assertThat(adopted).isZero();
            verify(storageRepository, never()).save(any());
            assertThat(owned.getWorkflowId()).isEqualTo("wf-other");
        }

        @Test
        @DisplayName("adopting twice is a no-op the second time")
        void isIdempotent() {
            UUID id = UUID.randomUUID();
            StorageEntity row = looseFile(id);
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(row));

            assertThat(storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null)).isEqualTo(1);
            // The row now carries a workflow, so the second pass declines it.
            assertThat(storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null)).isZero();
        }

        @Test
        @DisplayName("a chat attachment is refused - adopting one would hide it from the browser")
        void refusesNonUploadSourceTypes() {
            // A CHAT_ATTACHMENT is DB-resident with NO s3_key, and the Files browser only shows it
            // through the source-type disjunct. Only a plain object-storage upload is adoptable.
            UUID id = UUID.randomUUID();
            StorageEntity attachment = looseFile(id);
            attachment.setSourceType(StorageSourceTypes.CHAT_ATTACHMENT);
            attachment.setS3Key(null);
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(attachment));

            assertThat(storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isZero();
            verify(storageRepository, never()).save(any());
        }

        @ParameterizedTest(name = "{0} is not adoptable")
        @ValueSource(strings = {"CHAT_ATTACHMENT", "STEP_OUTPUT", "INTERFACE_ACTION", "DECISION",
                "SIGNAL", "SKIPPED_NODE", "FOLDER", "SOMETHING_ADDED_LATER"})
        @DisplayName("the guard is an ALLOW-list: only a plain upload is adoptable, everything else is refused")
        void refusesEverySourceTypeButPlainUploads(String sourceType) {
            // Pins the SHAPE, not one instance. An allow-list also refuses by default any type added
            // after this was written - which is why the made-up value below belongs in the list.
            UUID id = UUID.randomUUID();
            StorageEntity row = looseFile(id);
            row.setSourceType(sourceType);
            when(storageRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(row));

            assertThat(storageService.adoptRunContext(TENANT, List.of(id), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isZero();
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("another tenant's id is skipped - the lookup is tenant-scoped")
        void skipsForeignFile() {
            // Ids are opaque but guessable-by-leak; the scope check is what stops one tenant's
            // step from claiming another tenant's file.
            UUID foreign = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(foreign, TENANT)).thenReturn(Optional.empty());

            assertThat(storageService.adoptRunContext(TENANT, List.of(foreign), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isZero();
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown id among good ones does not stop the others")
        void skipsUnknownButKeepsGoing() {
            UUID gone = UUID.randomUUID();
            UUID ok = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(gone, TENANT)).thenReturn(Optional.empty());
            when(storageRepository.findByIdAndTenantId(ok, TENANT)).thenReturn(Optional.of(looseFile(ok)));

            assertThat(storageService.adoptRunContext(TENANT, List.of(gone, ok), WORKFLOW, RUN, STEP, 1, 0, null))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("degenerate input costs nothing")
    class Degenerate {

        @Test
        @DisplayName("no ids, null ids, or no workflow: no query at all")
        void noWorkAtAll() {
            assertThat(storageService.adoptRunContext(TENANT, List.of(), WORKFLOW, RUN, STEP, 1, 0, null)).isZero();
            assertThat(storageService.adoptRunContext(TENANT, null, WORKFLOW, RUN, STEP, 1, 0, null)).isZero();
            // Without a workflow id there is no folder to adopt into - the point of the call.
            assertThat(storageService.adoptRunContext(TENANT, List.of(UUID.randomUUID()), null, RUN, STEP, 1, 0, null)).isZero();
            assertThat(storageService.adoptRunContext(TENANT, List.of(UUID.randomUUID()), "  ", RUN, STEP, 1, 0, null)).isZero();
            verifyNoInteractions(storageRepository);
        }

        @Test
        @DisplayName("an ad-hoc run adopts nothing (the file would move into a folder the Files browser cannot show)")
        void adHocRunAdoptsNothing() {
            assertThat(storageService.adoptRunContext(TENANT, List.of(UUID.randomUUID()), WORKFLOW,
                    WorkflowFileScope.AD_HOC_RUN_ID_PREFIX + WORKFLOW, STEP, 1, 0, null)).isZero();
            verifyNoInteractions(storageRepository);
        }

        @Test
        @DisplayName("a non-UUID workflow id ('unknown') adopts nothing")
        void unknownWorkflowAdoptsNothing() {
            assertThat(storageService.adoptRunContext(TENANT, List.of(UUID.randomUUID()), "unknown",
                    RUN, STEP, 1, 0, null)).isZero();
            verifyNoInteractions(storageRepository);
        }

        @Test
        @DisplayName("a null id inside the list is stepped over")
        void nullIdInList() {
            UUID ok = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(ok, TENANT)).thenReturn(Optional.of(looseFile(ok)));

            List<UUID> ids = new java.util.ArrayList<>();
            ids.add(null);
            ids.add(ok);

            assertThat(storageService.adoptRunContext(TENANT, ids, WORKFLOW, RUN, STEP, 1, 0, null)).isEqualTo(1);
        }
    }
}
