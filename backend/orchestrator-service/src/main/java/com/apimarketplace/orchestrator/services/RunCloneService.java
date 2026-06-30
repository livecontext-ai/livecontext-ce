package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.interfaces.client.dto.SnapshotCreateRequest;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for cloning workflow runs with all associated data into new rows.
 * Used for showcase runs on publications: creates a full copy of a run
 * (workflow_runs, workflow_step_data, workflow_epochs, storage entries)
 * so the showcase can be displayed independently of the original run.
 */
@Service
public class RunCloneService {

    private static final Logger logger = LoggerFactory.getLogger(RunCloneService.class);

    private static final String SHOWCASE_PREFIX = "showcase_";

    /** Bound on descendant sub-workflow runs scanned during a clone (guards a pathological graph). */
    private static final int MAX_DESCENDANT_RUN_SCAN = 500;

    private static final String CLONE_EPOCHS_SQL = """
            INSERT INTO workflow_epochs (run_id, trigger_id, epoch, entry_type, entry_key, status, count,
                                         epoch_state, is_active, started_at, closed_at, updated_at, duration_ms)
            SELECT ?, trigger_id, epoch, entry_type, entry_key, status, count,
                   epoch_state, is_active, started_at, closed_at, updated_at, duration_ms
            FROM workflow_epochs WHERE run_id = ?
            """;

    private static final String DELETE_EPOCHS_SQL = "DELETE FROM workflow_epochs WHERE run_id = ?";

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepDataRepository workflowStepDataRepository;
    private final StorageRepository storageRepository;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;
    private final InterfaceClient interfaceClient;

