package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for PR18 audit-1 finding M2.
 *
 * <p>{@code RunCloneService.cloneStorageEntries} was silently dropping
 * {@code organization_id} when copying a row, demoting org-workspace data to
 * personal scope after marketplace acquisition. The round-2 fix added
 * {@code copy.setOrganizationId(source.getOrganizationId())} at line 254.
 * This test pins the contract so a future refactor of the clone loop cannot
 * regress it - failure mode is silent-data-drift, exactly the class the
 * CLAUDE.md "MANDATORY: bug fix → regression test" rule guards against.</p>
 */
@DisplayName("RunCloneService.cloneStorageEntries org_id preservation (PR18 audit-1 M2)")
@ExtendWith(MockitoExtension.class)
class RunCloneServiceCloneStorageTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private StorageRepository storageRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private ObjectMapper objectMapper;
    @Mock private InterfaceClient interfaceClient;

    @Captor private ArgumentCaptor<StorageEntity> savedCaptor;

    private RunCloneService runCloneService;

    @BeforeEach
    void setUp() {
        runCloneService = new RunCloneService(
                workflowRunRepository, workflowStepDataRepository, storageRepository,
                fileStorageService, jdbcTemplate, breakdownService, objectMapper, interfaceClient);
    }

    @Test
    @DisplayName("clone preserves organization_id on org-scoped source row")
    void preservesOrgIdOnOrgScopedSource() {
        UUID sourceId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        StorageEntity source = new StorageEntity();
        source.setId(sourceId);
        source.setTenantId("tenant-alice");
        source.setOrganizationId("org-acme");
        source.setContentType("application/json");
        source.setStorageType("JSON");
        source.setData("{}");
        source.setSizeBytes(0);
        source.setEpoch(0);
        source.setSpawn(0);
        source.setStatus(StorageStatus.ACTIVE);
        source.setCreatedAt(Instant.now());
        source.setAccessedAt(Instant.now());

        when(storageRepository.findAllById(Set.of(sourceId))).thenReturn(List.of(source));
        when(storageRepository.save(org.mockito.ArgumentMatchers.any(StorageEntity.class)))
                .thenAnswer(inv -> {
                    StorageEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    return arg;
                });

        runCloneService.cloneStorageEntries(Set.of(sourceId), "new-run", "tenant-alice", publicationId);

        org.mockito.Mockito.verify(storageRepository).save(savedCaptor.capture());
        StorageEntity clone = savedCaptor.getValue();
        assertThat(clone.getOrganizationId())
                .as("Clone MUST carry the source's organization_id - without this, the row "
                  + "demotes to personal scope and becomes invisible in the org workspace")
                .isEqualTo("org-acme");
    }

    @Test
    @DisplayName("clone preserves null organization_id on personal-scoped source row (no false-positive tagging)")
    void preservesNullOrgIdOnPersonalSource() {
        UUID sourceId = UUID.randomUUID();
        StorageEntity source = new StorageEntity();
        source.setId(sourceId);
        source.setTenantId("tenant-bob");
        source.setOrganizationId(null);
        source.setContentType("application/json");
        source.setStorageType("JSON");
        source.setData("{}");
        source.setSizeBytes(0);
        source.setEpoch(0);
        source.setSpawn(0);
        source.setStatus(StorageStatus.ACTIVE);
        source.setCreatedAt(Instant.now());
        source.setAccessedAt(Instant.now());

        when(storageRepository.findAllById(Set.of(sourceId))).thenReturn(List.of(source));
        when(storageRepository.save(org.mockito.ArgumentMatchers.any(StorageEntity.class)))
                .thenAnswer(inv -> {
                    StorageEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    return arg;
                });

        runCloneService.cloneStorageEntries(Set.of(sourceId), "new-run", "tenant-bob", UUID.randomUUID());

        org.mockito.Mockito.verify(storageRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getOrganizationId())
                .as("Personal-scoped source stays personal - clone MUST NOT silently tag it with an org id")
                .isNull();
    }

    /**
     * Regression for the opaque-URL cutover: a cloned row's embedded FileRef carries an {@code id}
     * (the storage-row UUID the opaque file URL is built from). Without the second-pass rewrite the
     * clone would keep the SOURCE row's id, so the by-id URL would address the publisher's file
     * (or 404 cross-org). The fix rewrites every {@code "id":"<sourceId>"} → {@code "<cloneId>"}.
     */
    @Test
    @DisplayName("clone rewrites the embedded FileRef id from the source row id to the cloned row id")
    void rewritesFileRefIdFromSourceToClone() {
        UUID sourceId = UUID.randomUUID();
        UUID cloneId = UUID.randomUUID();
        StorageEntity source = new StorageEntity();
        source.setId(sourceId);
        source.setTenantId("tenant-alice");
        source.setOrganizationId("org-acme");
        source.setContentType("application/json");
        source.setStorageType("JSON");
        // Step output embeds a FileRef whose id references THIS row (the file the step produced).
        source.setData("{\"file\":{\"_type\":\"file\",\"path\":\"p\",\"name\":\"n\","
                + "\"mimeType\":\"image/png\",\"size\":1,\"id\":\"" + sourceId + "\"}}");
        source.setSizeBytes(0);
        source.setEpoch(0);
        source.setSpawn(0);
        source.setStatus(StorageStatus.ACTIVE);
        source.setCreatedAt(Instant.now());
        source.setAccessedAt(Instant.now());

        when(storageRepository.findAllById(Set.of(sourceId))).thenReturn(List.of(source));
        when(storageRepository.save(org.mockito.ArgumentMatchers.any(StorageEntity.class)))
                .thenAnswer(inv -> {
                    StorageEntity arg = inv.getArgument(0);
                    arg.setId(cloneId);
                    return arg;
                });
        when(storageRepository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        runCloneService.cloneStorageEntries(Set.of(sourceId), "new-run", "tenant-alice", UUID.randomUUID());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StorageEntity>> saveAllCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(storageRepository).saveAll(saveAllCaptor.capture());
        List<StorageEntity> rewritten = saveAllCaptor.getValue();
        assertThat(rewritten).hasSize(1);
        assertThat(rewritten.get(0).getData())
                .as("the cloned FileRef must address the CLONED storage row, not the source row")
                .contains("\"id\":\"" + cloneId + "\"")
                .doesNotContain("\"id\":\"" + sourceId + "\"");
    }

    /**
     * Proves the claim in {@code rewriteFileRefIds}' Javadoc that the second pass resolves
     * CROSS-STEP references (row A's output embeds row B's file id) via the COMPLETE old→new mapping,
     * and that it rewrites {@code dataMapped} as well as {@code data}.
     */
    @Test
    @DisplayName("clone rewrites a cross-step FileRef id (in dataMapped) via the full old→new mapping")
    void rewritesCrossStepIdInDataMapped() {
        UUID srcA = UUID.randomUUID();
        UUID srcB = UUID.randomUUID();
        UUID cloneA = UUID.randomUUID();
        UUID cloneB = UUID.randomUUID();

        StorageEntity a = jsonRow(srcA);
        // A's dataMapped references B's file (a cross-step reference, not a self-reference).
        a.setDataMapped("{\"ref\":{\"_type\":\"file\",\"path\":\"pa\",\"name\":\"a\","
                + "\"mimeType\":\"image/png\",\"size\":1,\"id\":\"" + srcB + "\"}}");
        StorageEntity b = jsonRow(srcB);
        b.setData("{\"file\":{\"_type\":\"file\",\"path\":\"pb\",\"name\":\"b\","
                + "\"mimeType\":\"image/png\",\"size\":1,\"id\":\"" + srcB + "\"}}");

        when(storageRepository.findAllById(Set.of(srcA, srcB))).thenReturn(List.of(a, b));
        // Deterministic clone ids in save order (A then B, matching the findAllById list order).
        java.util.Iterator<UUID> ids = List.of(cloneA, cloneB).iterator();
        when(storageRepository.save(org.mockito.ArgumentMatchers.any(StorageEntity.class)))
                .thenAnswer(inv -> {
                    StorageEntity arg = inv.getArgument(0);
                    arg.setId(ids.next());
                    return arg;
                });
        when(storageRepository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        runCloneService.cloneStorageEntries(Set.of(srcA, srcB), "new-run", "tenant-alice", UUID.randomUUID());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StorageEntity>> cap = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(storageRepository).saveAll(cap.capture());
        StorageEntity cloneOfA = cap.getValue().stream()
                .filter(e -> e.getDataMapped() != null).findFirst().orElseThrow();
        assertThat(cloneOfA.getDataMapped())
                .as("A's dataMapped cross-ref to B must point at B's CLONE id, never B's source id")
                .contains("\"id\":\"" + cloneB + "\"")
                .doesNotContain("\"id\":\"" + srcB + "\"");
    }

    /**
     * Reproduction for the publication-snapshot audit CRITICAL "C3": the showcase-run S3 blob copy
     * downloads with the SINGLE-ARG {@code download(key)} overload, which the StorageClientAdapter
     * maps to {@code storageClient.download(null, key)} - i.e. no {@code X-User-ID}. In s3 mode
     * (prod/R2) storage-service refuses a null-tenant internal download with 403, the client swallows
     * it to an empty Optional, and {@code cloneStorageEntries} throws "S3 file not found during clone",
     * aborting the ENTIRE showcase clone for any run that produced an S3-backed file.
     *
     * <p>The blob is owned by the PUBLISHER (the source row's tenant), not the acquiring caller, so the
     * copy must download under {@code source.getTenantId()} (the key owner) - exactly the trap CLAUDE.md
     * names for RunCloneService. This test fails on the current single-arg call (the clone throws) and
     * passes once the download uses the 2-arg owner-tenant overload.</p>
     */
    @Test
    @DisplayName("clone of an S3-backed row downloads under the blob OWNER tenant, not a null/caller tenant (audit C3)")
    void downloadsS3BlobUnderKeyOwnerTenant() {
        UUID sourceId = UUID.randomUUID();
        String ownerTenant = "tenant-alice";   // publisher owns the showcase blob
        String callerTenant = "tenant-bob";     // acquirer running the clone
        String s3Key = ownerTenant + "/run-1/agent/out.png";

        StorageEntity source = jsonRow(sourceId);   // tenantId=tenant-alice, org=org-acme
        source.setStorageType("S3");
        source.setS3Key(s3Key);
        source.setData("{}");
        source.setFileName("out.png");
        source.setMimeType("image/png");
        source.setStepKey("agent");

        when(storageRepository.findAllById(Set.of(sourceId))).thenReturn(List.of(source));
        lenient().when(storageRepository.save(any(StorageEntity.class)))
                .thenAnswer(inv -> { StorageEntity a = inv.getArgument(0); a.setId(UUID.randomUUID()); return a; });
        lenient().when(storageRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // The blob belongs to tenant-alice → ONLY the owner-tenant 2-arg download returns it.
        // The single-arg download(key) sends X-User-ID:null → storage 403 → empty (Mockito default).
        lenient().when(fileStorageService.download(eq(ownerTenant), eq(s3Key)))
                .thenReturn(Optional.of(new byte[]{1, 2, 3}));
        lenient().when(fileStorageService.upload(anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), any(byte[].class)))
                .thenReturn(FileRef.of(callerTenant + "/new-run/cloned/agent/out.png", "out.png", "image/png", 3));

        assertThatCode(() -> runCloneService.cloneStorageEntries(
                        Set.of(sourceId), "new-run", callerTenant, UUID.randomUUID()))
                .as("cloning an S3-backed showcase run must succeed; pre-fix the null-tenant download "
                  + "yields empty → 'S3 file not found during clone' and the whole clone aborts in prod")
                .doesNotThrowAnyException();

        verify(fileStorageService).download(ownerTenant, s3Key);
    }

    private static StorageEntity jsonRow(UUID id) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setTenantId("tenant-alice");
        e.setOrganizationId("org-acme");
        e.setContentType("application/json");
        e.setStorageType("JSON");
        e.setSizeBytes(0);
        e.setEpoch(0);
        e.setSpawn(0);
        e.setStatus(StorageStatus.ACTIVE);
        e.setCreatedAt(Instant.now());
        e.setAccessedAt(Instant.now());
        return e;
    }
}
