package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable service for cloning a planSnapshot into a tenant's workspace.
 * Extracted from WorkflowPublicationService to be shared between the normal
 * acquire flow and the CE remote-marketplace acquire flow.
 */
@Service
public class SnapshotCloneService {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotCloneService.class);

    private final OrchestratorInternalClient orchestratorClient;
    private final AgentClient agentClient;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;
    private final DataSourceFileCloneService fileCloneService;

    /**
     * Acquire-time avatar file copy (snapshot autonomy). Field-injected to spare the
     * many test constructions; null (unit tests) falls back to the publishable pass-through.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    AvatarFileCloneService avatarFileCloneService;

    public SnapshotCloneService(OrchestratorInternalClient orchestratorClient,
                                 AgentClient agentClient,
                                 InterfaceClient interfaceClient,
                                 DataSourceClient dataSourceClient,
                                 StorageBreakdownService breakdownService,
                                 ObjectMapper objectMapper,
                                 DataSourceFileCloneService fileCloneService) {
        this.orchestratorClient = orchestratorClient;
        this.agentClient = agentClient;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
        this.fileCloneService = fileCloneService;
    }

    /**
     * Workflow-type stamps for cloned rows. Only the ROOT clone of a workflow
     * acquisition is an APPLICATION; every other workflow cloned alongside it
     * (sub-workflow children, agent-publication workflows) is a standard
     * WORKFLOW. The V268 unique index {@code uq_workflow_org_source_pub_application}
     * allows at most ONE APPLICATION per (organization, source publication),
     * and the /app/applications/{pubId} root lookup
     * ({@code findByOrganizationIdAndSourcePublicationIdAndWorkflowType}) relies
     * on that uniqueness.
     */
    public static final String ROOT_TYPE_APPLICATION = "APPLICATION";
    public static final String CLONE_TYPE_WORKFLOW = "WORKFLOW";

    /**
     * Clone a planSnapshot into the given tenant's workspace.
     * Creates all referenced resources (sub-workflows, interfaces, datasources, agents, files)
     * and returns the created root workflow info (an APPLICATION row).
     *
     * @param planSnapshot  the enriched plan snapshot (will be deep-copied internally)
     * @param tenantId      the acquiring tenant
     * @param publicationId the source publication ID (for tracking)
     * @param title         workflow title
     * @param description   workflow description
     * @param nodeIcons     node icon metadata
     * @return map with "workflowId" and "title"
     */
    public Map<String, Object> cloneFromSnapshot(Map<String, Object> planSnapshot,
                                                  String tenantId,
                                                  UUID publicationId,
                                                  String title,
                                                  String description,
                                                  List<Map<String, Object>> nodeIcons) {
        return cloneFromSnapshot(planSnapshot, tenantId, publicationId, title, description, nodeIcons, null);
    }

    public Map<String, Object> cloneFromSnapshot(Map<String, Object> planSnapshot,
                                                  String tenantId,
                                                  UUID publicationId,
                                                  String title,
                                                  String description,
                                                  List<Map<String, Object>> nodeIcons,
                                                  String organizationId) {
        return cloneFromSnapshot(planSnapshot, tenantId, publicationId, title, description, nodeIcons,
                organizationId, ROOT_TYPE_APPLICATION);
    }

    /**
     * @param rootWorkflowType type stamped on the ROOT clone - {@link #ROOT_TYPE_APPLICATION}
     *                         for workflow/application acquisitions,
     *                         {@link #CLONE_TYPE_WORKFLOW} for workflows cloned as part of
     *                         an AGENT publication (which has no application root at all).
     */
    public Map<String, Object> cloneFromSnapshot(Map<String, Object> planSnapshot,
                                                  String tenantId,
                                                  UUID publicationId,
                                                  String title,
                                                  String description,
                                                  List<Map<String, Object>> nodeIcons,
                                                  String organizationId,
                                                  String rootWorkflowType) {
        // Real acquire / agent-publication path: the file namespace and the row
        // source-tag are the SAME publication id, and there is no duplication
        // lineage. Delegating with fileNamespaceId == sourcePublicationId ==
        // publicationId keeps this a strict no-op relative to the pre-refactor
        // behavior (pinned by SnapshotCloneServiceCharacterizationTest).
        return cloneFromSnapshotInternal(planSnapshot, tenantId, publicationId, publicationId,
                title, description, nodeIcons, organizationId, rootWorkflowType, null);
    }

    /**
     * Duplicate a plan into the tenant's workspace as a freely-editable, DECOUPLED
     * {@code WORKFLOW} (never an {@code APPLICATION}). This is the engine behind
     * "downloading an application also clones its workflow": the acquired application
     * stays run-only while this twin lands in {@code /app/workflows}, fully editable.
     *
     * <p>Decoupling is FREE from the data model: a {@code WORKFLOW} row with
     * {@code source_publication_id = NULL} is invisible to every
     * My-Applications/execute/uninstall lookup (they filter
     * {@code workflow_type='APPLICATION'}) and is exempt from the V268 partial unique
     * index (which is {@code WHERE workflow_type='APPLICATION' AND source_publication_id
     * IS NOT NULL}). The lineage to the application it was duplicated from is carried
     * in {@code metadata.duplicatedFromApplicationId} (a key nothing reads today),
     * NEVER in {@code source_publication_id}.
     *
     * @param fileNamespaceId the {@code _publications/{id}/} namespace the embedded
     *        file refs in {@code planSnapshot} live under. When the duplicate is sourced
     *        from a publication's planSnapshot, this is that publication's id (so the
     *        clone-time allowlist matches and files re-copy into the acquirer's tenant).
     *        MUST be non-null - it stands in for the now-null {@code sourcePublicationId}
     *        so the file namespace never degrades to {@code _publications/null/}.
     * @param duplicatedFromApplicationId the APPLICATION clone this workflow was
     *        duplicated from - stored under {@code metadata.duplicatedFromApplicationId}.
     */
    public Map<String, Object> duplicateToEditableWorkflow(Map<String, Object> planSnapshot,
                                                            String tenantId,
                                                            String organizationId,
                                                            String title,
                                                            String description,
                                                            List<Map<String, Object>> nodeIcons,
                                                            UUID fileNamespaceId,
                                                            String duplicatedFromApplicationId) {
        Map<String, Object> metadata = new HashMap<>();
        if (hasText(duplicatedFromApplicationId)) {
            metadata.put("duplicatedFromApplicationId", duplicatedFromApplicationId);
        }
        // sourcePublicationId = null -> a decoupled, editable WORKFLOW row.
        return cloneFromSnapshotInternal(planSnapshot, tenantId, null, fileNamespaceId,
                title, description, nodeIcons, organizationId, CLONE_TYPE_WORKFLOW, metadata);
    }

    /**
     * Core clone engine shared by the real acquire path
     * ({@link #cloneFromSnapshot}) and the decoupled-duplicate path
     * ({@link #duplicateToEditableWorkflow}).
     *
     * <p>Two ids are threaded SEPARATELY and must not be conflated:
     * <ul>
     *   <li>{@code sourcePublicationId} (NULLABLE) - stamped on the created
     *       workflow rows + datasource snapshots as the marketplace source tag.
     *       {@code null} for a decoupled duplicate; when null the field is OMITTED
     *       from every create request (never sent as the string "null").</li>
     *   <li>{@code fileNamespaceId} (NEVER null) - backs every
     *       {@code _publications/{id}/} file-namespace string (datasource file
     *       re-upload, agent file refs, the {@code scanAndCloneFileRefs} allowlist).
     *       Threading it separately is what lets {@code sourcePublicationId} be null
     *       without the file namespace degrading to {@code _publications/null/} and
     *       silently dropping every cloned file.</li>
     * </ul>
     */
    private Map<String, Object> cloneFromSnapshotInternal(Map<String, Object> planSnapshot,
                                                  String tenantId,
                                                  UUID sourcePublicationId,
                                                  UUID fileNamespaceId,
                                                  String title,
                                                  String description,
                                                  List<Map<String, Object>> nodeIcons,
                                                  String organizationId,
                                                  String rootWorkflowType,
                                                  Map<String, Object> metadata) {
        if (fileNamespaceId == null) {
            throw new IllegalArgumentException(
                    "fileNamespaceId must not be null (it backs the _publications/{id}/ file namespace)");
        }
        // True deep copy via JSON round-trip so mutations don't affect the source
        Map<String, Object> clonedPlan = objectMapper.convertValue(planSnapshot,
                new TypeReference<Map<String, Object>>() {});

        // F4 PUB-HIJACK fix: strip standalone trigger row UUIDs (scheduleId,
        // webhookId, chatEndpointId, formEndpointId) so the acquired clone
        // creates fresh standalone rows on first sync instead of inheriting
        // back-references to the source tenant's rows.
        com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(clonedPlan);

        // Pre-generate the main workflow ID so we can:
        // 1. Use it for agent scoping (tempWorkflowId)
        // 2. Remap __self__ sentinel sub-workflow references
        // 3. Pass it to createApplicationWorkflow for deterministic ID
        String tempWorkflowId = UUID.randomUUID().toString();

        // Track the EXACT workflow ids this acquisition creates, so a mid-pipeline failure can be
        // compensated by deleting ONLY these - never an org-wide sweep, which would delete a
        // CONCURRENT acquisition's rows (the winner) when two first-time acquires of the same
        // publication race.
        Set<String> createdWorkflowIds = new HashSet<>();
        try {
        // Clone sub-workflows FIRST (created as standard WORKFLOW rows - only the
        // root below is an APPLICATION - returns old->new mapping)
        Map<String, String> workflowMapping = cloneSubWorkflowsForTenant(clonedPlan, tenantId, sourcePublicationId, fileNamespaceId, organizationId);
        createdWorkflowIds.addAll(workflowMapping.values());

        // Remap __self__ sentinel to the actual workflow ID (self-referencing sub-workflows)
        remapSelfReferences(clonedPlan, tempWorkflowId);

        // Remap trigger IDs and agent workflow refs using the sub-workflow mapping
        remapTriggerWorkflowIds(clonedPlan, workflowMapping);
        remapAgentSnapshotWorkflows(clonedPlan, workflowMapping);

        // Clone datasource structure (schema + row data) FIRST so the cloned
        // datasource ids are available to remap interface FORM bindings (below)
        // and datasource triggers.
        Map<String, String> acquireDsMapping = cloneDatasourcesForTenant(clonedPlan, tenantId, sourcePublicationId, fileNamespaceId, organizationId);

        // Remap datasource trigger ids - triggers[type=datasource].id is the
        // numeric DataSource PK (DataSourceTriggerResolver does
        // Integer.valueOf(trigger.id())); without this, the cloned trigger
        // would point at the SOURCE tenant's datasource and go inert.
        remapDatasourceTriggerIds(clonedPlan, acquireDsMapping);

        // Clone interfaces from the enriched plan into the acquiring tenant.
        // dsMapping is passed so a FORM interface's dataSourceId binding is
        // remapped to the cloned datasource - otherwise the cloned form binds the
        // SOURCE tenant's datasource id and fails to load its table at render time.
        Map<String, String> acquireInterfaceMapping = cloneInterfacesForTenant(clonedPlan, tenantId, fileNamespaceId, organizationId, acquireDsMapping);
        // Remap deprecated triggers[]/mcps[].interfaceIds[] back-references to the
        // cloned interface ids - InterfacePlanExtractor still reads them at run time.
        remapInterfaceIdRefs(clonedPlan, acquireInterfaceMapping);

        // Clone agents (after interfaces/datasources so we can remap IDs in toolsConfig)
        cloneAgentsForTenant(clonedPlan, tenantId, fileNamespaceId, tempWorkflowId,
                acquireInterfaceMapping, acquireDsMapping, organizationId);

        // Clone DataInput files (S3 blobs) so the acquirer owns independent copies
        cloneDataInputFilesForTenant(clonedPlan, tenantId, tempWorkflowId, organizationId);

        // Strip sensitive credentials (HTTP auth + send email)
        stripSensitiveCredentials(clonedPlan);

        // Create the root workflow via orchestrator with pre-generated ID
        Map<String, Object> basePlanCopy = objectMapper.convertValue(clonedPlan,
                new TypeReference<Map<String, Object>>() {});

        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("id", tempWorkflowId);
        createRequest.put("title", title);
        createRequest.put("description", description);
        createRequest.put("plan", clonedPlan);
        createRequest.put("basePlan", basePlanCopy);
        // NULLABLE source tag: omit the field entirely for a decoupled duplicate so the
        // orchestrator stores null (the V268 partial index + APPLICATION lookups exempt it).
        if (sourcePublicationId != null) {
            createRequest.put("sourcePublicationId", sourcePublicationId.toString());
        }
        createRequest.put("nodeIcons", nodeIcons);
        createRequest.put("workflowType", rootWorkflowType);
        // Lineage / provenance for a decoupled duplicate (e.g. duplicatedFromApplicationId).
        // The link lives in metadata because source_publication_id is intentionally null.
        if (metadata != null && !metadata.isEmpty()) {
            createRequest.put("metadata", metadata);
        }
        if (hasText(organizationId)) {
            createRequest.put("organizationId", organizationId);
        }

        // Reserve the root id too: if createApplicationWorkflow half-creates the row then errors,
        // compensation cleans it; if it never created, deleteAcquiredWorkflow is a harmless no-op.
        createdWorkflowIds.add(tempWorkflowId);
        Map<String, Object> result = orchestratorClient.createApplicationWorkflow(createRequest, tenantId);
        if (result == null) {
            throw new RuntimeException("Failed to create root workflow for cloned plan from publication "
                    + (sourcePublicationId != null ? sourcePublicationId : fileNamespaceId));
        }

        logger.info("Tenant {} cloned plan (source pub {}, file namespace {}) -> workflow {}",
                tenantId, sourcePublicationId, fileNamespaceId, result.get("id"));

        Map<String, Object> acquireResult = new HashMap<>();
        acquireResult.put("workflowId", result.get("id"));
        acquireResult.put("title", title);
        return acquireResult;
        } catch (RuntimeException e) {
            // Surface the exact ids created so far so the acquire caller runs a SCOPED compensation
            // (delete only this acquisition's rows, not a concurrent winner's).
            throw new AcquireCloneFailedException(createdWorkflowIds, e);
        }
    }

    // ========================================================================
    // Interface cloning
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, String> cloneInterfacesForTenant(Map<String, Object> plan,
                                                          String tenantId,
                                                          UUID fileNamespaceId,
                                                          String organizationId,
                                                          Map<String, String> dsMapping) {
        Map<String, String> interfaceMapping = new HashMap<>();
        Object interfacesRaw = plan.get("interfaces");
        if (!(interfacesRaw instanceof List)) return interfaceMapping;

        List<Map<String, Object>> interfaces = new ArrayList<>((List<Map<String, Object>>) interfacesRaw);
        plan.put("interfaces", interfaces);

        for (Map<String, Object> ifaceNode : interfaces) {
            Object htmlTemplate = ifaceNode.get("_snapshot_htmlTemplate");
            if (htmlTemplate == null) continue;

            String oldId = ifaceNode.get("id") != null ? ifaceNode.get("id").toString() : null;

            InterfaceCreateRequest createReq = new InterfaceCreateRequest();
            createReq.setName(ifaceNode.get("_snapshot_name") != null
                    ? ifaceNode.get("_snapshot_name").toString() : "Acquired Interface");
            createReq.setDescription(ifaceNode.get("_snapshot_description") != null
                    ? ifaceNode.get("_snapshot_description").toString() : null);
            createReq.setHtmlTemplate(htmlTemplate.toString());
            createReq.setCssTemplate(ifaceNode.get("_snapshot_cssTemplate") != null
                    ? ifaceNode.get("_snapshot_cssTemplate").toString() : null);
            createReq.setJsTemplate(ifaceNode.get("_snapshot_jsTemplate") != null
                    ? ifaceNode.get("_snapshot_jsTemplate").toString() : null);
            createReq.setInterfaceType(ifaceNode.get("_snapshot_interfaceType") != null
                    ? ifaceNode.get("_snapshot_interfaceType").toString() : "html");
            // The format travels with the templates: an acquired copy of a vertical interface
            // must render, capture and record vertical too.
            createReq.setFormat(ifaceNode.get("_snapshot_format") != null
                    ? ifaceNode.get("_snapshot_format").toString() : null);
            createReq.setIsPublic(false);
            if (hasText(organizationId)) {
                createReq.setOrganizationId(organizationId);
            }
            Object dataRaw = ifaceNode.get("_snapshot_data");
            if (dataRaw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) dataRaw;
                // C3-Hole#3 fix: scan interface data for FileRefs and re-copy them
                // to the acquirer's tenant namespace. Without this, FileRefs stored
                // under `_publications/{pubId}/...` paths leak into the acquired
                // interface row and 401 at render time (acquirer can't read cross-tenant).
                int copied = scanAndCloneFileRefs(data, tenantId, "iface-" + (oldId != null ? oldId : "new"),
                        "publication-clone", fileNamespaceId, organizationId);
                if (copied > 0) {
                    logger.info("[SnapshotClone/iface] re-copied {} FileRef(s) for interface {}", copied, oldId);
                }
                createReq.setData(data);
            }
            // FORM-typed bindings (snapshotted at publish, propagated here so
            // the cloned interface preserves its target-table relationship).
            // targetTable is the table NAME (stable across clone); dataSourceId is
            // the publisher's numeric DataSource PK and MUST be remapped to the
            // cloned datasource - InterfaceRenderService binds the form by this id,
            // so a stale id fails the acquirer's tenant-scoped lookup at render time.
            Object targetTable = ifaceNode.get("_snapshot_targetTable");
            if (targetTable instanceof String s) createReq.setTargetTable(s);
            Object dsId = ifaceNode.get("_snapshot_dataSourceId");
            if (dsId instanceof Number n) {
                long oldDsId = n.longValue();
                String mapped = dsMapping == null ? null : dsMapping.get(String.valueOf(oldDsId));
                if (mapped != null) {
                    try {
                        createReq.setDataSourceId(Long.parseLong(mapped));
                    } catch (NumberFormatException e) {
                        createReq.setDataSourceId(oldDsId);
                    }
                } else {
                    // Fail-soft: datasource not part of the publication snapshot -
                    // keep the old id (the form binding was already unresolvable).
                    createReq.setDataSourceId(oldDsId);
                }
            }

            InterfaceDto saved = interfaceClient.createInterface(createReq, tenantId);
            if (saved == null) {
                logger.warn("Failed to clone interface {} for tenant {}", oldId, tenantId);
                continue;
            }
            String newId = saved.getId().toString();

            if (oldId != null) {
                interfaceMapping.put(oldId, newId);
            }

            ifaceNode.put("id", newId);
            logger.info("Cloned interface {} -> {} for tenant {}", oldId, newId, tenantId);

            ifaceNode.remove("_snapshot_htmlTemplate");
            ifaceNode.remove("_snapshot_cssTemplate");
            ifaceNode.remove("_snapshot_jsTemplate");
            ifaceNode.remove("_snapshot_templateVariables");
            ifaceNode.remove("_snapshot_name");
            ifaceNode.remove("_snapshot_description");
            ifaceNode.remove("_snapshot_interfaceType");
            ifaceNode.remove("_snapshot_data");
            ifaceNode.remove("_snapshot_format");
            ifaceNode.remove("_snapshot_formFields");
            ifaceNode.remove("_snapshot_targetTable");
            ifaceNode.remove("_snapshot_dataSourceId");
        }
        return interfaceMapping;
    }

    // ========================================================================
    // DataSource cloning
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, String> cloneDatasourcesForTenant(Map<String, Object> plan,
                                                           String tenantId,
                                                           UUID sourcePublicationId,
                                                           UUID fileNamespaceId,
                                                           String organizationId) {
        Object tablesRaw = plan.get("tables");
        if (!(tablesRaw instanceof List)) return Map.of();

        List<Map<String, Object>> tables = new ArrayList<>((List<Map<String, Object>>) tablesRaw);
        plan.put("tables", tables);

        Map<String, Long> clonedDsMap = new HashMap<>();

        for (Map<String, Object> tableNode : tables) {
            Object snapshotName = tableNode.get("_snapshot_ds_name");
            if (snapshotName == null) continue;

            String oldDsId = tableNode.get("dataSourceId") != null ? tableNode.get("dataSourceId").toString() : null;

            if (oldDsId != null && clonedDsMap.containsKey(oldDsId)) {
                tableNode.put("dataSourceId", clonedDsMap.get(oldDsId).toString());
                removeSnapshotDsFields(tableNode);
                continue;
            }

            String sourceType = tableNode.get("_snapshot_ds_sourceType") != null
                    ? tableNode.get("_snapshot_ds_sourceType").toString() : "INLINE";
            Map<String, Object> sourceConfig = tableNode.get("_snapshot_ds_sourceConfig") instanceof Map<?, ?> scm
                    ? new java.util.LinkedHashMap<>((Map<String, Object>) scm) : new java.util.LinkedHashMap<>();
            List<Map<String, Object>> items = tableNode.get("_snapshot_ds_items") instanceof List<?> itemsRaw
                    ? new ArrayList<>((List<Map<String, Object>>) itemsRaw) : new ArrayList<>();
            Map<String, ColumnMappingSpecDto> mappingSpec = objectMapper.convertValue(
                    tableNode.get("_snapshot_ds_mappingSpec"),
                    new TypeReference<Map<String, ColumnMappingSpecDto>>() {});
            if (mappingSpec == null) mappingSpec = Map.of();

            // Re-upload S3-backed FILE/IMAGE column files + sourceConfig.file_path
            // under the acquirer's tenant. Mutates sourceConfig + items in place.
            if (fileCloneService != null) {
                fileCloneService.rewriteFilePaths(sourceType, sourceConfig, items, mappingSpec,
                        tenantId, fileNamespaceId.toString(), organizationId);
            }
            // Write back the mutated items so injectSnapshotItems uses the rewritten paths.
            tableNode.put("_snapshot_ds_items", items);

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("name", snapshotName.toString());
            if (tableNode.get("_snapshot_ds_description") != null) {
                snapshot.put("description", tableNode.get("_snapshot_ds_description").toString());
            }
            snapshot.put("sourceType", sourceType);
            snapshot.put("sourceConfig", sourceConfig);
            snapshot.put("columnOrder", tableNode.get("_snapshot_ds_columnOrder") instanceof List
                    ? tableNode.get("_snapshot_ds_columnOrder") : List.of());
            snapshot.put("mappingSpec", tableNode.get("_snapshot_ds_mappingSpec"));
            // NULLABLE source tag: omitted for a decoupled duplicate (sourcePublicationId == null).
            if (sourcePublicationId != null) {
                snapshot.put("sourcePublicationId", sourcePublicationId.toString());
            }
            if (hasText(organizationId)) {
                snapshot.put("organizationId", organizationId);
            }

            DataSourceDto saved = dataSourceClient.createFromSnapshot(snapshot, tenantId);
            if (saved == null) {
                logger.error("Failed to clone datasource from snapshot for tenant {}", tenantId);
                removeSnapshotDsFields(tableNode);
                continue;
            }

            String newDsId = saved.id().toString();

            injectSnapshotItems(tableNode, saved.id(), tenantId);

            if (oldDsId != null) {
                clonedDsMap.put(oldDsId, saved.id());
            }

            tableNode.put("dataSourceId", newDsId);
            logger.info("Cloned datasource {} -> {} for tenant {}", oldDsId, newDsId, tenantId);

            removeSnapshotDsFields(tableNode);
        }

        Map<String, String> dsMapping = new HashMap<>();
        for (var entry : clonedDsMap.entrySet()) {
            dsMapping.put(entry.getKey(), entry.getValue().toString());
        }
        return dsMapping;
    }

    private void removeSnapshotDsFields(Map<String, Object> tableNode) {
        tableNode.remove("_snapshot_ds_name");
        tableNode.remove("_snapshot_ds_description");
        tableNode.remove("_snapshot_ds_sourceType");
        tableNode.remove("_snapshot_ds_sourceConfig");
        tableNode.remove("_snapshot_ds_columnOrder");
        tableNode.remove("_snapshot_ds_mappingSpec");
        tableNode.remove("_snapshot_ds_items");
    }

    @SuppressWarnings("unchecked")
    private void injectSnapshotItems(Map<String, Object> tableNode, Long newDsId, String tenantId) {
        Object itemsRaw = tableNode.get("_snapshot_ds_items");
        if (!(itemsRaw instanceof List)) return;

        List<Map<String, Object>> itemSnapshots = (List<Map<String, Object>>) itemsRaw;
        if (itemSnapshots.isEmpty()) return;

        int count = dataSourceClient.bulkInsertItems(newDsId, itemSnapshots, tenantId);
        if (count > 0) {
            logger.info("Injected {} items into cloned datasource {}", count, newDsId);
            breakdownService.increment(tenantId, "DATA", count * 200L, count);
        }
    }

    // ========================================================================
    // Agent cloning
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void cloneAgentsForTenant(Map<String, Object> plan, String tenantId, UUID fileNamespaceId,
                                       String acquiredWorkflowId,
                                       Map<String, String> interfaceMapping,
                                       Map<String, String> dsMapping,
                                       String organizationId) {
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List)) return;

        List<Map<String, Object>> agents = new ArrayList<>((List<Map<String, Object>>) agentsRaw);
        plan.put("agents", agents);

        Map<String, String> agentMapping = new HashMap<>();

        for (Map<String, Object> agentNode : agents) {
            Object snapshotName = agentNode.get("_snapshot_agent_name");
            if (snapshotName == null) continue;

            String oldConfigId = agentNode.get("agentConfigId") != null ? agentNode.get("agentConfigId").toString() : null;

            Map<String, Object> cloneRequest = new HashMap<>();
            cloneRequest.put("tenantId", tenantId);
            // File namespace for the agent's embedded file refs (avatar, knowledge, skills).
            // Always non-null; equals the source publication id on the real acquire path.
            cloneRequest.put("publicationId", fileNamespaceId.toString());
            if (hasText(organizationId)) {
                cloneRequest.put("organizationId", organizationId);
            }
            cloneRequest.put("name", snapshotName.toString());
            cloneRequest.put("description", agentNode.get("_snapshot_agent_description"));
            cloneRequest.put("systemPrompt", agentNode.get("_snapshot_agent_systemPrompt"));
            cloneRequest.put("modelProvider", agentNode.get("_snapshot_agent_modelProvider"));
            cloneRequest.put("modelName", agentNode.get("_snapshot_agent_modelName"));
            cloneRequest.put("temperature", agentNode.get("_snapshot_agent_temperature"));
            cloneRequest.put("maxTokens", agentNode.get("_snapshot_agent_maxTokens"));
            cloneRequest.put("maxIterations", agentNode.get("_snapshot_agent_maxIterations"));
            cloneRequest.put("executionTimeout", agentNode.get("_snapshot_agent_executionTimeout"));
            cloneRequest.put("config", agentNode.get("_snapshot_agent_config"));
            // H1: forward the publisher's credit budget cap so the acquired agent is NOT uncapped.
            // The publish side captures these precisely (see enrichPlanWithAgentData) and the
            // agent-service clone endpoint honours request.creditBudget / request.budgetResetMode.
            cloneRequest.put("creditBudget", agentNode.get("_snapshot_agent_creditBudget"));
            cloneRequest.put("budgetResetMode", agentNode.get("_snapshot_agent_budgetResetMode"));
            // M1: per-agent loop-guard + COLD-summariser (compaction) model overrides - without
            // these the acquired agent reverts to platform defaults and changes its stop behaviour.
            cloneRequest.put("maxPerResourcePerTurn", agentNode.get("_snapshot_agent_maxPerResourcePerTurn"));
            cloneRequest.put("loopIdenticalStop", agentNode.get("_snapshot_agent_loopIdenticalStop"));
            cloneRequest.put("loopConsecutiveStop", agentNode.get("_snapshot_agent_loopConsecutiveStop"));
            cloneRequest.put("compactionModelProvider", agentNode.get("_snapshot_agent_compactionModelProvider"));
            cloneRequest.put("compactionModelName", agentNode.get("_snapshot_agent_compactionModelName"));
            // M3: per-agent reasoning-effort override.
            cloneRequest.put("reasoningEffort", agentNode.get("_snapshot_agent_reasoningEffort"));

            // Avatar: copy an uploaded/AI file into the ACQUIRER's storage so the clone
            // survives the publisher deleting theirs (presets/http pass through).
            String avatarUrl = agentNode.get("_snapshot_agent_avatarUrl") != null
                    ? agentNode.get("_snapshot_agent_avatarUrl").toString() : null;
            cloneRequest.put("avatarUrl", avatarFileCloneService != null
                    ? avatarFileCloneService.cloneForTenant(avatarUrl, tenantId, organizationId)
                    : com.apimarketplace.publication.utils.AvatarUrlPolicy.publishable(avatarUrl));

            Object dsIdRaw = agentNode.get("_snapshot_agent_dataSourceId");
            if (dsIdRaw instanceof Number n && n.longValue() > 0) {
                String oldDsId = String.valueOf(n.longValue());
                String newDsId = dsMapping.get(oldDsId);
                if (newDsId != null) {
                    cloneRequest.put("dataSourceId", Long.parseLong(newDsId));
                }
            }

            Object toolsConfigRaw = agentNode.get("_snapshot_agent_toolsConfig");
            if (toolsConfigRaw instanceof Map) {
                cloneRequest.put("toolsConfig", toolsConfigRaw);
            }

            Object skillsRaw = agentNode.get("_snapshot_agent_skills");
            if (skillsRaw instanceof List) {
                cloneRequest.put("skills", skillsRaw);
            }

            // C3-Hole#2 fix: scan agent config / toolsConfig / skills for FileRefs
            // and re-copy them to the acquirer's tenant namespace. Without this,
            // FileRefs (avatar images, embedded knowledge file refs, skill
            // payloads) stored under `_publications/{pubId}/...` paths persist
            // into the acquired agent's rows and 401 at runtime.
            String agentScope = "agent-" + (oldConfigId != null ? oldConfigId : "new");
            int agentCopied = 0;
            agentCopied += scanAndCloneFileRefs(cloneRequest.get("config"), tenantId, agentScope,
                    "publication-clone", fileNamespaceId, organizationId);
            agentCopied += scanAndCloneFileRefs(cloneRequest.get("toolsConfig"), tenantId, agentScope,
                    "publication-clone", fileNamespaceId, organizationId);
            agentCopied += scanAndCloneFileRefs(cloneRequest.get("skills"), tenantId, agentScope,
                    "publication-clone", fileNamespaceId, organizationId);
            if (agentCopied > 0) {
                logger.info("[SnapshotClone/agent] re-copied {} FileRef(s) for agent {}", agentCopied, oldConfigId);
            }

            Map<String, Object> result = agentClient.cloneFromSnapshot(cloneRequest);
            if (result == null) {
                logger.warn("Failed to clone agent {} for tenant {}", oldConfigId, tenantId);
                removeSnapshotAgentFields(agentNode);
                continue;
            }

            String newConfigId = (String) result.get("agentId");

            if (oldConfigId != null && newConfigId != null) {
                agentMapping.put(oldConfigId, newConfigId);
            }

            agentNode.put("agentConfigId", newConfigId);
            logger.info("Cloned agent {} -> {} for tenant {}", oldConfigId, newConfigId, tenantId);

            // M2: recreate the webhook + schedule the publisher's agent had, so a workflow-embedded
            // scheduled/triggered agent keeps its automation on acquire - consistent with the
            // standalone agent-publication path. New webhook token auto-generated by agent-service;
            // failures are best-effort and never abort the clone.
            recreateAgentTriggers(agentNode, newConfigId, tenantId);

            removeSnapshotAgentFields(agentNode);
        }

        // Second pass: remap resource IDs in each cloned agent's toolsConfig
        for (Map<String, Object> agentNode : agents) {
            String newConfigId = agentNode.get("agentConfigId") != null ? agentNode.get("agentConfigId").toString() : null;
            if (newConfigId == null) continue;

            try {
                UUID agentId = UUID.fromString(newConfigId);

                Map<String, Object> mappings = new HashMap<>();
                mappings.put("tables", dsMapping);
                mappings.put("interfaces", interfaceMapping);
                mappings.put("agents", agentMapping);
                mappings.put("workflowId", acquiredWorkflowId);

                agentClient.remapToolsConfig(agentId, mappings);
            } catch (Exception e) {
                logger.warn("Failed to remap toolsConfig for agent {}: {}", newConfigId, e.getMessage());
            }
        }

        // Third pass: remap agent UUID references embedded in core:task node
        // configs (task.agentId / reviewerAgentId) to the cloned agent ids.
        // These literal agent-entity UUIDs live in cores[], not agents[], so the
        // agentConfigId rewrite above does not cover them.
        remapAgentReferencesInCores(plan, agentMapping);
    }

    private void removeSnapshotAgentFields(Map<String, Object> agentNode) {
        agentNode.remove("_snapshot_agent_name");
        agentNode.remove("_snapshot_agent_description");
        agentNode.remove("_snapshot_agent_systemPrompt");
        agentNode.remove("_snapshot_agent_modelProvider");
        agentNode.remove("_snapshot_agent_modelName");
        agentNode.remove("_snapshot_agent_temperature");
        agentNode.remove("_snapshot_agent_maxTokens");
        agentNode.remove("_snapshot_agent_maxIterations");
        agentNode.remove("_snapshot_agent_executionTimeout");
        agentNode.remove("_snapshot_agent_avatarUrl");
        agentNode.remove("_snapshot_agent_config");
        agentNode.remove("_snapshot_agent_toolsConfig");
        agentNode.remove("_snapshot_agent_skills");
        agentNode.remove("_snapshot_agent_dataSourceId");
    }

    /**
     * Recreate the webhook + schedule the publisher's agent had on the freshly-cloned agent, so a
     * workflow-embedded scheduled/triggered agent keeps its automation on acquire - mirroring the
     * standalone agent-publication path (AgentPublicationService). agent-service auto-generates a
     * fresh webhook token. Best-effort: a trigger-service failure is logged, never fatal to the clone.
     */
    @SuppressWarnings("unchecked")
    private void recreateAgentTriggers(Map<String, Object> agentNode, String newConfigId, String tenantId) {
        if (newConfigId == null) return;
        UUID newAgentId;
        try {
            newAgentId = UUID.fromString(newConfigId);
        } catch (IllegalArgumentException e) {
            return;
        }
        Object webhookRaw = agentNode.get("_snapshot_agent_webhookConfig");
        if (webhookRaw instanceof Map<?, ?> wcRaw) {
            Map<String, Object> wc = (Map<String, Object>) wcRaw;
            Map<String, Object> webhookReq = new HashMap<>();
            webhookReq.put("httpMethod", wc.getOrDefault("httpMethod", "POST"));
            webhookReq.put("memoryEnabled", wc.getOrDefault("memoryEnabled", false));
            try {
                agentClient.createOrUpdateWebhook(newAgentId, webhookReq, tenantId);
                logger.info("Recreated webhook for acquired workflow-embedded agent {}", newConfigId);
            } catch (Exception e) {
                logger.warn("Failed to recreate webhook for acquired agent {}: {}", newConfigId, e.getMessage());
            }
        }
        Object scheduleRaw = agentNode.get("_snapshot_agent_scheduleConfig");
        if (scheduleRaw instanceof Map<?, ?> scRaw) {
            Map<String, Object> sc = (Map<String, Object>) scRaw;
            if (sc.get("cronExpression") != null) {
                Map<String, Object> scheduleReq = new HashMap<>();
                scheduleReq.put("cronExpression", sc.get("cronExpression"));
                scheduleReq.put("timezone", sc.getOrDefault("timezone", "UTC"));
                scheduleReq.put("maxExecutions", sc.get("maxExecutions"));
                scheduleReq.put("schedulePrompt", sc.get("schedulePrompt"));
                scheduleReq.put("withMemory", sc.getOrDefault("withMemory", false));
                try {
                    agentClient.createOrUpdateSchedule(newAgentId, scheduleReq, tenantId);
                    logger.info("Recreated schedule for acquired workflow-embedded agent {}", newConfigId);
                } catch (Exception e) {
                    logger.warn("Failed to recreate schedule for acquired agent {}: {}", newConfigId, e.getMessage());
                }
            }
        }
    }

    // ========================================================================
    // DataInput file cloning
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void cloneDataInputFilesForTenant(Map<String, Object> plan, String tenantId, String workflowId,
                                              String organizationId) {
        copyDataInputFiles(plan, tenantId, workflowId, "publication", organizationId);
    }

    /**
     * Generic recursive walker that detects canonical FileRef Maps anywhere in
     * a snapshot value (interface `_snapshot_data`, agent config/toolsConfig/
     * skills, etc.) and re-copies each file from the publication namespace to
     * the acquirer's tenant namespace, mutating the FileRef's `path` in-place
     * so the cloned resource references a tenant-local file.
     *
     * <p><b>Allowlist:</b> only sources under {@code _publications/{publicationId}/}
     * are copied. A path outside this namespace (publisher tenant path that
     * survived the publish-time copy, foreign-publication path, raw HTTP URL,
     * …) is left untouched + logged. This closes audit C3-Hole#6 (defense in
     * depth: a corrupted snapshot can't leak another tenant's bucket key into
     * the acquirer's namespace via this clone path).
     *
     * @param node the structure to walk (Map / List / scalar)
     * @param tenantId the acquirer tenant
     * @param workflowId scope id used by the orchestrator copy endpoint
     * @param runId scope id for grouping (e.g. "publication-acquire")
     * @param fileNamespaceId the {@code _publications/{id}/} namespace that bounds the
     *        allowlist (the source publication id on the real acquire path)
     * @return number of FileRefs successfully copied + rewritten
     */
    @SuppressWarnings("unchecked")
    private int scanAndCloneFileRefs(Object node, String tenantId, String workflowId,
                                       String runId, UUID fileNamespaceId, String organizationId) {
        if (node == null) return 0;
        String allowedPrefix = "_publications/" + fileNamespaceId + "/";
        int[] count = new int[]{0};
        scanAndCloneFileRefsInternal(node, tenantId, workflowId, runId, allowedPrefix, count, organizationId);
        return count[0];
    }

    @SuppressWarnings("unchecked")
    private void scanAndCloneFileRefsInternal(Object node, String tenantId, String workflowId,
                                                String runId, String allowedPrefix, int[] count,
                                                String organizationId) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if ("file".equals(m.get("_type")) && m.get("path") instanceof String oldPath
                    && !oldPath.isBlank()) {
                if (!oldPath.startsWith(allowedPrefix)) {
                    // Out-of-namespace path: don't copy. The acquired resource
                    // keeps the original path; if it's a publisher-tenant path
                    // the acquirer will 401 at render time (defense-in-depth at
                    // the storage proxy), which is the desired failure mode.
                    logger.debug("[SnapshotClone/files] skip non-namespace FileRef path: {}", oldPath);
                    return;
                }
                String fileName = m.get("name") instanceof String s && !s.isBlank()
                        ? s : "file";
                String mimeType = m.get("mimeType") instanceof String mt
                        ? mt : "application/octet-stream";
                String stepAlias = "clone-" + Integer.toHexString(oldPath.hashCode());
                try {
                    Map<String, Object> copyRequest = new HashMap<>();
                    copyRequest.put("sourcePath", oldPath);
                    copyRequest.put("sourceTenantId", "_publications");
                    copyRequest.put("tenantId", tenantId);
                    copyRequest.put("workflowId", workflowId);
                    copyRequest.put("runId", runId);
                    copyRequest.put("stepAlias", stepAlias);
                    copyRequest.put("fileName", fileName);
                    copyRequest.put("mimeType", mimeType);
                    Map<String, Object> result = orchestratorClient.copyFile(copyRequest, organizationId);
                    if (result != null && result.get("newPath") instanceof String newPath) {
                        m.put("path", newPath);
                        // Adopt the NEW storage-row id so the opaque by-id URL resolves in the
                        // acquirer's tenant; a leftover source id would 403/404 cross-tenant.
                        if (result.get("newId") instanceof String newId) {
                            m.put("id", newId);
                        } else {
                            m.remove("id");
                        }
                        count[0]++;
                    } else {
                        logger.warn("[SnapshotClone/files] copy returned no newPath for {}", oldPath);
                    }
                } catch (Exception e) {
                    logger.warn("[SnapshotClone/files] copy failed for {}: {}", oldPath, e.getMessage());
                }
                // Don't recurse below the FileRef.
                return;
            }
            for (Object v : m.values()) {
                scanAndCloneFileRefsInternal(v, tenantId, workflowId, runId, allowedPrefix, count, organizationId);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                scanAndCloneFileRefsInternal(item, tenantId, workflowId, runId, allowedPrefix, count, organizationId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void copyDataInputFiles(Map<String, Object> plan, String tenantId, String workflowId,
                                    String runId, String organizationId) {
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return;

        for (Object coreRaw : cores) {
            if (!(coreRaw instanceof Map<?, ?> coreMap)) continue;

            Object dataInputRaw = coreMap.get("dataInput");
            if (!(dataInputRaw instanceof Map<?, ?> dataInputMap)) continue;

            Object itemsRaw = dataInputMap.get("items");
            if (!(itemsRaw instanceof List<?> items)) continue;

            for (Object itemRaw : items) {
                if (!(itemRaw instanceof Map<?, ?> itemMap)) continue;
                if (!"file".equals(itemMap.get("type"))) continue;

                Object fileRaw = itemMap.get("file");
                if (!(fileRaw instanceof Map<?, ?>)) continue;

                Map<String, Object> fileMap = (Map<String, Object>) fileRaw;
                String sourcePath = fileMap.get("path") != null ? fileMap.get("path").toString() : null;
                if (sourcePath == null || sourcePath.isBlank()) continue;

                String fileName = fileMap.get("name") != null ? fileMap.get("name").toString() : "file";
                String mimeType = fileMap.get("mimeType") != null ? fileMap.get("mimeType").toString() : "application/octet-stream";
                String stepAlias = coreMap.get("id") != null ? coreMap.get("id").toString() : "data_input";

                try {
                    Map<String, Object> copyRequest = new HashMap<>();
                    copyRequest.put("sourcePath", sourcePath);
                    copyRequest.put("sourceTenantId", sourceTenantIdForPath(sourcePath));
                    copyRequest.put("tenantId", tenantId);
                    copyRequest.put("workflowId", workflowId);
                    copyRequest.put("runId", runId);
                    copyRequest.put("stepAlias", stepAlias);
                    copyRequest.put("fileName", fileName);
                    copyRequest.put("mimeType", mimeType);

                    Map<String, Object> copyResult = orchestratorClient.copyFile(copyRequest, organizationId);
                    if (copyResult != null && copyResult.get("newPath") != null) {
                        fileMap.put("path", copyResult.get("newPath").toString());
                        // Adopt the NEW storage-row id so the opaque by-id URL resolves in the
                        // acquirer's tenant; a leftover source id would 403/404 cross-tenant.
                        if (copyResult.get("newId") instanceof String newId) {
                            fileMap.put("id", newId);
                        } else {
                            fileMap.remove("id");
                        }
                        logger.info("Copied DataInput file {} -> {}", sourcePath, copyResult.get("newPath"));
                    } else {
                        logger.warn("Failed to copy DataInput file {}: no newPath in response", sourcePath);
                    }
                } catch (Exception e) {
                    logger.error("Failed to copy DataInput file {}: {}", sourcePath, e.getMessage());
                    throw new RuntimeException("DataInput file copy failed: " + sourcePath, e);
                }
            }
        }
    }

    private static String sourceTenantIdForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.contains("://")) {
            return null;
        }
        int idx = path.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return path.substring(0, idx);
    }

    // ========================================================================
    // Security: strip sensitive credentials
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void stripSensitiveCredentials(Map<String, Object> plan) {
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) continue;
                Object httpReq = coreMap.get("httpRequest");
                if (httpReq instanceof Map<?, ?> httpMap) {
                    ((Map<String, Object>) httpMap).remove("authConfig");
                }
                Object sendEmail = coreMap.get("sendEmail");
                if (sendEmail instanceof Map<?, ?> emailMap) {
                    ((Map<String, Object>) emailMap).remove("credentialId");
                    // Inline SMTP password RAW fallback - strip so the publisher's secret
                    // does not survive the acquire-time clone into the acquirer's plan.
                    ((Map<String, Object>) emailMap).remove("smtpPassword");
                }
                Object emailInbox = coreMap.get("emailInbox");
                if (emailInbox instanceof Map<?, ?> inboxMap) {
                    ((Map<String, Object>) inboxMap).remove("credentialId");
                }
                Object cryptoJwt = coreMap.get("cryptoJwt");
                if (cryptoJwt instanceof Map<?, ?> cryptoMap) {
                    ((Map<String, Object>) cryptoMap).remove("key");
                    ((Map<String, Object>) cryptoMap).remove("secret");
                    ((Map<String, Object>) cryptoMap).remove("token");
                }
                // SSH / SFTP / Database carry an inline password / privateKey RAW fallback
                // (alongside the credentialId reference). Without stripping these, the
                // publisher's raw secret survives the acquire-time clone into the acquirer's
                // plan (and any share read of it). Remove the raw fallbacks; the credentialId
                // reference resolves against the acquirer's own credentials at execution time.
                Object ssh = coreMap.get("ssh");
                if (ssh instanceof Map<?, ?> sshMap) {
                    ((Map<String, Object>) sshMap).remove("password");
                    ((Map<String, Object>) sshMap).remove("privateKey");
                }
                Object sftp = coreMap.get("sftp");
                if (sftp instanceof Map<?, ?> sftpMap) {
                    ((Map<String, Object>) sftpMap).remove("password");
                    ((Map<String, Object>) sftpMap).remove("privateKey");
                }
                Object database = coreMap.get("database");
                if (database instanceof Map<?, ?> databaseMap) {
                    ((Map<String, Object>) databaseMap).remove("password");
                }
            }
        }

        for (String stepBucket : List.of("mcps", "agents")) {
            Object bucket = plan.get(stepBucket);
            if (!(bucket instanceof List<?> steps)) continue;
            for (Object step : steps) {
                if (!(step instanceof Map<?, ?> stepMap)) continue;
                Map<String, Object> mutableStep = (Map<String, Object>) stepMap;
                mutableStep.remove("selectedCredentialId");
                mutableStep.remove("credentialId");
                mutableStep.remove("platformCredentialId");
                mutableStep.remove("credentialSource");
            }
        }
    }

    // ========================================================================
    // Sub-workflow cloning
    // ========================================================================

    private Map<String, String> cloneSubWorkflowsForTenant(Map<String, Object> plan,
                                                            String tenantId,
                                                            UUID sourcePublicationId,
                                                            UUID fileNamespaceId,
                                                            String organizationId) {
        return cloneSubWorkflowsForTenant(plan, tenantId, sourcePublicationId, fileNamespaceId, organizationId, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> cloneSubWorkflowsForTenant(Map<String, Object> plan,
                                                            String tenantId,
                                                            UUID sourcePublicationId,
                                                            UUID fileNamespaceId,
                                                            String organizationId,
                                                            Map<String, String> clonedWorkflowIds) {
        Object subWfsRaw = plan.get("_snapshot_subworkflows");
        if (!(subWfsRaw instanceof Map<?, ?> subWfsMap)) return Map.of();

        Map<String, String> workflowMapping = new HashMap<>();

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) subWfsMap).entrySet()) {
            String oldWorkflowId = entry.getKey().toString();

            // Diamond (a child shared by two parents) or a cycle back-reference: the child was
            // already cloned - or is in progress - in another branch. Reuse its clone id so THIS
            // parent's sub_workflow node remaps to the same clone, instead of being skipped and
            // left dangling at the publisher-tenant source id (which the acquirer cannot resolve).
            String existingClone = clonedWorkflowIds.get(oldWorkflowId);
            if (existingClone != null) {
                workflowMapping.put(oldWorkflowId, existingClone);
                continue;
            }

            if (!(entry.getValue() instanceof Map<?, ?>)) continue;
            Map<String, Object> snapshot = (Map<String, Object>) entry.getValue();

            Object planObj = snapshot.get("plan");
            if (planObj == null) {
                logger.warn("Sub-workflow snapshot missing plan for {}, skipping", oldWorkflowId);
                continue;
            }
            Map<String, Object> subPlan = objectMapper.convertValue(planObj,
                    new TypeReference<Map<String, Object>>() {});

            String tempId = UUID.randomUUID().toString();
            // Reserve the clone id BEFORE recursing so a cycle (and every sibling parent) resolves
            // to this same id rather than infinitely re-cloning or dangling. createReq below
            // provides tempId as the workflow id and the orchestrator honours it.
            clonedWorkflowIds.put(oldWorkflowId, tempId);

            Map<String, String> nestedWfMapping = cloneSubWorkflowsForTenant(
                    subPlan, tenantId, sourcePublicationId, fileNamespaceId, organizationId, clonedWorkflowIds);
            remapTriggerWorkflowIds(subPlan, nestedWfMapping);
            remapAgentSnapshotWorkflows(subPlan, nestedWfMapping);

            Map<String, String> subDsMapping = cloneDatasourcesForTenant(subPlan, tenantId, sourcePublicationId, fileNamespaceId, organizationId);
            remapDatasourceTriggerIds(subPlan, subDsMapping);
            Map<String, String> subIfaceMapping = cloneInterfacesForTenant(subPlan, tenantId, fileNamespaceId, organizationId, subDsMapping);
            remapInterfaceIdRefs(subPlan, subIfaceMapping);
            cloneAgentsForTenant(subPlan, tenantId, fileNamespaceId, tempId, subIfaceMapping, subDsMapping, organizationId);
            cloneDataInputFilesForTenant(subPlan, tenantId, tempId, organizationId);
            stripSensitiveCredentials(subPlan);
            // F4 PUB-HIJACK fix: same as the top-level cloneFromSnapshot strip -
            // each sub-workflow plan is also a clone and must shed its parent's
            // standalone trigger row back-references.
            com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(subPlan);

            Map<String, Object> createReq = new HashMap<>();
            createReq.put("id", tempId);
            createReq.put("title", snapshot.getOrDefault("name", "Sub-workflow"));
            createReq.put("description", snapshot.getOrDefault("description", ""));
            createReq.put("plan", subPlan);
            createReq.put("basePlan", objectMapper.convertValue(subPlan, new TypeReference<Map<String, Object>>() {}));
            // NULLABLE source tag: omitted for a decoupled duplicate's sub-workflows.
            if (sourcePublicationId != null) {
                createReq.put("sourcePublicationId", sourcePublicationId.toString());
            }
            // Children are standard WORKFLOW rows: a second APPLICATION stamped
            // with the same source_publication_id would violate the V268 unique
            // index (duplicate-key 500 on the root insert) and break the
            // one-root-per-publication lookup.
            createReq.put("workflowType", CLONE_TYPE_WORKFLOW);
            if (hasText(organizationId)) {
                createReq.put("organizationId", organizationId);
            }

            Map<String, Object> result = orchestratorClient.createApplicationWorkflow(createReq, tenantId);
            if (result != null && result.get("id") != null) {
                String newId = result.get("id").toString();
                workflowMapping.put(oldWorkflowId, newId);
                // Finalize the reservation with the created id (== tempId, the id we provided) so
                // sibling parents and cycle back-references all remap to the same clone.
                clonedWorkflowIds.put(oldWorkflowId, newId);
            } else {
                logger.warn("Failed to create APPLICATION workflow for sub-workflow {}", oldWorkflowId);
                clonedWorkflowIds.remove(oldWorkflowId); // undo reservation so it isn't reused as a phantom
            }
        }

        remapSubWorkflowReferences(plan, workflowMapping);
        plan.remove("_snapshot_subworkflows");

        return workflowMapping;
    }

    @SuppressWarnings("unchecked")
    private void remapSubWorkflowReferences(Map<String, Object> plan, Map<String, String> mapping) {
        if (mapping.isEmpty()) return;
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return;
        for (Object core : cores) {
            if (!(core instanceof Map<?, ?> coreMap)) continue;
            if (!"sub_workflow".equals(coreMap.get("type"))) continue;
            Object subWf = coreMap.get("subWorkflow");
            if (!(subWf instanceof Map<?, ?> subMap)) continue;
            String oldId = subMap.get("workflowId") != null ? subMap.get("workflowId").toString() : null;
            if (oldId != null && mapping.containsKey(oldId)) {
                ((Map<String, Object>) subMap).put("workflowId", mapping.get(oldId));
            }
        }
    }

    // ========================================================================
    // Remapping helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void remapSelfReferences(Map<String, Object> plan, String actualWorkflowId) {
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) continue;
                if (!"sub_workflow".equals(coreMap.get("type"))) continue;
                Object subWf = coreMap.get("subWorkflow");
                if (!(subWf instanceof Map<?, ?> subMap)) continue;
                if ("__self__".equals(subMap.get("workflowId"))) {
                    ((Map<String, Object>) subMap).put("workflowId", actualWorkflowId);
                }
            }
        }
        // agent _snapshot_agent_toolsConfig.workflows "__self__" → actual id (mirror of the
        // publish-side markSelfRefNodes; runs before remapAgentSnapshotWorkflows so the sentinel
        // is resolved and not left for the sub-workflow mapping pass to miss).
        Object agentsRaw = plan.get("agents");
        if (agentsRaw instanceof List<?> agents) {
            for (Object agent : agents) {
                if (!(agent instanceof Map<?, ?> agentMap)) continue;
                Object tcRaw = agentMap.get("_snapshot_agent_toolsConfig");
                if (!(tcRaw instanceof Map<?, ?> tc)) continue;
                Object wfListRaw = tc.get("workflows");
                if (!(wfListRaw instanceof List<?> wfList)) continue;
                List<Object> remapped = new ArrayList<>(wfList.size());
                boolean changed = false;
                for (Object wf : wfList) {
                    if ("__self__".equals(wf)) { remapped.add(actualWorkflowId); changed = true; }
                    else remapped.add(wf);
                }
                if (changed) ((Map<String, Object>) tc).put("workflows", remapped);
            }
        }
        // workflow/error trigger id "__self__" → actual id
        Object triggersRaw = plan.get("triggers");
        if (triggersRaw instanceof List<?> triggers) {
            for (Object t : triggers) {
                if (!(t instanceof Map<?, ?> triggerMap)) continue;
                String type = triggerMap.get("type") != null ? triggerMap.get("type").toString().toLowerCase() : "";
                if (("workflow".equals(type) || "error".equals(type))
                        && "__self__".equals(triggerMap.get("id"))) {
                    ((Map<String, Object>) triggerMap).put("id", actualWorkflowId);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void remapTriggerWorkflowIds(Map<String, Object> plan, Map<String, String> mapping) {
        if (mapping.isEmpty()) return;
        Object triggersRaw = plan.get("triggers");
        if (!(triggersRaw instanceof List<?> triggers)) return;
        for (Object t : triggers) {
            if (!(t instanceof Map<?, ?> triggerMap)) continue;
            String type = triggerMap.get("type") != null ? triggerMap.get("type").toString().toLowerCase() : "";
            if ("workflow".equals(type) || "error".equals(type)) {
                String oldId = triggerMap.get("id") != null ? triggerMap.get("id").toString() : null;
                if (oldId != null && mapping.containsKey(oldId)) {
                    ((Map<String, Object>) triggerMap).put("id", mapping.get(oldId));
                }
            }
        }
    }

    /**
     * Remap datasource trigger ids to the cloned datasource ids. For
     * {@code triggers[type="datasource"]} the {@code id} field is the numeric
     * primary key of the source DataSource (see DataSourceTriggerResolver
     * which does {@code Integer.valueOf(trigger.id())}). Without this remap,
     * an acquired application's datasource trigger points at the SOURCE
     * tenant's datasource and goes silently inert (tenant-scoped 404 on fire,
     * no cross-tenant leak - just no execution).
     *
     * <p>Mirrors the existing remap of {@code tableNode.dataSourceId} (in
     * {@link #cloneDatasourcesForTenant}) and agent {@code dataSourceId} (in
     * {@link #cloneAgentsForTenant}). Pattern: any trigger type whose
     * {@code id} field is a back-reference to a cloned entity must remap that
     * id in lockstep with the entity clone - see {@link #remapTriggerWorkflowIds}
     * for the workflow/error counterpart.
     *
     * <p>Fail-soft: an unmapped id (datasource not included in the publication
     * snapshot) is kept as-is and a warn is logged so the orphan is detectable;
     * the trigger then fails the existing tenant-scoped lookup at fire time.
     */
    @SuppressWarnings("unchecked")
    private void remapDatasourceTriggerIds(Map<String, Object> plan, Map<String, String> dsMapping) {
        if (dsMapping == null || dsMapping.isEmpty()) return;
        Object triggersRaw = plan.get("triggers");
        if (!(triggersRaw instanceof List<?> triggers)) return;
        for (Object t : triggers) {
            if (!(t instanceof Map<?, ?> triggerMap)) continue;
            Object typeObj = triggerMap.get("type");
            if (typeObj == null || !"datasource".equalsIgnoreCase(typeObj.toString())) continue;
            Object oldIdObj = triggerMap.get("id");
            if (oldIdObj == null) continue;
            String oldId = oldIdObj.toString();
            String newId = dsMapping.get(oldId);
            if (newId != null) {
                ((Map<String, Object>) triggerMap).put("id", newId);
            } else {
                logger.warn("[clone] Datasource trigger id {} has no mapping (cloned dsIds: {}). " +
                        "Trigger will not fire on cloned workflow.", oldId, dsMapping.keySet());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void remapAgentSnapshotWorkflows(Map<String, Object> plan, Map<String, String> mapping) {
        if (mapping.isEmpty()) return;
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List<?> agents)) return;
        for (Object agent : agents) {
            if (!(agent instanceof Map<?, ?> agentMap)) continue;
            Object toolsConfigRaw = agentMap.get("_snapshot_agent_toolsConfig");
            if (!(toolsConfigRaw instanceof Map<?, ?> toolsConfig)) continue;
            Object wfListRaw = toolsConfig.get("workflows");
            if (!(wfListRaw instanceof List<?> wfList)) continue;

            List<String> remapped = new ArrayList<>();
            for (Object wf : wfList) {
                if (wf == null) continue;
                String wfStr = wf.toString();
                if (mapping.containsKey(wfStr)) {
                    remapped.add(mapping.get(wfStr));
                } else {
                    remapped.add(wfStr);
                }
            }
            ((Map<String, Object>) toolsConfig).put("workflows", remapped);
        }
    }

    /**
     * Remap agent UUID references embedded in {@code core:task} node configs to
     * the cloned agent ids. A Task node can assign work to a specific agent via
     * {@code task.agentId} / {@code task.reviewerAgentId} - literal agent-entity
     * UUIDs chosen in the builder. These live in {@code cores[]}, NOT in the
     * {@code agents[]} array (only the agent NODES are remapped there via
     * {@code agentConfigId}). Without this step an acquired application's Task
     * node sends the SOURCE tenant's agent id to agent-service at fire time
     * (TaskNode.executeCreate → createTaskForWorkflow), which resolves to nothing
     * in the acquirer's tenant - the application's agent "no longer exists".
     *
     * <p>Mirrors {@link #remapDatasourceTriggerIds} / {@link #remapSubWorkflowReferences}:
     * any field that back-references a cloned entity by id is remapped in lockstep
     * with the entity clone.
     *
     * <p>Fail-soft: a value that isn't a key in {@code agentMapping} - a template
     * expression ({@code {{...}}}) or an agent not part of the publication - is
     * left untouched.
     */
    @SuppressWarnings("unchecked")
    private void remapAgentReferencesInCores(Map<String, Object> plan, Map<String, String> agentMapping) {
        if (agentMapping == null || agentMapping.isEmpty()) return;
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return;
        for (Object core : cores) {
            if (!(core instanceof Map<?, ?> coreMap)) continue;
            Object taskRaw = coreMap.get("task");
            if (!(taskRaw instanceof Map<?, ?>)) continue;
            Map<String, Object> task = (Map<String, Object>) taskRaw;
            remapResourceIdField(task, "agentId", agentMapping);
            remapResourceIdField(task, "reviewerAgentId", agentMapping);
        }
    }

    /**
     * Replace {@code holder[field]} via {@code mapping} when the current value is a
     * mapped id. A null value, or a value with no mapping entry (expression /
     * unrelated id), is left untouched.
     */
    private void remapResourceIdField(Map<String, Object> holder, String field, Map<String, String> mapping) {
        Object value = holder.get(field);
        if (value == null) return;
        String newId = mapping.get(value.toString());
        if (newId != null) {
            holder.put(field, newId);
        }
    }

    /**
     * Remap interface UUID references carried by the deprecated
     * {@code triggers[].interfaceIds[]} / {@code mcps[].interfaceIds[]} arrays to
     * the cloned interface ids. These legacy back-references are still read at run
     * time (InterfacePlanExtractor strategies 3 &amp; 4 → WorkflowExecutionService
     * links them to the run); the primary {@code interfaces[].id} remap in
     * {@link #cloneInterfacesForTenant} does not cover them, so without this an
     * acquired application would link the SOURCE tenant's interfaces (cross-tenant,
     * inert).
     *
     * <p>Fail-soft: an id with no mapping entry is left untouched.
     */
    @SuppressWarnings("unchecked")
    private void remapInterfaceIdRefs(Map<String, Object> plan, Map<String, String> interfaceMapping) {
        if (interfaceMapping == null || interfaceMapping.isEmpty()) return;
        for (String bucket : List.of("triggers", "mcps")) {
            Object bucketRaw = plan.get(bucket);
            if (!(bucketRaw instanceof List<?> nodes)) continue;
            for (Object node : nodes) {
                if (!(node instanceof Map<?, ?> nodeMap)) continue;
                Object idsRaw = nodeMap.get("interfaceIds");
                if (!(idsRaw instanceof List<?> ids)) continue;
                List<Object> remapped = new ArrayList<>(ids.size());
                for (Object id : ids) {
                    if (id == null) { remapped.add(null); continue; }
                    String mapped = interfaceMapping.get(id.toString());
                    remapped.add(mapped != null ? mapped : id);
                }
                ((Map<String, Object>) nodeMap).put("interfaceIds", remapped);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