    public RunCloneService(WorkflowRunRepository workflowRunRepository,
                           WorkflowStepDataRepository workflowStepDataRepository,
                           StorageRepository storageRepository,
                           FileStorageService fileStorageService,
                           JdbcTemplate jdbcTemplate,
                           StorageBreakdownService breakdownService,
                           ObjectMapper objectMapper,
                           InterfaceClient interfaceClient) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepDataRepository = workflowStepDataRepository;
        this.storageRepository = storageRepository;
        this.fileStorageService = fileStorageService;
        this.jdbcTemplate = jdbcTemplate;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
        this.interfaceClient = interfaceClient;
    }

    /**
     * Clone a run with all its data for showcase purposes.
     * Copies: workflow_runs, workflow_step_data, workflow_epochs, storage entries.
     *
     * @param sourceRunIdPublic the public run ID of the source run to clone
     * @param newSource         the source label for the cloned run (e.g., "showcase")
     * @param publicationId     the publication ID to associate with the cloned run
     * @return the newly created WorkflowRunEntity
     * @throws IllegalArgumentException if the source run is not found
     */
    @Transactional
    public WorkflowRunEntity cloneRun(String sourceRunIdPublic, String newSource, String publicationId) {
        // 1. Load source run
        WorkflowRunEntity sourceRun = workflowRunRepository.findByRunIdPublic(sourceRunIdPublic)
                .orElseThrow(() -> new IllegalArgumentException("Source run not found: " + sourceRunIdPublic));

        // 2. Create new WorkflowRunEntity (copy all fields with new identifiers)
        String cloneRunIdPublic = SHOWCASE_PREFIX + UUID.randomUUID().toString().substring(0, 8);

        WorkflowRunEntity clone = new WorkflowRunEntity();
        clone.setWorkflow(sourceRun.getWorkflow());
        clone.setTenantId(sourceRun.getTenantId());
        clone.setRunIdPublic(cloneRunIdPublic);
        clone.setStatus(sourceRun.getStatus());
        clone.setExecutionMode(sourceRun.getExecutionMode());
        clone.setStartedAt(sourceRun.getStartedAt());
        clone.setEndedAt(sourceRun.getEndedAt());
        clone.setDurationMs(sourceRun.getDurationMs());
        clone.setTotalNodes(sourceRun.getTotalNodes());
        clone.setTriggerPayload(sourceRun.getTriggerPayload());
        clone.setMetadata(sourceRun.getMetadata());
        clone.setPlan(sourceRun.getPlan());
        clone.setStateSnapshot(sourceRun.getStateSnapshot());
        // A2 Phase 4 (audit Opus 2026-05-09 M2): mirror the seq column too,
        // otherwise the clone has SQL=0 vs JSONB.seq=K → every read on the
        // clone seq-mismatches the L1 cache forever (until the first
        // mutator) and degrades to the L2 DB+parse path on every SSE poll.
        clone.setStateSnapshotSeq(sourceRun.getStateSnapshotSeq());
        clone.setPlanVersion(sourceRun.getPlanVersion());
        clone.setSource(newSource);
        clone.setPublicationId(publicationId);
        clone.setCreatedBy(sourceRun.getCreatedBy());
        clone.setCreatedAt(Instant.now());
        clone.setUpdatedAt(Instant.now());

        clone = workflowRunRepository.save(clone);
        logger.info("[RunClone] Created clone run {} from source {}", cloneRunIdPublic, sourceRunIdPublic);

        // EXECUTION_DATA tracked by daily reconciliation only

        // 3. Clone storage entries (outputs)
        List<WorkflowStepDataEntity> sourceSteps = workflowStepDataRepository
                .findByWorkflowRunIdOrderByIdAsc(sourceRun.getId());

        Set<UUID> storageIds = sourceSteps.stream()
                .map(WorkflowStepDataEntity::getOutputStorageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // A sub-workflow runs as a SEPARATE WorkflowRunEntity; the parent step output embeds the
        // child's result (FileRefs that reference the CHILD run's storage rows by id) plus the
        // child's subRunId. Pull in those descendant storage rows so the clone physically COPIES
        // the data and the second-pass id rewrite remaps the embedded refs - otherwise the cloned
        // showcase still addresses the ORIGINAL child-run blobs by id (cross-tenant leak / 404 once
        // the original run is cleaned).
        Set<UUID> descendantStorageIds = collectDescendantStorageIds(sourceRun, storageIds);
        if (!descendantStorageIds.isEmpty()) {
            storageIds.addAll(descendantStorageIds);
            logger.info("[RunClone] including {} descendant sub-workflow storage rows in clone of {}",
                    descendantStorageIds.size(), sourceRunIdPublic);
        }

        UUID pubUuid = publicationId != null ? UUID.fromString(publicationId) : null;
        Map<UUID, UUID> storageIdMapping = cloneStorageEntries(storageIds, cloneRunIdPublic, sourceRun.getTenantId(), pubUuid);

        // 4. Clone workflow_step_data rows
        cloneStepData(sourceSteps, clone.getId(), cloneRunIdPublic, storageIdMapping);

        // 5. Clone workflow_epochs rows (single INSERT...SELECT)
        int epochRows = jdbcTemplate.update(CLONE_EPOCHS_SQL, cloneRunIdPublic, sourceRunIdPublic);
        logger.info("[RunClone] Cloned {} epoch rows for run {}", epochRows, cloneRunIdPublic);

        // 6. Clone interface snapshots (variable mappings, action mappings, templates)
        cloneInterfaceSnapshots(sourceRun.getId(), clone.getId(), sourceRun.getTenantId());

        return clone;
    }

    /**
     * A showcase run can contain SubWorkflowNode steps; each sub-workflow executes as its own
     * {@link WorkflowRunEntity}. The parent step output embeds the child's FileRefs (which
     * reference the CHILD run's storage rows by id) and the child's {@code subRunId}. This walks
     * the parent run's stored outputs for {@code subRunId} values, loads each descendant run's
     * output storage rows, and recurses (nested sub-workflows) - so the clone copies every
     * descendant blob and the second-pass id rewrite can remap the embedded refs. Bounded +
     * cycle-guarded for pathological graphs.
     */
    private Set<UUID> collectDescendantStorageIds(WorkflowRunEntity rootRun, Set<UUID> rootStorageIds) {
        Set<UUID> result = new LinkedHashSet<>();
        Set<String> visitedRuns = new HashSet<>();
        if (rootRun.getRunIdPublic() != null) visitedRuns.add(rootRun.getRunIdPublic());
        Deque<String> runQueue = new ArrayDeque<>(scanSubRunIds(rootStorageIds));
        int scanned = 0;
        while (!runQueue.isEmpty() && scanned++ < MAX_DESCENDANT_RUN_SCAN) {
            String childRunPublic = runQueue.poll();
            if (childRunPublic == null || !visitedRuns.add(childRunPublic)) continue;
            WorkflowRunEntity childRun = workflowRunRepository.findByRunIdPublic(childRunPublic).orElse(null);
            if (childRun == null) continue;
            Set<UUID> childStorageIds = workflowStepDataRepository
                    .findByWorkflowRunIdOrderByIdAsc(childRun.getId()).stream()
                    .map(WorkflowStepDataEntity::getOutputStorageId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            result.addAll(childStorageIds);
            runQueue.addAll(scanSubRunIds(childStorageIds)); // nested sub-workflows
        }
        if (scanned >= MAX_DESCENDANT_RUN_SCAN) {
            logger.warn("[RunClone] descendant sub-workflow scan hit cap {} for run {} - deeper "
                    + "child runs' files may be omitted from the clone", MAX_DESCENDANT_RUN_SCAN, rootRun.getRunIdPublic());
        }
        return result;
    }

    /** Load the given storage rows and collect every {@code subRunId} string in their JSON output. */
    private Set<String> scanSubRunIds(Set<UUID> storageIds) {
        Set<String> ids = new HashSet<>();
        if (storageIds == null || storageIds.isEmpty()) return ids;
        for (StorageEntity row : storageRepository.findAllById(storageIds)) {
            collectSubRunIdsFromJson(row.getData(), ids);
            collectSubRunIdsFromJson(row.getDataMapped(), ids);
        }
        return ids;
    }

    private void collectSubRunIdsFromJson(String json, Set<String> out) {
        if (json == null || json.isBlank()) return;
        try {
            walkForSubRunId(objectMapper.readTree(json), out);
        } catch (Exception e) {
            // non-JSON or unparseable output - nothing to traverse
        }
    }

    private void walkForSubRunId(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode sr = node.get("subRunId");
            if (sr != null && sr.isTextual() && !sr.asText().isBlank()) out.add(sr.asText());
            node.fields().forEachRemaining(e -> walkForSubRunId(e.getValue(), out));
        } else if (node.isArray()) {
            for (JsonNode c : node) walkForSubRunId(c, out);
        }
    }

    /**
     * Delete a previously cloned showcase run and all its associated data.
     *
     * @param runIdPublic the public run ID of the showcase run to delete
     * @throws IllegalArgumentException if the run is not found or is not a showcase run
     */
    @Transactional
    public void deleteClonedRun(String runIdPublic) {
        if (!runIdPublic.startsWith(SHOWCASE_PREFIX)) {
            throw new IllegalArgumentException("Not a showcase run: " + runIdPublic);
        }

        WorkflowRunEntity run = workflowRunRepository.findByRunIdPublic(runIdPublic)
                .orElseThrow(() -> new IllegalArgumentException("Showcase run not found: " + runIdPublic));

        // Delete epochs
        int epochRows = jdbcTemplate.update(DELETE_EPOCHS_SQL, runIdPublic);
        logger.info("[RunClone] Deleted {} epoch rows for showcase run {}", epochRows, runIdPublic);

        // Post-2026-05-22 OOM hardening: projection of output_storage_id only - no JSONB
        // load. The full-entity load was a 17k-row × heavy-JSONB pattern on the deletion
        // path that mirrored the 2026-05-07 OOM shape.
        Set<UUID> storageIds = new HashSet<>(
                workflowStepDataRepository.findOutputStorageIdsByWorkflowRunId(run.getId()));

        if (!storageIds.isEmpty()) {
            // Post-audit-9.5 fix: batch DELETE by id without entity hydration. The previous
            // findAllById + deleteAll dance materialised the full JSONB column just to delete,
            // mirroring the 2026-05-07 OOM shape on the deletion path.
            int deleted = storageRepository.deleteAllByIds(storageIds);
            logger.info("[RunClone] Deleted {} storage entries for showcase run {}", deleted, runIdPublic);
        }

        // Delete step data
        workflowStepDataRepository.deleteByRunId(runIdPublic);
        logger.info("[RunClone] Deleted step data for showcase run {}", runIdPublic);

        // Delete interface snapshots
        try {
            interfaceClient.deleteSnapshotsForRun(run.getId(), run.getTenantId());
            logger.info("[RunClone] Deleted interface snapshots for showcase run {}", runIdPublic);
        } catch (Exception e) {
            logger.warn("[RunClone] Failed to delete interface snapshots: {}", e.getMessage());
        }

        // EXECUTION_DATA tracked by daily reconciliation only

        // Delete run entity
        workflowRunRepository.delete(run);
        logger.info("[RunClone] Deleted showcase run {}", runIdPublic);
    }

    /**
     * Clone interface snapshots from the source run to the clone run.
     * This ensures variable mappings and action mappings are available for the cloned run.
     */
    private void cloneInterfaceSnapshots(UUID sourceWorkflowRunId, UUID cloneWorkflowRunId, String tenantId) {
        try {
            List<InterfaceSnapshotDto> snapshots = interfaceClient.getSnapshotsForRun(sourceWorkflowRunId, tenantId);
            if (snapshots.isEmpty()) {
                logger.info("[RunClone] No interface snapshots to clone for source run {}", sourceWorkflowRunId);
                return;
            }

            int cloned = 0;
            for (InterfaceSnapshotDto snapshot : snapshots) {
                SnapshotCreateRequest request = new SnapshotCreateRequest(
                        snapshot.getInterfaceId(),
                        cloneWorkflowRunId,
                        snapshot.getVariableMappings(),
                        snapshot.getActionMappings()
                );
                interfaceClient.createSnapshot(request, tenantId);
                cloned++;
            }
            logger.info("[RunClone] Cloned {} interface snapshots for run {}", cloned, cloneWorkflowRunId);
        } catch (Exception e) {
            logger.warn("[RunClone] Failed to clone interface snapshots: {}", e.getMessage());
        }
    }

    /**
     * Clone storage entries and build a mapping from old IDs to new IDs.
     * Package-private to allow direct unit-test access to the org_id
     * preservation contract pinned by RunCloneServiceCloneStorageTest.
     */
    Map<UUID, UUID> cloneStorageEntries(Set<UUID> storageIds, String newRunId, String tenantId, UUID publicationId) {
        if (storageIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, UUID> mapping = new HashMap<>();
        List<StorageEntity> copies = new java.util.ArrayList<>();
        List<StorageEntity> sources = storageRepository.findAllById(storageIds);

        for (StorageEntity source : sources) {
            StorageEntity copy = new StorageEntity();
            copy.setTenantId(source.getTenantId());
            // PR18 - preserve organization_id on clone so strict-isolation
            // org filters keep finding the row after marketplace acquisition.
            // Audit-1 finding M2: silent loss of org tag was demoting org
            // workspace data to personal scope.
            copy.setOrganizationId(source.getOrganizationId());
            copy.setContentType(source.getContentType());
            copy.setData(source.getData());
            copy.setDataBinary(source.getDataBinary());
            copy.setDataText(source.getDataText());
            copy.setStorageType(source.getStorageType());
            copy.setFileName(source.getFileName());
            copy.setFileExtension(source.getFileExtension());
            copy.setMimeType(source.getMimeType());
            copy.setWidth(source.getWidth());
            copy.setHeight(source.getHeight());
            copy.setDuration(source.getDuration());
            copy.setMetadata(source.getMetadata());
            copy.setDataMapped(source.getDataMapped());
            copy.setStructureSkeleton(source.getStructureSkeleton());
            copy.setSizeBytes(source.getSizeBytes());
            copy.setChecksum(source.getChecksum());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setAccessedAt(Instant.now());
            copy.setExpiresAt(source.getExpiresAt());
            copy.setStatus(StorageStatus.ACTIVE);
            copy.setRunId(newRunId);
            copy.setStepKey(source.getStepKey());
            copy.setItemIndex(source.getItemIndex());
            copy.setEpoch(source.getEpoch());
            copy.setSpawn(source.getSpawn());
            copy.setWorkflowId(source.getWorkflowId());
            copy.setSourceType(source.getSourceType());
            copy.setSourcePublicationId(publicationId);

            // Copy the S3 blob so the clone owns an independent file.
            // Download under the blob's OWNER tenant (the source row's tenant), not the caller:
            // the single-arg download() sends X-User-ID:null, which storage-service refuses with
            // 403 in s3 mode (prod/R2) → empty Optional → the whole clone aborts here.
            if (source.getS3Key() != null && !source.getS3Key().isBlank()) {
                var content = fileStorageService.download(source.getTenantId(), source.getS3Key());
                if (content.isEmpty()) {
                    throw new RuntimeException("S3 file not found during clone: " + source.getS3Key());
                }
                String fileName = source.getFileName() != null ? source.getFileName() : "file";
                String mimeType = source.getMimeType() != null ? source.getMimeType() : "application/octet-stream";
                String stepKey = source.getStepKey() != null ? source.getStepKey() : "unknown";
                FileRef newRef = fileStorageService.upload(
                        tenantId, newRunId, "cloned", stepKey,
                        fileName, mimeType, content.get());
                copy.setS3Key(newRef.path());

                // Rewrite file_url in dataMapped and data to point to the cloned file
                String oldPath = source.getS3Key();
                String newPath = newRef.path();
                rewriteFileUrls(copy, oldPath, newPath);
            }

            copy = storageRepository.save(copy);
            mapping.put(source.getId(), copy.getId());
            copies.add(copy);
        }

        // Second pass - rewrite embedded FileRef `id` references (storage-row UUIDs) from the source
        // ids to the cloned ids now that the full old→new mapping is known. The first-pass
        // rewriteFileUrls only fixes the s3 `path`; the opaque file URL is built from `id`, so a clone
        // whose FileRef.id still pointed at the source row would render the source owner's file (or
        // 404 cross-org). Cross-step references resolve here too (mapping covers every cloned row).
        rewriteFileRefIds(copies, mapping);

        logger.info("[RunClone] Cloned {} storage entries for run {}", mapping.size(), newRunId);
        return mapping;
    }

    /**
     * Clone step data rows, remapping storage IDs where applicable.
     */
    private void cloneStepData(List<WorkflowStepDataEntity> sourceSteps,
                               UUID cloneRunId,
                               String cloneRunIdPublic,
                               Map<UUID, UUID> storageIdMapping) {
        List<WorkflowStepDataEntity> clones = new ArrayList<>(sourceSteps.size());

        for (WorkflowStepDataEntity source : sourceSteps) {
            UUID remappedStorageId = source.getOutputStorageId() != null
                    ? storageIdMapping.getOrDefault(source.getOutputStorageId(), source.getOutputStorageId())
                    : null;

            WorkflowStepDataEntity copy = new WorkflowStepDataEntity(
                    cloneRunId,
                    cloneRunIdPublic,
                    source.getStepAlias(),
                    source.getToolId(),
                    source.getInputData(),
                    remappedStorageId,
                    source.getHttpStatus(),
                    source.getStatus(),
                    source.getStartTime(),
                    source.getEndTime(),
                    source.getErrorMessage(),
                    source.getTenantId(),
                    source.getEpoch(),
                    source.getSpawn(),
                    source.getIteration(),
                    source.getItemIndex(),
                    source.getMetadata()
            );

            // Copy unified columns
            copy.setOrganizationId(source.getOrganizationId());
            copy.setNodeType(source.getNodeType());
            copy.setConditionExpression(source.getConditionExpression());
            copy.setConditionResult(source.getConditionResult());
            copy.setSelectedBranch(source.getSelectedBranch());
            copy.setLoopId(source.getLoopId());
            copy.setLoopIteration(source.getLoopIteration());
            copy.setLoopExitReason(source.getLoopExitReason());
            copy.setMergeStrategy(source.getMergeStrategy());
            copy.setMergeReceivedBranches(source.getMergeReceivedBranches());
            copy.setMergeSkippedBranches(source.getMergeSkippedBranches());
            copy.setItemId(source.getItemId());
            copy.setTriggerId(source.getTriggerId());
            copy.setSkipReason(source.getSkipReason());
            copy.setSkipSourceNode(source.getSkipSourceNode());
            copy.setNormalizedKey(source.getNormalizedKey());
            copy.setItemNumber(source.getItemNumber());

            clones.add(copy);
        }

        workflowStepDataRepository.saveAll(clones);
        logger.info("[RunClone] Cloned {} step data rows for run {}", clones.size(), cloneRunIdPublic);
    }

    /**
     * Remap the embedded s3 key (FileRef {@code path}) in a cloned row's JSON from the source key to
     * the re-uploaded key. Operates on the raw JSON string ({@code data}/{@code dataMapped} are stored
     * as JSON text) - no parse/serialize round-trip. Post-cutover there is no materialized file URL to
     * remap (the opaque URL is rebuilt from {@code id}, rewritten in {@link #rewriteFileRefIds}); only
     * the wiring {@code path} needs the old→new key swap.
     */
    private void rewriteFileUrls(StorageEntity entity, String oldPath, String newPath) {
        String mapped = replacePathInJson(entity.getDataMapped(), oldPath, newPath);
        if (mapped != null) {
            entity.setDataMapped(mapped);
        }
        String data = replacePathInJson(entity.getData(), oldPath, newPath);
        if (data != null) {
            entity.setData(data);
        }
    }

    /** Replace every occurrence of {@code oldPath} with {@code newPath} in the raw JSON; null if unchanged. */
    private String replacePathInJson(String json, String oldPath, String newPath) {
        if (json == null || !json.contains(oldPath)) {
            return null;
        }
        return json.replace(oldPath, newPath);
    }

    /**
     * Second-pass rewrite of embedded FileRef {@code id} references (storage-row UUIDs) from the
     * source ids to the cloned ids, using the complete old→new {@code mapping}. The opaque file URL
     * is built from {@code id}, so without this a cloned FileRef would still address the source row
     * (the source owner's file, or a cross-org 404). Re-saves only the copies whose JSON changed.
     */
    private void rewriteFileRefIds(List<StorageEntity> copies, Map<UUID, UUID> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        List<StorageEntity> changed = new java.util.ArrayList<>();
        for (StorageEntity copy : copies) {
            boolean dirty = false;
            String mapped = rewriteIdsInJson(copy.getDataMapped(), mapping);
            if (mapped != null) {
                copy.setDataMapped(mapped);
                dirty = true;
            }
            String data = rewriteIdsInJson(copy.getData(), mapping);
            if (data != null) {
                copy.setData(data);
                dirty = true;
            }
            if (dirty) {
                changed.add(copy);
            }
        }
        if (!changed.isEmpty()) {
            storageRepository.saveAll(changed);
        }
    }

    /**
     * Replace every {@code "id":"<oldUuid>"} occurrence (compact-JSON FileRef id reference) in the raw
     * JSON string using the old→new {@code mapping}. Returns the rewritten JSON, or {@code null} when
     * nothing changed. Operates on the raw string ({@code dataMapped}/{@code data} are stored as JSON
     * text) - no parse/serialize round-trip.
     */
    private String rewriteIdsInJson(String json, Map<UUID, UUID> mapping) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        String updated = json;
        for (Map.Entry<UUID, UUID> e : mapping.entrySet()) {
            updated = updated.replace("\"id\":\"" + e.getKey() + "\"", "\"id\":\"" + e.getValue() + "\"");
        }
        return updated.equals(json) ? null : updated;
    }

    /**
     * Estimates the size of a workflow run's EXECUTION_DATA columns
     * (state_snapshot, plan, trigger_payload, metadata) in bytes.
     * Matches the columns measured by StorageReconciliationQueries.EXECUTION_DATA.
     */
    private long estimateRunExecutionDataSize(WorkflowRunEntity run) {
        long size = 0;
        try {
            if (run.getStateSnapshot() != null) {
                size += run.getStateSnapshot().getBytes(StandardCharsets.UTF_8).length;
            }
            if (run.getPlan() != null) {
                size += objectMapper.writeValueAsBytes(run.getPlan()).length;
            }
            if (run.getTriggerPayload() != null) {
                size += objectMapper.writeValueAsBytes(run.getTriggerPayload()).length;
            }
            if (run.getMetadata() != null) {
                size += objectMapper.writeValueAsBytes(run.getMetadata()).length;
            }
        } catch (Exception e) {
            logger.debug("Failed to estimate run execution data size for run {}: {}",
                    run.getRunIdPublic(), e.getMessage());
        }
        return size;
    }
}
