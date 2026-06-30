package com.apimarketplace.orchestrator.tools.files;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.agent.tools.common.ToolParamUtils;
import com.apimarketplace.agent.tools.common.ToolResultSizeCap;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.services.file.DocumentTextExtractor;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.orchestrator.repository.OffsetLimitPageable;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.arrayParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * Agent-facing {@code files} tool - browse and open the workspace's stored files
 * (documents, images, exports, uploads), newest first, scoped to the active
 * organization.
 *
 * <p><b>Why this exists.</b> Before this tool, an agent could SEE a file
 * reference ({@code FileRef}) inside a node output via {@code workflow
 * (action='get_node_output')} but had no way to (a) list the files of its
 * workspace, or (b) open a file's content. Storage was internal
 * service-to-service only. This tool closes that gap as a thin, coherent
 * wrapper over the same machinery the UI Storage Explorer uses
 * ({@link StorageExplorerService}) - it does not re-implement any query.
 *
 * <p><b>Scope boundary with {@code get_run}.</b> Node/step OUTPUTS (structured
 * JSON produced by a step) are NOT browsed here - they are read, with full
 * execution context, through {@code workflow(action='get_run')} /
 * {@code get_node_output}. {@code files.list} restricts to real files
 * ({@code file_name IS NOT NULL}); step-output JSON blobs have no file name and
 * never appear. This keeps one responsibility per tool and avoids two paths to
 * the same data.
 *
 * <p><b>Coherence.</b> {@code list} emits the canonical {@link AgentListEnvelope}
 * (same offset pagination + hints as {@code workflow.list}); {@code view}
 * truncates large content with the same {@code truncated}/{@code original_length}
 * vocabulary as {@link ToolResultSizeCap} and offers an {@code offset}-based
 * expand (the same idiom the size-cap stub always pointed at but never had a
 * tool for). The agent handle is the storage row {@code file_id} (UUID) - the
 * raw S3 key is never exposed (cross-tenant footgun + useless to the agent).
 */
@Slf4j
@Component
public class FilesToolsProvider implements ToolsProvider {

    private static final String TOOL_NAME = "files";

    private static final List<String> VALID_ACTIONS =
            List.of("list", "get", "view", "visualize", "create_folder", "move_to_folder", "help");

    /** Default text/JSON window for {@code view}; also the hard ceiling (aligned with
     *  {@link ToolResultSizeCap#MAX_STRING_BYTES} so the agent sees one cap everywhere). */
    private static final int VIEW_MAX_BYTES = ToolResultSizeCap.MAX_STRING_BYTES;

    /** Head window for the cheap {@code get} preview of TEXT files. */
    private static final int GET_PREVIEW_CHARS = 500;

    /**
     * Largest image (raw bytes) inlined to the vision channel on {@code view}. Kept under
     * the Anthropic per-image ceiling: base64 inflates ~33%, so a 3.6 MB raw image encodes
     * to &lt;5 MB. Bigger images skip inlining (the user still gets the click-to-open card +
     * url). Override with the {@code files.view.image-inline-max-bytes} property.
     */
    private static final int DEFAULT_IMAGE_INLINE_MAX_BYTES = 3_600_000;

    /**
     * Largest document (raw bytes) we download + parse to extract text inline on {@code view}.
     * A document's byte size maps roughly to extracted-text size, so this guards against
     * pulling a huge file into memory on a single view; past it the agent gets the url/card and
     * can wire the file into the {@code ExtractFromFile} workflow node for chunked extraction.
     * Override with the {@code files.view.extract-max-bytes} property.
     */
    private static final int DEFAULT_EXTRACT_MAX_BYTES = 10_485_760; // 10 MB

    private final StorageExplorerService explorerService;
    private final StorageService storageService;
    private final PublicFileUrlBuilder publicFileUrlBuilder;
    private final OrgAccessGuard orgAccessGuard;
    /** Resolves WORKFLOW-folder display names for the folder-navigation listing
     *  (the storage boundary cannot query the workflows table). */
    private final WorkflowRepository workflowRepository;
    /** Nullable - used to download S3-backed bytes for the vision channel (images) and for
     *  inline text extraction (documents). */
    private final FileStorageService fileStorageService;
    private final int imageInlineMaxBytes;
    private final int extractMaxBytes;

    /** Convenience constructor without the vision/extraction media channel (fileStorageService=null) -
     *  used by call-sites/tests that do not exercise image inlining or document extraction on {@code view}. */
    public FilesToolsProvider(StorageExplorerService explorerService, StorageService storageService,
                              PublicFileUrlBuilder publicFileUrlBuilder, OrgAccessGuard orgAccessGuard,
                              WorkflowRepository workflowRepository) {
        this(explorerService, storageService, publicFileUrlBuilder, orgAccessGuard, workflowRepository,
             null, DEFAULT_IMAGE_INLINE_MAX_BYTES, DEFAULT_EXTRACT_MAX_BYTES);
    }

    /** Media-channel constructor with an explicit image cap (extraction cap defaulted) - kept for
     *  tests that exercise the image vision channel. */
    public FilesToolsProvider(StorageExplorerService explorerService, StorageService storageService,
                              PublicFileUrlBuilder publicFileUrlBuilder, OrgAccessGuard orgAccessGuard,
                              WorkflowRepository workflowRepository,
                              FileStorageService fileStorageService, int imageInlineMaxBytes) {
        this(explorerService, storageService, publicFileUrlBuilder, orgAccessGuard, workflowRepository,
             fileStorageService, imageInlineMaxBytes, DEFAULT_EXTRACT_MAX_BYTES);
    }

    @Autowired
    public FilesToolsProvider(StorageExplorerService explorerService, StorageService storageService,
                              PublicFileUrlBuilder publicFileUrlBuilder, OrgAccessGuard orgAccessGuard,
                              WorkflowRepository workflowRepository,
                              FileStorageService fileStorageService,
                              @org.springframework.beans.factory.annotation.Value(
                                  "${files.view.image-inline-max-bytes:" + DEFAULT_IMAGE_INLINE_MAX_BYTES + "}") int imageInlineMaxBytes,
                              @org.springframework.beans.factory.annotation.Value(
                                  "${files.view.extract-max-bytes:" + DEFAULT_EXTRACT_MAX_BYTES + "}") int extractMaxBytes) {
        this.explorerService = explorerService;
        this.storageService = storageService;
        this.publicFileUrlBuilder = publicFileUrlBuilder;
        this.orgAccessGuard = orgAccessGuard;
        this.workflowRepository = workflowRepository;
        this.fileStorageService = fileStorageService;
        this.imageInlineMaxBytes = imageInlineMaxBytes > 0 ? imageInlineMaxBytes : DEFAULT_IMAGE_INLINE_MAX_BYTES;
        this.extractMaxBytes = extractMaxBytes > 0 ? extractMaxBytes : DEFAULT_EXTRACT_MAX_BYTES;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildFilesTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!TOOL_NAME.equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        Map<String, Object> params = parameters != null ? parameters : Map.of();

        String action = ToolParamUtils.getStringParam(params, "action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        action = action.trim().toLowerCase();

        // help is free + read-only and needs no scope.
        if ("help".equals(action)) {
            return ToolExecutionResult.success(buildHelpPayload(params));
        }

        // Every data action is org-scoped. The gateway always injects the active
        // organization (post-V261); a blank scope means we cannot safely query.
        String tenantId = context != null ? context.tenantId() : null;
        String orgId = context != null ? context.orgId() : null;
        String orgRole = context != null ? context.orgRole() : null;
        if (orgId == null || orgId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.AUTHENTICATION_REQUIRED,
                "An active organization scope is required to access files.");
        }

        // When an agent is scoped to specific files (toolsConfig.files), only those
        // are reachable. An absent/empty allow-list means full org-scoped access.
        Set<String> allowedFiles = agentAllowedFileIds(context);

        // Per-agent read/write axis (toolsConfig.fileAccessMode): a read-only agent may
        // list/get/view/visualize files but NOT the write actions (create_folder /
        // move_to_folder). READ actions short-circuit inside checkWriteAccess; an
        // absent/'write' mode is full access (default). This stacks ON TOP of the org
        // RBAC canWrite("file") already enforced on the individual move below.
        var fileAccessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "file", action);
        if (fileAccessDenied.isPresent()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, fileAccessDenied.get());
        }

        try {
            return switch (action) {
                case "list" -> executeList(params, tenantId, orgId, orgRole, allowedFiles);
                case "get"  -> executeGet(params, tenantId, orgId, orgRole, allowedFiles);
                case "view" -> executeView(params, tenantId, orgId, orgRole, allowedFiles);
                case "visualize" -> executeVisualize(params, tenantId, orgId, orgRole, allowedFiles);
                case "create_folder" -> executeCreateFolder(params, tenantId, orgId);
                case "move_to_folder" -> executeMoveToFolder(params, tenantId, orgId, orgRole, allowedFiles);
                default -> ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
            };
        } catch (Exception e) {
            log.error("files action '{}' failed: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "files " + action + " failed: " + e.getMessage());
        }
    }

    // ==================== list ====================

    private ToolExecutionResult executeList(Map<String, Object> params, String tenantId, String orgId, String orgRole,
                                            Set<String> allowedFiles) {
        // Folder navigation (Phase 3): when 'folder' is supplied, browse the folder TREE the user sees
        // (manual folders + the virtual workflow→epoch→spawn→iteration tree). Omitting 'folder' keeps the
        // legacy flat newest-first listing below, unchanged (back-compat).
        String folder = trimToNull(ToolParamUtils.getStringParam(params, "folder"));
        if (folder != null) {
            return executeListFolder(folder, params, tenantId, orgId, orgRole, allowedFiles);
        }

        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "files", "files", "files")
                .withSuggestedFilters(List.of("query", "run_id", "workflow_id", "date_from", "date_to"))
                .withNext(Map.of(
                        "peek", "files(action='get', file_id='<file_id>')",
                        "view", "files(action='view', file_id='<file_id>')"
                ));

        String query = trimToNull(ToolParamUtils.getStringParam(params, "query"));
        String runId = trimToNull(ToolParamUtils.getStringParam(params, "run_id"));
        String workflowId = trimToNull(ToolParamUtils.getStringParam(params, "workflow_id"));
        Instant dateFrom = parseInstant(ToolParamUtils.getStringParam(params, "date_from"));
        Instant dateTo = parseInstant(ToolParamUtils.getStringParam(params, "date_to"));

        Set<String> activeFilters = new LinkedHashSet<>();
        if (query != null) activeFilters.add("query");
        if (runId != null) activeFilters.add("run_id");
        if (workflowId != null) activeFilters.add("workflow_id");
        if (dateFrom != null) activeFilters.add("date_from");
        if (dateTo != null) activeFilters.add("date_to");

        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(params, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, e.code + ": " + e.getMessage());
        }

        // Agent scoped to specific files: list ONLY those (still honouring org restrictions),
        // resolved by id and paginated in memory (the allow-list is small and known).
        if (allowedFiles != null) {
            Set<UUID> restricted = restrictedFileIds(orgId, tenantId, orgRole);
            List<Map<String, Object>> all = new ArrayList<>();
            for (String idStr : allowedFiles) {
                UUID id = parseUuid(idStr).orElse(null);
                if (id == null || restricted.contains(id)) continue;
                storageService.getEntityByIdForScope(id, tenantId, orgId)
                        .filter(e -> e.getFileName() != null)
                        .ifPresent(e -> all.add(toListItemFromEntity(e)));
            }
            int from = Math.min(bounds.offset(), all.size());
            int to = Math.min(from + bounds.limit(), all.size());
            Map<String, Object> envelope = AgentListEnvelope.paginateProjection(
                    all.subList(from, to), bounds, all.size(), spec);
            return ToolExecutionResult.success(envelope);
        }

        StorageExplorerService.FilesSlice slice = explorerService.searchFilesSlice(
                tenantId, orgId, query, null, null, workflowId, runId, dateFrom, dateTo,
                /* filesOnly */ true, restrictedFileIds(orgId, tenantId, orgRole), bounds.limit(), bounds.offset());

        List<Map<String, Object>> items = slice.files().stream().map(this::toListItem).toList();
        Map<String, Object> envelope = AgentListEnvelope.paginateProjection(items, bounds, slice.total(), spec);
        return ToolExecutionResult.success(envelope);
    }

    private Map<String, Object> toListItemFromEntity(StorageEntity e) {
        String mime = e.getMimeType() != null ? e.getMimeType() : e.getContentType();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_id", e.getId() != null ? e.getId().toString() : null);
        m.put("name", e.getFileName());
        m.put("mime_type", mime);
        m.put("size_bytes", e.getSizeBytes());
        m.put("kind", kindOf(mime));
        if (publicFileUrlBuilder != null && e.getId() != null) {
            m.put("url", publicFileUrlBuilder.fileUrl(e.getId(), true));
        }
        m.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        if (e.getRunId() != null) m.put("run_id", e.getRunId());
        if (e.getWorkflowId() != null) m.put("workflow_id", e.getWorkflowId());
        if (e.getStepKey() != null) m.put("step", e.getStepKey());
        addWorkflowContext(m, e.getWorkflowId(), e.getRunId(), e.getStepKey(), mime);
        return m;
    }

    private Map<String, Object> toListItem(StorageExplorerDto d) {
        String mime = d.mimeType() != null ? d.mimeType() : d.contentType();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_id", d.id() != null ? d.id().toString() : null);
        m.put("name", d.fileName());
        m.put("mime_type", mime);
        m.put("size_bytes", d.sizeBytes());
        m.put("size", d.formattedSize());
        m.put("kind", kindOf(mime));
        // Opaque, absolute, user-facing link (no tenant id / no s3 key).
        if (publicFileUrlBuilder != null && d.id() != null) {
            m.put("url", publicFileUrlBuilder.fileUrl(d.id(), true));
        }
        m.put("created_at", d.createdAt() != null ? d.createdAt().toString() : null);
        if (d.runId() != null) m.put("run_id", d.runId());
        if (d.workflowId() != null) m.put("workflow_id", d.workflowId());
        if (d.stepKey() != null) m.put("step", d.stepKey());
        addWorkflowContext(m, d.workflowId(), d.runId(), d.stepKey(), mime);
        return m;
    }

    // ==================== list (folder navigation) ====================

    /**
     * Browse ONE level of the Files folder tree - the same model the user sees: manual folders
     * (created with {@code create_folder}) plus the virtual {@code workflow → epoch → spawn → iteration}
     * tree computed from each file's run context. Returns the level's sub-{@code folders} and its loose
     * {@code files} (the existing flat file-item shape), with offset pagination.
     *
     * <p>{@code folder} routing: {@code "root"}/blank → the virtual root (manual root folders + one
     * folder per workflow + loose files); a {@code wf:…} token → into that virtual subtree; any other
     * value is parsed as a manual-folder UUID → that folder's children. A value that is neither a
     * virtual token nor a valid UUID is a validation error.</p>
     */
    private ToolExecutionResult executeListFolder(String folder, Map<String, Object> params, String tenantId,
                                                  String orgId, String orgRole, Set<String> allowedFiles) {
        // 'folder' is itself the scoping filter, so deep paging inside a folder is allowed (no
        // PAGINATION_LIMIT_WITHOUT_FILTER wall). Reuse the SAME bounds (default 25, max 50) as the flat list.
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                AgentListEnvelope.Caps.STANDARD, "files", "files", "files");
        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(params, spec, Set.of("folder"));
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, e.code + ": " + e.getMessage());
        }

        // Honour the same org member restriction list as the flat list. (The agent allow-list - when
        // present - scopes browsing too: drop any restricted/out-of-allow-list rows from the page.)
        Set<UUID> excludedIds = restrictedFileIds(orgId, tenantId, orgRole);

        // Arbitrary-offset Pageable: the storage services read getOffset()/getPageSize() verbatim, so this
        // maps the agent's offset/limit exactly (no page-alignment snap - see OffsetLimitPageable).
        Pageable pageable = OffsetLimitPageable.of(bounds.offset(), bounds.limit());

        boolean isRoot = "root".equalsIgnoreCase(folder);
        Page<StorageExplorerDto> page;
        // The location ONE LEVEL UP, so a stateless agent that drilled in can always step back out.
        // null = already at the root (there is nowhere up). "root" = up one level lands at the top.
        String parent;
        if (isRoot) {
            page = explorerService.searchVirtualScope(tenantId, orgId, null, null, null, null,
                    /* filesOnly */ true, /* s3Only */ true, null, excludedIds, pageable);
            parent = null;
        } else {
            VirtualFolderAddress address = VirtualFolderAddress.parse(folder);
            if (address != null) {
                page = explorerService.searchVirtualScope(tenantId, orgId, address, null, null, null,
                        /* filesOnly */ true, /* s3Only */ true, null, excludedIds, pageable);
                // Walk up the virtual address (ITERATION→SPAWN→EPOCH→WORKFLOW); above WORKFLOW is the root.
                VirtualFolderAddress up = address.parent();
                parent = up != null ? up.toToken() : "root";
            } else {
                Optional<UUID> manualId = parseUuid(folder);
                if (manualId.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                        "folder must be 'root', a folder_ref from list, or a workflow folder token (wf:...)");
                }
                page = explorerService.searchFolderScope(tenantId, orgId, manualId.get(), null, null, null,
                        null, null, null, null, /* filesOnly */ true, /* s3Only */ true, null,
                        excludedIds, pageable);
                // A manual folder's parent is its own parent_folder_id, else the root.
                parent = storageService.getEntityByIdForScope(manualId.get(), tenantId, orgId)
                        .map(e -> e.getParentFolderId() != null ? e.getParentFolderId().toString() : "root")
                        .orElse("root");
            }
        }

        return ToolExecutionResult.success(
                buildFolderListing(folder, parent, page, bounds, allowedFiles, orgId, tenantId, orgRole));
    }

    /** Assemble the folder-navigation envelope: split the page into {@code folders} + {@code files}. */
    private Map<String, Object> buildFolderListing(String folder, String parent, Page<StorageExplorerDto> page,
                                                   AgentListEnvelope.Bounds bounds, Set<String> allowedFiles,
                                                   String orgId, String tenantId, String orgRole) {
        List<StorageExplorerDto> content = page.getContent();

        // Resolve WORKFLOW-folder display names in one batched lookup (mirror StorageExplorerController).
        Map<UUID, String> workflowNames = resolveWorkflowFolderNames(content);

        List<Map<String, Object>> folders = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        for (StorageExplorerDto d : content) {
            if (d.isFolder()) {
                folders.add(folderItem(d, workflowNames));
            } else {
                // When an agent allow-list is active, only its files are browsable.
                if (allowedFiles != null && (d.id() == null || !allowedFiles.contains(d.id().toString()))) {
                    continue;
                }
                files.add(toListItem(d));
            }
        }

        int count = folders.size() + files.size();
        long total = page.getTotalElements();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("kind", "files");
        out.put("folder", folder);
        // `parent` = the location one level up (a folder_ref, or 'root'). Absent at the root. Lets a
        // stateless agent step back out instead of getting stranded deep in the tree.
        if (parent != null) {
            out.put("parent", parent);
        }
        out.put("folders", folders);
        out.put("files", files);
        out.put("count", count);
        out.put("total", total);
        out.put("offset", bounds.offset());
        out.put("limit", bounds.limit());
        // Page from the DB element count (the rows this page actually returned), NOT the post-filter
        // `count`: an active agent allow-list drops some file rows from the visible page, so `count`
        // would under-report and emit a phantom hasMore / a total the agent can never reach.
        out.put("hasMore", (long) bounds.offset() + page.getNumberOfElements() < total);

        // Hint always points the agent forward (drill in / open a file) AND, when not at the root,
        // back out - so a context-free agent that drilled deep is never stranded.
        StringBuilder hint = new StringBuilder();
        if (count == 0) {
            hint.append("This folder is empty. ");
        }
        hint.append("Enter a folder: files(action='list', folder='<folder_ref>'). ")
            .append("Open a file: files(action='view', file_id='<file_id>').");
        if (parent != null) {
            if (!"root".equalsIgnoreCase(parent)) {
                hint.append(" Go up one level: files(action='list', folder='").append(parent).append("').");
            }
            hint.append(" Back to the top: files(action='list', folder='root').");
        }
        out.put("hint", hint.toString());
        return out;
    }

    /** One folder row for the navigation listing: a navigable handle + label + kind + child count. */
    private static Map<String, Object> folderItem(StorageExplorerDto d, Map<UUID, String> workflowNames) {
        boolean virtual = d.virtualId() != null;
        Map<String, Object> m = new LinkedHashMap<>();
        // folder_ref is what the agent passes back as `folder` to drill in: a workflow token for a
        // virtual folder, the row UUID for a manual folder.
        m.put("folder_ref", virtual ? d.virtualId() : (d.id() != null ? d.id().toString() : null));
        m.put("name", folderLabel(d, workflowNames));
        m.put("child_count", d.childCount() != null ? d.childCount() : 0);
        m.put("folder_kind", virtual ? d.virtualKind().toLowerCase() : "manual");
        return m;
    }

    /**
     * Human label for a folder row. Manual folders use their own name; virtual folders are localised
     * from their kind (1-based epoch / run / item, matching the Files UI) + the resolved workflow name.
     */
    private static String folderLabel(StorageExplorerDto d, Map<UUID, String> workflowNames) {
        if (d.virtualId() == null) {
            return d.fileName(); // manual folder
        }
        return switch (d.virtualKind()) {
            case "WORKFLOW" -> {
                String name = workflowNameOf(d, workflowNames);
                yield name != null ? name : "Workflow";
            }
            case "EPOCH" -> "Epoch " + ((d.epoch() != null ? d.epoch() : 0) + 1);
            case "SPAWN" -> "Run " + ((d.spawn() != null ? d.spawn() : 0) + 1);
            case "ITERATION" -> "Item " + ((d.itemIndex() != null ? d.itemIndex() : 0) + 1);
            default -> "Folder";
        };
    }

    private static String workflowNameOf(StorageExplorerDto d, Map<UUID, String> workflowNames) {
        if (d.workflowId() == null) {
            return null;
        }
        return parseUuid(d.workflowId()).map(workflowNames::get).orElse(null);
    }

    /**
     * Batch-resolve the display name of every WORKFLOW-kind virtual folder on the page. The storage
     * boundary returns these with a null name (it cannot query the workflows table), so we look the
     * names up here - same pattern as {@code StorageExplorerController.resolveWorkflowNames}.
     */
    private Map<UUID, String> resolveWorkflowFolderNames(List<StorageExplorerDto> content) {
        Set<UUID> ids = new HashSet<>();
        for (StorageExplorerDto d : content) {
            if ("WORKFLOW".equals(d.virtualKind()) && d.workflowId() != null) {
                parseUuid(d.workflowId()).ifPresent(ids::add);
            }
        }
        if (ids.isEmpty() || workflowRepository == null) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (Object[] pair : workflowRepository.findIdNamePairs(ids)) {
            names.put((UUID) pair[0], (String) pair[1]);
        }
        return names;
    }

    // ==================== create_folder ====================

    /**
     * Create a manual folder (top level when {@code folder} is omitted/'root', else under the manual
     * folder identified by {@code folder}). Folders cannot nest under a virtual workflow folder.
     */
    private ToolExecutionResult executeCreateFolder(Map<String, Object> params, String tenantId, String orgId) {
        String name = trimToNull(ToolParamUtils.getStringParam(params, "name"));
        if (name == null) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, "name is required");
        }

        String folder = trimToNull(ToolParamUtils.getStringParam(params, "folder"));
        UUID parentId = null;
        if (folder != null && !"root".equalsIgnoreCase(folder)) {
            if (VirtualFolderAddress.parse(folder) != null) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Folders nest only under a manual folder or the root - you cannot create a folder inside a "
                    + "workflow folder (wf:...). Pass a folder id from list, or omit folder for the top level.");
            }
            Optional<UUID> parsed = parseUuid(folder);
            if (parsed.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "folder must be a folder id or 'root'");
            }
            parentId = parsed.get();
        }

        StorageEntity saved;
        try {
            saved = storageService.createFolderForScope(tenantId, orgId, name, parentId);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        String folderId = saved.getId().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("folder_id", folderId);
        out.put("name", saved.getFileName());
        out.put("parent", folder != null ? folder : "root");
        out.put("hint", "List it with files(action='list', folder='" + folderId + "'). "
                + "Move files in with files(action='move_to_folder', file_ids=[...], folder='" + folderId + "').");
        return ToolExecutionResult.success(out);
    }

    // ==================== move_to_folder ====================

    /**
     * Move files and/or manual folders into a manual folder (or out to the root). Per-id failures are
     * reported in {@code failed} (the agent reads them - it does not retry blindly); a virtual workflow
     * folder is never a valid move target.
     */
    private ToolExecutionResult executeMoveToFolder(Map<String, Object> params, String tenantId, String orgId,
                                                    String orgRole, Set<String> allowedFiles) {
        List<String> fileIds = stringList(params.get("file_ids"));
        if (fileIds.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, "file_ids is required");
        }

        String folder = trimToNull(ToolParamUtils.getStringParam(params, "folder"));
        UUID targetId = null;
        if (folder != null && !"root".equalsIgnoreCase(folder)) {
            if (VirtualFolderAddress.parse(folder) != null) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "You can only move items into a manual folder or to the root (folder='root') - not into a "
                    + "workflow folder (wf:...).");
            }
            Optional<UUID> parsed = parseUuid(folder);
            if (parsed.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "folder must be a folder id or 'root'");
            }
            targetId = parsed.get();
        }

        // Validate each id; collect per-id failures (bad id / outside the agent allow-list) BEFORE the
        // service so they merge with the service's own per-id failures into one list.
        List<UUID> valid = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String idStr : fileIds) {
            Optional<UUID> parsed = parseUuid(idStr);
            if (parsed.isEmpty()) {
                failed.add(moveFailure(idStr, "not a valid id"));
                continue;
            }
            UUID id = parsed.get();
            // Agent allow-list scope (toolsConfig.files): an out-of-list id is unreachable.
            if (allowedFiles != null && !allowedFiles.contains(id.toString())) {
                failed.add(moveFailure(id.toString(), "restricted"));
                continue;
            }
            // Org member write-restriction - mirror the UI move path (canWriteFile). A read-only /
            // DENY-restricted member must not re-parent (or even confirm the existence of) a file it
            // cannot write, so reject BEFORE the org-scoped service call.
            if (!canWriteFile(orgId, tenantId, orgRole, id)) {
                failed.add(moveFailure(id.toString(), "restricted"));
                continue;
            }
            valid.add(id);
        }

        StorageService.MoveResult result;
        try {
            result = storageService.moveEntriesForScope(orgId, valid, targetId);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        for (StorageService.MoveFailure f : result.failed()) {
            failed.add(moveFailure(f.id().toString(), f.reason()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("moved_count", result.movedCount());
        out.put("failed", failed);
        out.put("hint", "See the folder with files(action='list', folder='" + (folder != null ? folder : "root") + "').");
        return ToolExecutionResult.success(out);
    }

    private static Map<String, Object> moveFailure(String fileId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_id", fileId);
        m.put("reason", reason);
        return m;
    }

    /**
     * Org member write-permission for one file - mirror of {@code StorageExplorerController.canWriteFile}.
     * A null guard / blank org is permissive (the org scope is asserted upstream); otherwise the member's
     * DENY / read-only restrictions block the move.
     */
    private boolean canWriteFile(String orgId, String userId, String orgRole, UUID id) {
        return orgAccessGuard == null
                || orgId == null
                || orgId.isBlank()
                || orgAccessGuard.canWrite(orgId, userId, "file", id.toString(), orgRole);
    }

    /** Read a JSON array param as a list of non-blank trimmed strings (agent passes JSON-typed values). */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = o.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ==================== get (cheap peek) ====================

    private ToolExecutionResult executeGet(Map<String, Object> params, String tenantId, String orgId, String orgRole,
                                           Set<String> allowedFiles) {
        UUID fileId = ToolParamUtils.getUuidParam(params, "file_id");
        if (fileId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "file_id is required (the UUID from files(action='list')).");
        }
        if (!canAccessFile(orgId, tenantId, orgRole, fileId) || !withinAllowList(allowedFiles, fileId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        Optional<StorageEntity> opt = storageService.getEntityByIdForScope(fileId, tenantId, orgId);
        if (opt.isEmpty()) {
            // 404, never 403 - do not leak existence of files in another workspace.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        StorageEntity e = opt.get();
        if (e.getFileName() == null) {
            // Not a browsable file (e.g. a step-output JSON blob) - same boundary as list's filesOnly.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }

        Map<String, Object> out = baseMeta(e);
        String st = e.getStorageType();
        if ("JSON".equals(st)) {
            if (e.getStructureSkeleton() != null) out.put("preview_skeleton", e.getStructureSkeleton());
            out.put("viewable", "text");
        } else if ("TEXT".equals(st)) {
            out.put("preview", head(e.getDataText(), GET_PREVIEW_CHARS));
            out.put("viewable", "text");
        } else if ("S3_FILE".equals(st) || e.getS3Key() != null) {
            // Tell the agent HOW view will surface this file: a document reads back as text, an
            // image is SEEn (vision), anything else is download-only. Cheap (mime check, no download).
            String mime = e.getMimeType() != null ? e.getMimeType() : e.getContentType();
            out.put("viewable", DocumentTextExtractor.isTextExtractable(mime, e.getFileName()) ? "text"
                    : "image".equals(kindOf(mime)) ? "image" : "download");
            // Opaque absolute URL the user can open (no tenant id / no s3 key in it).
            if (publicFileUrlBuilder != null) out.put("url", publicFileUrlBuilder.fileUrl(e.getId(), true));
        } else {
            out.put("viewable", "none");
        }
        if (e.getWidth() != null) out.put("width", e.getWidth());
        if (e.getHeight() != null) out.put("height", e.getHeight());
        if (e.getDuration() != null) out.put("duration_seconds", e.getDuration());
        // A FileRef the agent can drop into a workflow node's file parameter (only files
        // backed by object storage have one; inline text/binary cannot be wired this way).
        if (e.getS3Key() != null) out.put("ref", buildFileRef(e));
        out.put("NEXT", "files(action='view', file_id='" + e.getId() + "') to read the content or get the file's url");

        return ToolExecutionResult.success(ToolResultSizeCap.capLargeStrings(out));
    }

    // ==================== view (content + offset expand) ====================

    private ToolExecutionResult executeView(Map<String, Object> params, String tenantId, String orgId, String orgRole,
                                            Set<String> allowedFiles) {
        UUID fileId = ToolParamUtils.getUuidParam(params, "file_id");
        if (fileId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "file_id is required (the UUID from files(action='list')).");
        }
        if (!canAccessFile(orgId, tenantId, orgRole, fileId) || !withinAllowList(allowedFiles, fileId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        Optional<StorageEntity> opt = storageService.getEntityByIdForScope(fileId, tenantId, orgId);
        if (opt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        StorageEntity e = opt.get();
        if (e.getFileName() == null) {
            // Not a browsable file (e.g. a step-output JSON blob) - same boundary as list's filesOnly.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }

        int offset = Math.max(0, ToolParamUtils.getIntParam(params, "offset", 0));
        int maxBytes = ToolParamUtils.getIntParam(params, "max_bytes", VIEW_MAX_BYTES);
        maxBytes = Math.max(1, Math.min(maxBytes, VIEW_MAX_BYTES));

        Map<String, Object> out = baseMeta(e);
        String mime = e.getMimeType() != null ? e.getMimeType() : e.getContentType();
        // FileRef for workflow wiring (object-storage-backed files only).
        if (e.getS3Key() != null) out.put("ref", buildFileRef(e));
        String st = e.getStorageType();

        if ("JSON".equals(st)) {
            out.putAll(sliceText(e.getId(), e.getData(), offset, maxBytes));
        } else if ("TEXT".equals(st)) {
            out.putAll(sliceText(e.getId(), e.getDataText(), offset, maxBytes));
        } else if ("S3_FILE".equals(st) || e.getS3Key() != null) {
            // Documents (PDF/Word/Excel/HTML/CSV/text) are extracted to text and inlined so the
            // agent reads the actual CONTENT, not just a link - same channel as TEXT files, so it
            // works on every provider and on both execution paths (bridge + direct-API).
            boolean inlined = tryInlineDocumentText(e, mime, tenantId, offset, maxBytes, out);
            // Opaque, absolute, permanent (workspace-scoped) URL is ALWAYS given so the user can
            // open the original file. No tenant id and no s3 key appear in it, and it does not
            // expire - unlike the old short-lived presigned link, which also leaked the key.
            if (publicFileUrlBuilder != null && e.getId() != null) {
                out.put("url", publicFileUrlBuilder.fileUrl(e.getId(), true));
                // Only the generic "open the url" note when the content was neither inlined as text nor
                // skipped for size (an oversize document already carries its own 'extract_note').
                if (!inlined && !out.containsKey("extract_note")) {
                    out.put("note", "Binary/file content - 'url' is the file's permanent opaque link (no tenant id, "
                            + "no storage key). It serves the bytes to a request that carries the caller's session, so it "
                            + "opens inside the signed-in app - it is NOT a public/anonymous link a logged-out user could "
                            + "open. To let the USER see or open the file, call files(action='visualize', file_id='"
                            + e.getId() + "') - that shows a clickable card which opens it in their side panel. "
                            + "Its content is not available as text here: open the 'url' to view it (an image is instead "
                            + "inlined for you to SEE - check the 'vision' field).");
                }
            } else if (!inlined && !out.containsKey("extract_note")) {
                out.put("download_unavailable", true);
                out.put("note", "No link is available for this file right now (object storage not configured).");
            }
        } else {
            // BINARY stored inline (no object-storage key). Try text extraction first (a small
            // inline-stored document still reads); otherwise it is genuinely opaque bytes.
            if (!tryInlineDocumentText(e, mime, tenantId, offset, maxBytes, out)) {
                out.put("note", "Inline binary content - not text, and there is no shareable public link (it is not in "
                        + "object storage). The click-to-open card below still lets the user see/download it.");
            }
        }

        // Make EVERY viewed file visible to the USER, every time. Emit the same
        // click-to-open card marker as visualize so a `view` always renders a card
        // the user can open - not just an opaque url buried in a note. Harmless for
        // text (the inlined content stays for the agent to read) and it is the ONLY
        // way the user can actually SEE a binary/image straight from a `view` call.
        // The agent must echo this marker verbatim for the card to appear.
        out.put("marker", "[visualize:file:" + e.getId() + "]");
        out.put("show_to_user", "Include the marker line above verbatim in your reply - a clickable card then lets the "
                + "user open '" + e.getFileName() + "'. A card is emitted for every viewed file, so the user can always "
                + "see what you viewed.");

        // Vision channel: for an image small enough, attach the raw bytes (base64) under the
        // metadata media key so the MCP bridge turns it into a native image content block -
        // the ONLY way a vision-capable model can actually SEE the file. The bytes go in
        // METADATA, never in the agent-visible text above (that would blow up the context and
        // be size-capped). The in-process metadata sinks strip it (see ToolMediaMetadata) so it
        // never bloats the chat stream or observability rows.
        Map<String, Object> metadata = new HashMap<>();
        attachImageMediaIfEligible(e, mime, tenantId, metadata, out);

        return ToolExecutionResult.success(ToolResultSizeCap.capLargeStrings(out), metadata);
    }

    /**
     * When {@code e} is an image within {@link #imageInlineMaxBytes}, download its bytes and
     * attach a base64 image descriptor under {@link ToolMediaMetadata#MEDIA_KEY} so the MCP
     * bridge can render it as vision input. No-op (with an explanatory {@code vision} note in
     * {@code out}) for non-images, oversized images, or when bytes cannot be read. Never throws.
     */
    private void attachImageMediaIfEligible(StorageEntity e, String mime, String tenantId,
                                            Map<String, Object> metadata, Map<String, Object> out) {
        if (!"image".equals(kindOf(mime))) {
            return;
        }
        Integer size = e.getSizeBytes();
        if (size != null && size > imageInlineMaxBytes) {
            out.put("vision", "Image too large to inline for vision (" + size + " bytes > " + imageInlineMaxBytes
                    + "). Open it via the card/url to see it.");
            return;
        }
        byte[] bytes = readFileBytes(e, tenantId);
        if (bytes == null || bytes.length == 0) {
            out.put("vision", "Image bytes unavailable to inline for vision; open it via the card/url to see it.");
            return;
        }
        if (bytes.length > imageInlineMaxBytes) {
            out.put("vision", "Image too large to inline for vision (" + bytes.length + " bytes > "
                    + imageInlineMaxBytes + "). Open it via the card/url to see it.");
            return;
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        metadata.put(ToolMediaMetadata.MEDIA_KEY,
                List.of(ToolMediaMetadata.imageDescriptor(mime, base64)));
        out.put("vision", "This image is inlined as a vision content block - you can SEE it directly in this result.");
    }

    /**
     * Attempt to extract a document's text and inline it (same {@link #sliceText} contract as a
     * TEXT file: {@code content}/{@code offset}/{@code truncated}/{@code NEXT} with the 128 KB
     * window). Returns {@code true} when text was inlined; {@code false} (caller falls back to the
     * url/card) for a non-document type (audio/video/archive), an image (it goes to the vision
     * channel instead), a file over {@link #extractMaxBytes}, unreadable bytes, or a parse failure.
     * Never throws.
     *
     * <p>This is what lets an agent READ an uploaded PDF/Word/Excel/HTML/CSV/text file's content
     * directly. The extracted text rides in the normal tool-result body, so it reaches every
     * provider on both the bridge and direct-API paths identically.</p>
     */
    private boolean tryInlineDocumentText(StorageEntity e, String mime, String tenantId,
                                          int offset, int maxBytes, Map<String, Object> out) {
        // Images are SEEN (vision channel), never read as text here.
        if ("image".equals(kindOf(mime))) {
            return false;
        }
        if (!DocumentTextExtractor.isTextExtractable(mime, e.getFileName())) {
            return false;
        }
        Integer size = e.getSizeBytes();
        if (size != null && size > extractMaxBytes) {
            out.put("extract_note", "Document too large to extract inline (" + size + " bytes > " + extractMaxBytes
                    + "). Open it via the url/card, or wire its 'ref' into an ExtractFromFile workflow node for "
                    + "chunked extraction.");
            return false;
        }
        byte[] bytes = readFileBytes(e, tenantId);
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        if (bytes.length > extractMaxBytes) {
            out.put("extract_note", "Document too large to extract inline (" + bytes.length + " bytes > "
                    + extractMaxBytes + "). Open it via the url/card, or wire its 'ref' into an ExtractFromFile "
                    + "workflow node for chunked extraction.");
            return false;
        }
        String text;
        try {
            text = DocumentTextExtractor.extract(bytes, mime, e.getFileName());
        } catch (Exception ex) {
            log.warn("files.view: text extraction failed for {} ({}): {}", e.getId(), mime, ex.getMessage());
            return false;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        out.put("extracted_text", true);
        out.put("note", "Extracted text from this file - the 'content' below IS the file's readable text "
                + "(page long files with the 'NEXT' offset call). files(action='visualize', file_id='"
                + e.getId() + "') shows the original file to the user.");
        out.putAll(sliceText(e.getId(), text, offset, maxBytes));
        return true;
    }

    /**
     * Read a file's raw bytes: inline BINARY rows carry them directly; S3-backed rows are
     * downloaded via {@link FileStorageService}. Returns {@code null} on any failure (missing
     * adapter, download error) so the caller degrades gracefully to the url/card.
     */
    private byte[] readFileBytes(StorageEntity e, String tenantId) {
        try {
            String st = e.getStorageType();
            if ("S3_FILE".equals(st) || e.getS3Key() != null) {
                if (fileStorageService == null || e.getS3Key() == null) {
                    return null;
                }
                // Download under the tenant that OWNS the key, not the caller's context tenant.
                // storage-service refuses the internal download (403) unless the X-User-ID header
                // matches the key's tenant prefix (isKeyOwnedByTenant: key.startsWith(tenantId+"/")).
                // The entity is already authorized upstream (canAccessFile + getEntityByIdForScope),
                // and s3 keys are namespaced as "<tenantId>/..." at upload, so e.getTenantId() is the
                // correct owner. The context tenantId stays as a fallback for legacy rows.
                String ownerTenant = (e.getTenantId() != null && !e.getTenantId().isBlank())
                        ? e.getTenantId() : tenantId;
                return fileStorageService.download(ownerTenant, e.getS3Key()).orElse(null);
            }
            // Inline binary stored on the row itself.
            return e.getDataBinary();
        } catch (Exception ex) {
            log.warn("files.view: failed to read bytes for {}: {}", e.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Return a byte-window of {@code full} starting at {@code offset}, capped at
     * {@code maxBytes}. Surfaces the same {@code truncated}/{@code original_length}
     * vocabulary as {@link ToolResultSizeCap} plus a {@code next} expand call when
     * more content remains - the missing "expand" half of the truncation contract.
     */
    private static Map<String, Object> sliceText(UUID fileId, String full, int offset, int maxBytes) {
        if (full == null) full = "";
        // Windows are measured in String chars (UTF-16 units), matching ToolResultSizeCap -
        // for multi-byte text the real UTF-8 size can exceed the char count ("bytes" ≈ chars here).
        int total = full.length();
        int start = Math.min(offset, total);
        int end = Math.min(start + maxBytes, total);
        String slice = full.substring(start, end);
        boolean truncated = end < total;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", slice);
        r.put("offset", start);
        r.put("returned_bytes", slice.length());
        r.put("original_length", total);
        r.put("truncated", truncated);
        if (truncated) {
            r.put("NEXT", "files(action='view', file_id='" + fileId + "', offset=" + end + ")");
        }
        return r;
    }

    // ==================== visualize (show a file to the user) ====================

    /**
     * Emit a chat card the user can click to open the file (preview + download) in
     * their side panel. The card is rendered from the {@code marker} the agent
     * echoes into its reply - same mechanism as {@code agent}/{@code application}
     * visualize. This is for the USER's eyes; {@code view} is for reading content
     * into the agent's own context. The agent cannot open the panel itself - only
     * the user, by clicking.
     */
    private ToolExecutionResult executeVisualize(Map<String, Object> params, String tenantId, String orgId, String orgRole,
                                                 Set<String> allowedFiles) {
        UUID fileId = ToolParamUtils.getUuidParam(params, "file_id");
        if (fileId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "file_id is required (the UUID from files(action='list')).");
        }
        if (!canAccessFile(orgId, tenantId, orgRole, fileId) || !withinAllowList(allowedFiles, fileId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        Optional<StorageEntity> opt = storageService.getEntityByIdForScope(fileId, tenantId, orgId);
        if (opt.isEmpty()) {
            // 404, never 403 - do not leak existence of files in another workspace.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }
        StorageEntity e = opt.get();
        if (e.getFileName() == null) {
            // Not a browsable file (e.g. a step-output JSON blob) - same boundary as list's filesOnly.
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "File not found: " + fileId);
        }

        Map<String, Object> out = baseMeta(e);
        out.put("marker", "[visualize:file:" + e.getId() + "]");
        out.put("message", "Showing '" + e.getFileName() + "' to the user as a clickable card. "
            + "Include the marker line verbatim in your reply; the user clicks the card to open the file.");
        return ToolExecutionResult.success(out);
    }

    private Map<String, Object> baseMeta(StorageEntity e) {
        String mime = e.getMimeType() != null ? e.getMimeType() : e.getContentType();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_id", e.getId() != null ? e.getId().toString() : null);
        m.put("name", e.getFileName());
        m.put("mime_type", mime);
        m.put("size_bytes", e.getSizeBytes());
        m.put("kind", kindOf(mime));
        if (e.getCreatedAt() != null) m.put("created_at", e.getCreatedAt().toString());
        if (e.getRunId() != null) m.put("run_id", e.getRunId());
        if (e.getWorkflowId() != null) m.put("workflow_id", e.getWorkflowId());
        if (e.getStepKey() != null) m.put("step", e.getStepKey());
        addWorkflowContext(m, e.getWorkflowId(), e.getRunId(), e.getStepKey(), mime);
        return m;
    }

    /**
     * Surface the producing workflow/run as a relative deep link, and - for an
     * interface screenshot image - a {@code how_to_view} guide. The screenshot is
     * captured full-page (the whole scrollable interface, never cropped), so it is
     * the source of truth for how the page actually displays. The guide gives the
     * exact steps: view the rendered PNG via {@code files(action='view')} (its
     * {@code url}), show it to the USER via {@code visualize}, and - as a SEPARATE
     * concern - inspect the resolved data feeding the render via
     * {@code get_node_output} (which shows variable_mapping, not whether the page
     * looks right). {@code mime} drives the image detection; {@code stepKey}
     * starting with {@code interface:} marks a screenshot.
     */
    private void addWorkflowContext(Map<String, Object> m, Object workflowId, Object runId, Object stepKey, String mime) {
        String wf = workflowId == null ? null : String.valueOf(workflowId);
        String run = runId == null ? null : String.valueOf(runId);
        String step = stepKey == null ? null : String.valueOf(stepKey);
        if (wf != null && !wf.isBlank() && run != null && !run.isBlank()) {
            String link = "/workflows/" + wf + "/runs/" + run;
            if (step != null && !step.isBlank()) {
                link += "?node=" + step;
            }
            m.put("workflow_run_url", link);
        }
        boolean isInterfaceShot = "image".equals(kindOf(mime))
                && step != null && step.startsWith("interface:");
        if (isInterfaceShot) {
            m.put("interface_screenshot", true);
            String fid = String.valueOf(m.get("file_id"));
            m.put("how_to_view",
                "Rendered screenshot of this interface node, captured FULL-PAGE (the entire scrollable "
                + "interface, never cropped to the viewport). To SEE the rendered image: "
                + "files(action='view', file_id='" + fid + "') inlines the PNG as a vision content block you "
                + "can SEE directly in the result (when small enough - see its 'vision' note), returns its "
                + "'url' - the full PNG of how the page actually displays - AND a 'marker' you echo verbatim "
                + "to show that image to the USER as a click-to-open card (no separate visualize call needed). "
                + "Separately, to inspect "
                + "the DATA that fed the render, workflow(action='get_node_output', node_id='" + step + "'"
                + (run != null && !run.isBlank() ? ", run_id='" + run + "'" : "")
                + ") returns the resolved interface / variable_mapping - that tells you WHAT data was bound, "
                + "not whether the page looks right; use the screenshot url for the visual check.");
        }
    }

    /**
     * Canonical FileRef the workflow runtime consumes - {@code {_type:'file', path, name,
     * mimeType, size}} (same shape the file-producer nodes emit, see FileRefSchema). The
     * agent drops this object into a node's file parameter via workflow(action='update')
     * to feed a stored file into a workflow. Only object-storage-backed files (with a
     * storage key) can be referenced this way; the caller guards on {@code s3Key != null}.
     */
    private Map<String, Object> buildFileRef(StorageEntity e) {
        String mime = e.getMimeType() != null ? e.getMimeType() : e.getContentType();
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", e.getS3Key());
        ref.put("name", e.getFileName());
        ref.put("mimeType", mime);
        ref.put("size", e.getSizeBytes() != null ? e.getSizeBytes() : 0);
        // Opaque, absolute, user-facing URL (no tenant id / no s3 key). `path` stays the s3 key -
        // that is the runtime wiring handle the engine fetches by; `url` is what the user opens.
        if (publicFileUrlBuilder != null && e.getId() != null) {
            ref.put("url", publicFileUrlBuilder.fileUrl(e.getId(), true));
        }
        return ref;
    }

    // ==================== helpers ====================

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Accept an ISO-8601 instant ({@code 2026-05-01T00:00:00Z}) or a plain date
     *  ({@code 2026-05-01}, treated as UTC midnight). Unparseable → null (filter ignored). */
    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return Instant.parse(t);
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Agent-friendly file category derived from the MIME type (display only). */
    private static String kindOf(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("audio/")) return "audio";
        if (m.contains("pdf") || m.contains("msword") || m.contains("officedocument")
                || m.contains("presentation") || m.contains("spreadsheet") || m.contains("excel")) {
            return "document";
        }
        if (m.contains("json") || m.contains("csv") || m.contains("xml") || m.startsWith("text/")) {
            return "data";
        }
        return "file";
    }

    private static String head(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * The agent's file allow-list (toolsConfig.files threaded as {@code allowedFileIds}).
     * Returns null when the agent is unrestricted - an absent OR empty list means full
     * org-scoped file access (opt-in: only a non-empty list scopes the agent).
     */
    private static Set<String> agentAllowedFileIds(ToolsProvider.ToolExecutionContext context) {
        if (context == null) {
            return null;
        }
        List<String> ids = ToolAccessControl.getAllowedIds(context.credentials(), "file");
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return new HashSet<>(ids);
    }

    /** True if the agent may reach this file: no allow-list, or the id is in it. */
    private static boolean withinAllowList(Set<String> allowedFiles, UUID fileId) {
        return allowedFiles == null || allowedFiles.contains(fileId.toString());
    }

    private Set<UUID> restrictedFileIds(String orgId, String userId, String orgRole) {
        if (orgAccessGuard == null || orgId == null || orgId.isBlank()) {
            return Set.of();
        }
        Set<String> restricted = orgAccessGuard.getRestrictedResourceIds(orgId, userId, "file", orgRole);
        if (restricted == null || restricted.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String resourceId : restricted) {
            parseUuid(resourceId).ifPresent(ids::add);
        }
        return ids;
    }

    private boolean canAccessFile(String orgId, String userId, String orgRole, UUID fileId) {
        return orgAccessGuard == null
                || orgId == null
                || orgId.isBlank()
                || orgAccessGuard.canAccess(orgId, userId, "file", fileId.toString(), orgRole);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    // ==================== tool definition ====================

    private AgentToolDefinition buildFilesTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("list | get | view | visualize | create_folder | move_to_folder | help")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("file_id", "File UUID from list - required for get/view", false),
            stringParam("query", "Filter by file name (list)", false),
            stringParam("run_id", "Only files produced by this run (list)", false),
            stringParam("workflow_id", "Only files produced by this workflow (list)", false),
            stringParam("date_from", "ISO-8601 date/instant lower bound on created_at (list)", false),
            stringParam("date_to", "ISO-8601 date/instant upper bound on created_at (list)", false),
            stringParam("folder", "Folder to list inside: 'root' for the top level, a folder_ref from a previous "
                + "list (a folder id, or a workflow folder token like wf:<id>/e0). Omit for a flat newest-first "
                + "listing of every file. Also the parent for create_folder / the target for move_to_folder "
                + "('root'/omit = top level).", false),
            stringParam("name", "New folder name (create_folder), max 100 chars", false),
            arrayParam("file_ids", "File and/or folder ids to move (move_to_folder)", false),
            intParam("limit", "Page size (list), default 25, max 50", false, 25),
            intParam("offset", "Pagination offset (list); also the byte offset to expand from (view)", false, 0),
            intParam("max_bytes", "Max text bytes to inline (view), default & cap 128 KB", false, VIEW_MAX_BYTES),
            arrayParam("topics", "Optional help filter: ['actions','concepts','examples']", false)
        );

        String description = "Browse and open files in your workspace (documents, images, exports, uploads), newest first.\n"
            + "- list: paginated file listing. Filters: query (name), run_id, workflow_id, date_from, date_to. default 25, max 50. "
            + "Pass folder='root' (or a folder_ref) to browse the folder TREE instead of a flat list - returns folders[] + files[].\n"
            + "- get: metadata + a cheap preview (JSON skeleton / text head) for one file_id. No full content.\n"
            + "- view: read a file's content for one file_id. This is how you access an uploaded file's data: "
            + "documents (PDF, Word, Excel, HTML, CSV, text/code) come back as extracted TEXT in 'content'; images "
            + "are inlined so a vision model SEES them; audio/video/other binaries return an opaque permanent 'url' "
            + "only. Long content pages with offset (follow the 'NEXT' call). Every view also returns a 'marker' - "
            + "echo it verbatim and a click-to-open card lets the user see the file (works for any file, every time).\n"
            + "- visualize: show a file to the user as a clickable card in the chat; they click it to open the "
            + "file (preview + download). Use when the user wants to SEE a file.\n"
            + "- create_folder: make a manual folder (params: name, optional folder=parent). Returns folder_id.\n"
            + "- move_to_folder: move files/folders into a manual folder or to the root (params: file_ids, optional folder=target). "
            + "Returns moved_count + failed[].\n"
            + "- help: full reference.\n"
            + "Folders: workflow folders (wf:...) are computed groupings of a workflow's files by run/epoch - browse INTO them "
            + "and move files OUT of them, but you cannot create/rename them or move files INTO them; only manual folders and "
            + "the root accept moves.\n"
            + "Step/node OUTPUTS are NOT here - read them via workflow(action='get_run') then get_node_output. "
            + "Deleting files is done by the user in their workspace, not by you.";

        return AgentToolDefinition.builder()
            .name(TOOL_NAME)
            .description(description)
            .category(ToolCategory.UTILITY)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Drill down list → get → view (expand long text with offset). Browse the folder tree with "
                + "files(action='list', folder='root'); organise with files(action='create_folder', name=…) and "
                + "files(action='move_to_folder', file_ids=[…], folder=…). Show a file to the user with "
                + "files(action='visualize', file_id=…). Call files(action='help') for the full reference.")
            .requiresAuth(true)
            .tags(List.of("files", "storage", "documents", "browse", "download"))
            .timeoutMs(30_000L)
            .build();
    }

    // ==================== help ====================

    private static final Set<String> HELP_TOPICS = Set.of("actions", "concepts", "examples");

    private Map<String, Object> buildHelpPayload(Map<String, Object> params) {
        Set<String> requested = new LinkedHashSet<>();
        Object topicsObj = params == null ? null : params.get("topics");
        if (topicsObj instanceof List<?> topicList && !topicList.isEmpty()) {
            for (Object t : topicList) {
                if (t == null) continue;
                String s = String.valueOf(t).trim().toLowerCase();
                if (HELP_TOPICS.contains(s)) requested.add(s);
            }
        }
        if (requested.isEmpty()) requested = new LinkedHashSet<>(HELP_TOPICS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
            "FILES TOOL - browse and open the files in your active workspace (organization). "
            + "Files are documents, images, exports, and uploads - anything with a file name. "
            + "Drill down: list (find) → get (peek metadata) → view (read content or get a link). "
            + "Browse the folder tree with list(folder='root') and organise with create_folder / move_to_folder "
            + "(manual folders only; workflow folders wf:... are computed groupings you browse but cannot edit). "
            + "Step/node outputs are NOT files - read those via workflow(action='get_run') / get_node_output.");

        if (requested.contains("actions")) out.put("actions", buildActionsHelp());
        if (requested.contains("concepts")) out.put("concepts", buildConceptsHelp());
        if (requested.contains("examples")) out.put("examples", buildExamplesHelp());
        return out;
    }

    private static Map<String, Object> buildActionsHelp() {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("list", Map.of(
            "summary", "List workspace files, newest first. Real files only (step outputs are excluded - use get_run for those). "
                + "Pass `folder` to browse the folder TREE instead: 'root' = top level, a folder_ref from a previous list "
                + "(a manual folder id, or a workflow folder token like wf:<id>/e0). Omit `folder` for the flat listing.",
            "params", Map.of(
                "folder", "optional - 'root' or a folder_ref to browse one folder level (returns folders[] + files[]); omit for a flat list",
                "query", "optional - match file name (flat list only)",
                "run_id", "optional - only files a given run produced",
                "workflow_id", "optional - only files a given workflow produced",
                "date_from / date_to", "optional - ISO-8601 bounds on created_at",
                "limit", "optional, default 25, max 50",
                "offset", "optional, default 0"),
            "returns", "flat (no folder): envelope {files:[{file_id, name, mime_type, size_bytes, size, kind, url (opaque absolute link), "
                + "created_at, run_id?, workflow_id?, step?}], count, total, offset, limit, hasMore, hint}. "
                + "folder set: {status:'OK', kind:'files', folder (where you are), parent (one level up - a folder_ref or 'root'; absent at the root), "
                + "folders:[{folder_ref, name, child_count, folder_kind: manual|workflow|epoch|spawn|iteration}], "
                + "files:[ same file shape ], count, total, offset, limit, hasMore, hint}. "
                + "Pass a folder's folder_ref back as `folder` to drill in; pass `parent` (or 'root') as `folder` to go back out; open a file with view/get."
        ));
        actions.put("get", Map.of(
            "summary", "Metadata + a cheap preview for one file. Does NOT return full content.",
            "params", Map.of("file_id", "required - UUID from list"),
            "returns", "{file_id, name, mime_type, size_bytes, kind, created_at, "
                + "viewable:'text'(view returns extracted/inline text)|'image'(view inlines it for you to SEE)|'download'(binary, url only)|'none', "
                + "url? (opaque absolute link the user can open - present for object-storage files), "
                + "preview? (text head), preview_skeleton? (JSON outline), width?/height?/duration_seconds?, "
                + "ref? (FileRef {_type,path,name,mimeType,size,url} to wire into a workflow node - present when the file has a storage path)}"
        ));
        actions.put("view", Map.of(
            "summary", "Read a file's content AND make it visible to the user. This is how you access uploaded data: "
                + "documents (PDF, Word, Excel, HTML, CSV, text/code) are extracted to TEXT in 'content' (paged at "
                + "128 KB); images are inlined as a vision block you SEE; audio/video/other binaries return an "
                + "opaque 'url' only. EVERY view also returns a 'marker' (click-to-open card) - echo it verbatim so "
                + "the user can always see the file you viewed.",
            "params", Map.of(
                "file_id", "required - UUID from list",
                "offset", "optional - byte offset to start from (use the value in 'NEXT' to expand)",
                "max_bytes", "optional - max bytes to inline, default & cap 128 KB"),
            "returns", "always includes marker (echo verbatim → clickable card for the user) + show_to_user; "
                + "text/JSON/extracted document → {content, offset, returned_bytes, original_length, truncated, NEXT? (expand call)} "
                + "(documents also carry extracted_text:true + url to open the original); "
                + "image → {url, vision (it is inlined for you to SEE)}; "
                + "audio/video/other binary → {url, note} (opaque, absolute, permanent - give 'url' to the user) or {download_unavailable, note}; "
                + "a document over the extract cap returns {url, extract_note}; "
                + "object-storage-backed files also include ref? (FileRef for workflow wiring)"
        ));
        actions.put("visualize", Map.of(
            "summary", "Show a file to the user as a clickable card in the chat. They click the card to open the file "
                + "(image preview + download) - you cannot open it for them. Use when the user wants to SEE a file you found.",
            "params", Map.of("file_id", "required - UUID from list"),
            "returns", "{file_id, name, mime_type, marker, message} - include the marker line verbatim in your reply so the card renders"
        ));
        actions.put("create_folder", Map.of(
            "summary", "Create a MANUAL folder. Manual folders are the only folders you can create - workflow folders (wf:...) "
                + "are computed, not created. Nest under another manual folder by passing its id as `folder`, or omit `folder` "
                + "(or pass 'root') for the top level.",
            "params", Map.of(
                "name", "required - the folder name, max 100 chars (trimmed)",
                "folder", "optional - parent manual folder id, or 'root'/omit for the top level. A workflow folder (wf:...) is rejected."),
            "returns", "{status:'OK', folder_id (this IS a folder_ref - use it as `folder` to list / move into it), name, parent, hint}. "
                + "A blank name or a workflow-folder parent returns a validation error."
        ));
        actions.put("move_to_folder", Map.of(
            "summary", "Move files and/or manual folders into a manual folder, or out to the root (folder='root'/omit). "
                + "A file moved into a manual folder leaves its workflow grouping. You cannot move items INTO a workflow folder (wf:...).",
            "params", Map.of(
                "file_ids", "required - array of file and/or manual-folder ids to move",
                "folder", "optional - target manual folder id, or 'root'/omit to move to the top level. A workflow folder (wf:...) is rejected."),
            "returns", "{status:'OK', moved_count, failed:[{file_id, reason}], hint}. Per-id failures (e.g. reason "
                + "'not a valid id', 'restricted' (you may not write that file), 'not found in this workspace', "
                + "'cannot move a folder into its own descendant') are reported "
                + "in `failed` - read them, do not blindly retry. An empty file_ids or a workflow-folder target returns a validation error."
        ));
        actions.put("help", Map.of(
            "summary", "This reference.",
            "params", Map.of("topics", "optional array - ['actions','concepts','examples']")
        ));
        return actions;
    }

    private static Map<String, Object> buildConceptsHelp() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files_vs_outputs",
            "This tool browses real FILES (file_name present): PDFs, images, CSVs, uploads, exports. "
            + "It does NOT browse a step's structured OUTPUT - read those via workflow(action='get_run') "
            + "then get_node_output, which keep the run/epoch/node context. A run's downloadable files DO "
            + "appear here: filter with run_id.");
        out.put("finding_a_file",
            "To FIND a specific file, pick the fastest path - you do not have to drill folders: "
            + "(1) know its name → files(action='list', query='<part of the name>') searches the whole workspace flat, "
            + "newest first. (2) know which run/workflow made it → files(action='list', run_id='<run_id>') or "
            + "workflow_id='<id>') - also a flat list. (3) browsing by workflow → files(action='list', folder='wf:<id>') "
            + "then drill epoch → run → item via each folder_ref. query/run_id/workflow_id apply to the FLAT list only "
            + "(omit `folder`); folder browsing returns a level's folders+files unfiltered. Once you see the file row, "
            + "OPEN it: files(action='view', file_id=…) for content (documents come back as text, images you SEE, "
            + "other binaries give a url), or files(action='get', file_id=…) for metadata + the `ref` you wire into a "
            + "workflow. Show it to the user with files(action='visualize', file_id=…).");
        out.put("truncation_and_expand",
            "view inlines text/JSON up to 128 KB. When the file is larger, the response has truncated=true, "
            + "original_length (total chars), and a 'NEXT' call. Call that NEXT (it carries the right offset) "
            + "to read the following window - repeat to walk the whole file. This is the same 128 KB cap and "
            + "truncated/original_length vocabulary you see on any large tool result.");
        out.put("handles",
            "Always address a file by its file_id (UUID from list). A file_id from another workspace returns "
            + "'File not found' - files are organization-scoped. Don't hand-build storage paths: to wire a file into "
            + "a workflow, use the `ref` (FileRef) that get/view return.");
        out.put("reading_content",
            "How files(action='view') gives you a file's data, by type: "
            + "(1) DOCUMENTS - PDF, Word (.docx), Excel (.xlsx), HTML, CSV, and any text/code file - are extracted to "
            + "TEXT and returned in 'content' (with extracted_text:true), paged at 128 KB via the 'NEXT' offset call. "
            + "This is how you read an uploaded document end to end - no extra step. "
            + "(2) IMAGES are inlined as a vision content block you can SEE directly in the result (within the inline "
            + "size limit - check the 'vision' note). "
            + "(3) AUDIO / VIDEO / archives / other binaries have no text or visual representation, so view returns "
            + "only the 'url'. "
            + "For every object-storage file, view also returns an opaque absolute `url` (no tenant id, no storage "
            + "path) that opens the ORIGINAL file inside the signed-in app (not a public anonymous link), plus a `ref` "
            + "(FileRef) you wire into a workflow node. To show a file to the USER, files(action='visualize', "
            + "file_id=…) renders a clickable card. A document over the extract cap returns 'extract_note' instead of "
            + "'content' - open it via the url, or wire its `ref` into an ExtractFromFile workflow node for chunked "
            + "extraction. Deleting files is the user's job; you have no delete action.");
        out.put("interface_screenshots",
            "A file whose `step` starts with 'interface:' and whose kind is image is a RENDERED interface "
            + "screenshot, captured FULL-PAGE (the whole scrollable interface, never cropped to the viewport). "
            + "get/view tag it with interface_screenshot=true plus a how_to_view guide. To SEE the rendered image, "
            + "files(action='view', file_id=…) inlines the PNG as a vision content block you can SEE directly (when "
            + "small enough) and returns its 'url' - the full PNG of how the page displays; inspect it to "
            + "confirm the interface renders correctly, then files(action='visualize', file_id=…) to show it to the "
            + "user. To inspect the DATA that fed the render (resolved variable_mapping - not whether it looks right), "
            + "use workflow(action='get_node_output', node_id=<step>, run_id=<run_id>). Every file also carries "
            + "workflow_run_url, a deep link back to the run that produced it.");
        out.put("show_to_user",
            "Both view and visualize return a 'marker' line - include it verbatim in your reply and a clickable card "
            + "appears in the chat that the user clicks to open the file. view ALSO returns the content/url for your own "
            + "context, and emits the card for EVERY file so the user always sees what you viewed; visualize is the "
            + "card-only path when you just want to show a file without reading it. Either way, echoing the marker is "
            + "what makes the file visible to the user.");
        out.put("folders",
            "Files live in a folder tree you browse with files(action='list', folder=…). At the root (folder='root') you see "
            + "your MANUAL folders plus one WORKFLOW folder per workflow that produced files, plus loose files. Each folder row "
            + "carries folder_ref (pass it back as `folder` to drill in), folder_kind (manual | workflow | epoch | spawn | iteration), "
            + "name, and child_count. Browse a workflow folder to drill into its epoch → run → item groupings. "
            + "A workflow / epoch / run / item folder appears ONLY when that group actually produced a real file - a run "
            + "that downloaded or uploaded nothing shows NO folder, so you never open an empty workflow folder (step/node "
            + "outputs are not files and never create one). The intermediate run/item levels also collapse when there is "
            + "nothing to disambiguate (a single run, or a single item). "
            + "To go back OUT, every folder listing returns `parent` (the location one level up - a folder_ref or 'root'); "
            + "pass it as `folder`, or jump straight to the top with folder='root'. The root listing has no `parent`. "
            + "Workflow folders (wf:...) are COMPUTED groupings of a workflow's files by run/epoch - you can browse INTO them and "
            + "move files OUT of them, but you cannot create or rename them or move files INTO them; only manual folders (made with "
            + "create_folder) and the root accept moves. Create a manual folder with files(action='create_folder', name=…) and put "
            + "files in it with files(action='move_to_folder', file_ids=[…], folder='<folder_id>'). A file moved into a manual folder "
            + "leaves its workflow grouping (it no longer appears under the workflow folder). A move reports per-id problems in "
            + "`failed` (each with a `reason`) and the rest in `moved_count` - read `failed` rather than retrying blindly. "
            + "Listing pages 25 by default (max 50); a folder name is capped at 100 chars.");
        out.put("use_in_workflow",
            "To use a stored file as input to a workflow, call files(action='get', file_id) and copy its `ref` object "
            + "(a FileRef {_type:'file', path, name, mimeType, size, url}) into the node parameter that takes a file, "
            + "then save with workflow(action='update'). The engine wires by `path` (the storage handle); `url` is the "
            + "user-facing link. Only files with a storage path expose a ref; inline text/binary files cannot be wired this way.");
        return out;
    }

    private static Map<String, Object> buildExamplesHelp() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recent_files", "files(action='list')");
        out.put("find_by_name", "files(action='list', query='invoice')");
        out.put("find_then_open", "files(action='list', query='report') → files(action='view', file_id='<id from the row>')");
        out.put("files_from_a_run", "files(action='list', run_id='<run_id>')");
        out.put("browse_folder_tree", "files(action='list', folder='root')");
        out.put("open_a_folder", "files(action='list', folder='<folder_ref from a folder row>')");
        out.put("browse_a_workflow_folder", "files(action='list', folder='wf:<workflow_id>')");
        out.put("go_up_one_level", "files(action='list', folder='<parent from the last listing>')");
        out.put("back_to_top", "files(action='list', folder='root')");
        out.put("create_folder", "files(action='create_folder', name='Invoices')");
        out.put("create_subfolder", "files(action='create_folder', name='Q1', folder='<parent_folder_id>')");
        out.put("move_files_into_folder", "files(action='move_to_folder', file_ids=['<uuid1>','<uuid2>'], folder='<folder_id>')");
        out.put("move_files_to_root", "files(action='move_to_folder', file_ids=['<uuid>'], folder='root')");
        out.put("peek", "files(action='get', file_id='<uuid>')");
        out.put("read_text", "files(action='view', file_id='<uuid>')");
        out.put("read_uploaded_document", "files(action='view', file_id='<pdf/docx/xlsx/csv id>') → its text comes back in 'content' (page long files with the 'NEXT' offset call)");
        out.put("expand_long_text", "files(action='view', file_id='<uuid>', offset=131072)");
        out.put("show_file_to_user", "files(action='visualize', file_id='<uuid>')");
        out.put("use_in_workflow", "files(action='get', file_id='<uuid>') → put its 'ref' into a workflow node's file parameter via workflow(action='update')");
        return out;
    }
}
