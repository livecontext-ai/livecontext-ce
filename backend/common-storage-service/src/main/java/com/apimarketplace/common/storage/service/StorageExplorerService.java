package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.StorageExplorerProjection;
import com.apimarketplace.common.storage.dto.StoragePreviewFile;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for Storage Explorer operations.
 * Provides paginated, filtered listing of storage entries.
 *
 * <p>Post-V261 sweep: every user-scoped row carries a non-null
 * {@code organization_id} (gateway always injects {@code X-Organization-ID};
 * personal-workspace users resolve to their personal-org UUID). The legacy
 * "personal scope = organization_id IS NULL" entry points were removed;
 * {@code organizationId} is now required on every search/stats call.</p>
 */
@Service
@Transactional(readOnly = true)
public class StorageExplorerService {

    private static final Logger logger = LoggerFactory.getLogger(StorageExplorerService.class);

    private final StorageExplorerRepository explorerRepository;

    public StorageExplorerService(StorageExplorerRepository explorerRepository) {
        this.explorerRepository = explorerRepository;
    }

    /**
     * Strict org-scope search. Returns only rows tagged with the given
     * {@code organizationId}. Throws {@link IllegalArgumentException} if
     * {@code organizationId} is null/blank.
     *
     * <p>{@code filesOnly=true} restricts the result to real files
     * ({@code file_name IS NOT NULL}) - the same predicate the agent files
     * tool uses. The full-page Files browser passes {@code true} so machine
     * JSON step-output blobs (which carry no file name) don't flood the view;
     * the side-panel explorer passes {@code false} (its legacy behaviour).</p>
     */
    public Page<StorageExplorerDto> search(
            String tenantId,
            String organizationId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            Pageable pageable) {
        return search(tenantId, organizationId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, false, null, List.of(), pageable);
    }

    /**
     * @param s3Only when true, restrict to real object-storage files
     *               ({@code s3_key IS NOT NULL}). The full-page Files browser sets
     *               it so DB-resident pseudo-files (observability TEXT blobs, BINARY
     *               chat-attachments/avatars) don't surface. Composes with
     *               {@code filesOnly} (AND); other surfaces leave it false.
     */
    public Page<StorageExplorerDto> search(
            String tenantId,
            String organizationId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Collection<UUID> excludedIds,
            Pageable pageable) {

        logger.debug("Storage explorer search: tenantId={}, orgId={}, search={}, sourceType={}, "
                + "storageType={}, workflowId={}, filesOnly={}, s3Only={}, fileCategory={}",
                tenantId, organizationId, search, sourceType, storageType, workflowId, filesOnly, s3Only, fileCategory);

        Page<StorageExplorerProjection> projections = explorerRepository.search(
                organizationId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, s3Only, fileCategory, excludedIds, pageable);

        return projections.map(p -> StorageExplorerDto.from(p, null));
    }

    /**
     * Folder-aware listing for the Files browser (V313 manual folders).
     *
     * <p>{@code parentFolderId == null} lists the root (top-level folders, then top-level files);
     * a non-null id lists that folder's direct children (sub-folders first, then files). Folders
     * sort before files, {@code created_at DESC} within each group. File filters
     * ({@code fileCategory}, {@code s3Only}, {@code filesOnly}) constrain only the file rows; the
     * {@code search} text also filters folder names. See
     * {@link StorageExplorerRepository#listFolderScope}.
     *
     * <p>For the folder rows on the returned page the {@code childCount} and {@code previewFiles}
     * (≤9 image/video/pdf children, newest first) are computed in TWO batched queries for the whole
     * page - no N+1 over folders.</p>
     */
    public Page<StorageExplorerDto> searchFolderScope(
            String tenantId,
            String organizationId,
            UUID parentFolderId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Collection<UUID> excludedIds,
            Pageable pageable) {

        StorageExplorerRepository.SliceResult slice = explorerRepository.listFolderScope(
                organizationId, parentFolderId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, s3Only, fileCategory, excludedIds,
                pageable.getPageSize(), (int) pageable.getOffset());

        List<StorageExplorerProjection> rows = slice.rows();

        // Batch the folder-only aggregates for the folder rows on THIS page only (no N+1).
        List<UUID> folderIds = new ArrayList<>();
        for (StorageExplorerProjection p : rows) {
            if (p.isFolder()) {
                folderIds.add(p.id());
            }
        }
        // Thread the same member restricted-id deny-list used for listFolderScope into the
        // folder aggregates so a restricted MEMBER/VIEWER neither sees an inflated childCount
        // nor has restricted child UUIDs disclosed in previewFiles.
        Map<UUID, Integer> childCounts = explorerRepository.countChildrenByParent(organizationId, folderIds, excludedIds);
        Map<UUID, List<StoragePreviewFile>> previewFiles = explorerRepository.findPreviewFilesByParent(organizationId, folderIds, excludedIds);
        // Folder "last activity" (MAX child created_at), so each folder DTO exposes the date the last
        // element was added - the same field a virtual folder carries and what the listFolderScope SQL
        // already ORDERs by. A childless folder is absent → withCreatedAt(null) keeps its own date.
        Map<UUID, Instant> folderActivity = explorerRepository.latestChildCreatedAtByParent(organizationId, folderIds, excludedIds);

        List<StorageExplorerDto> dtos = new ArrayList<>(rows.size());
        for (StorageExplorerProjection p : rows) {
            if (p.isFolder()) {
                int count = childCounts.getOrDefault(p.id(), 0);
                List<StoragePreviewFile> previews = previewFiles.getOrDefault(p.id(), List.of());
                dtos.add(StorageExplorerDto.from(p, null, count, previews).withCreatedAt(folderActivity.get(p.id())));
            } else {
                dtos.add(StorageExplorerDto.from(p, null));
            }
        }

        return new PageImpl<>(dtos, pageable, slice.total());
    }

    /**
     * Phase 2b - VIRTUAL workflow folder tree listing.
     *
     * <p>Computes the synthetic {@code workflow → epoch → spawn → iteration} folders (derived from
     * the run-context columns of the org's virtual-root files: {@code parent_folder_id IS NULL}) and
     * the leaf files for the requested {@code address}. {@code address == null} lists the ROOT
     * (manual root folders + virtual workflow folders + loose files with no workflow). The tree
     * COLLAPSES single-spawn/single-iteration levels so the user is never forced to click through a
     * folder that has exactly one child group.</p>
     *
     * <p>Folders and files are composed with HEADER/BODY pagination: the bounded folder list is
     * computed first (so its size is known), then the file query - when a level has files - is issued
     * at the right offset/limit so a page can show folders then spill into files.</p>
     *
     * <p><b>Search interaction:</b> a non-blank {@code search} filters manual folders (by name) and
     * leaf files (by the usual triple-ILIKE), but OMITS the virtual workflow folders at the root -
     * their display name is resolved later in the controller (from the workflows table, outside this
     * boundary), so they cannot be name-filtered here. Searching therefore narrows the root to manual
     * folders + loose files; to search within a workflow, navigate into it first.</p>
     *
     * @param address null = virtual root; otherwise the parsed virtual address.
     */
    public Page<StorageExplorerDto> searchVirtualScope(
            String tenantId,
            String organizationId,
            VirtualFolderAddress address,
            String search,
            String sourceType,
            String storageType,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Collection<UUID> excludedIds,
            Pageable pageable) {
        // Back-compat: callers that do not filter by created-at (e.g. the files MCP tool) get the
        // full window. The Files browser passes an explicit range via the dateFrom/dateTo overload.
        return searchVirtualScope(tenantId, organizationId, address, search, sourceType, storageType,
                filesOnly, s3Only, fileCategory, null, null, excludedIds, pageable);
    }

    /**
     * Date-scoped variant: {@code dateFrom}/{@code dateTo} bound the files' {@code created_at} both in
     * the leaf-file listing AND in the folder visibility/preview GROUP-BY, so a date filter on the
     * Files browser hides out-of-window files and the folders that would only contain them.
     */
    public Page<StorageExplorerDto> searchVirtualScope(
            String tenantId,
            String organizationId,
            VirtualFolderAddress address,
            String search,
            String sourceType,
            String storageType,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Instant dateFrom,
            Instant dateTo,
            Collection<UUID> excludedIds,
            Pageable pageable) {

        boolean hasSearch = search != null && !search.isBlank();

        if (address == null) {
            return virtualRoot(organizationId, search, hasSearch, sourceType, storageType,
                    filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
        }

        return switch (address.level()) {
            case WORKFLOW -> virtualWorkflow(organizationId, address, excludedIds,
                    new StorageExplorerRepository.VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, dateFrom, dateTo),
                    pageable);
            case RUN -> virtualRun(organizationId, address, excludedIds,
                    new StorageExplorerRepository.VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, dateFrom, dateTo),
                    pageable);
            case EPOCH -> virtualEpoch(organizationId, address, search, sourceType, storageType,
                    filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
            case SPAWN -> virtualSpawn(organizationId, address, search, sourceType, storageType,
                    filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
            case ITERATION -> {
                StorageExplorerRepository.SliceResult slice = leafFiles(organizationId, address.workflowId(),
                        address.runId(), address.epoch(), address.spawn(), address.itemIndex(), search, sourceType,
                        storageType, filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
                yield filesOnlyPage(slice, pageable);
            }
        };
    }

    /** ROOT: manual root folders ++ virtual workflow folders, then loose files (workflow_id IS NULL). */
    private Page<StorageExplorerDto> virtualRoot(String organizationId, String search, boolean hasSearch,
                                                 String sourceType, String storageType, boolean filesOnly,
                                                 boolean s3Only, String fileCategory,
                                                 Instant dateFrom, Instant dateTo,
                                                 Collection<UUID> excludedIds, Pageable pageable) {
        List<StorageExplorerDto> folders = new ArrayList<>();

        // Manual root folders (real rows): childCount + 9-up preview, batched (no N+1). Each folder's
        // createdAt is stamped with its last activity (MAX child created_at) so it sorts + displays by
        // the date the last element was added - uniform with the virtual folders' latestCreatedAt below.
        List<StorageExplorerProjection> manualFolders =
                explorerRepository.listRootManualFolders(organizationId, search, excludedIds);
        List<UUID> manualFolderIds = manualFolders.stream().map(StorageExplorerProjection::id).toList();
        Map<UUID, Integer> manualCounts = explorerRepository.countChildrenByParent(organizationId, manualFolderIds, excludedIds);
        Map<UUID, List<StoragePreviewFile>> manualPreviews = explorerRepository.findPreviewFilesByParent(organizationId, manualFolderIds, excludedIds);
        Map<UUID, Instant> manualActivity = explorerRepository.latestChildCreatedAtByParent(organizationId, manualFolderIds, excludedIds);
        for (StorageExplorerProjection p : manualFolders) {
            int count = manualCounts.getOrDefault(p.id(), 0);
            List<StoragePreviewFile> previews = manualPreviews.getOrDefault(p.id(), List.of());
            folders.add(StorageExplorerDto.from(p, null, count, previews).withCreatedAt(manualActivity.get(p.id())));
        }

        // Virtual workflow folders - omitted when a name search is active (their name is resolved
        // downstream in the controller, so they can't be name-filtered at this boundary).
        if (!hasSearch) {
            // A workflow folder appears only if the workflow has ≥1 row matching what the user sees
            // (real files when filesOnly+s3Only) - not merely a machine step-output row.
            StorageExplorerRepository.VirtualFileFilter filter =
                    new StorageExplorerRepository.VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, dateFrom, dateTo);
            List<StorageExplorerRepository.VirtualGroup> groups = explorerRepository.listVirtualGroups(
                    organizationId, VirtualFolderAddress.Level.WORKFLOW, null, null, null, null, excludedIds, filter);
            Map<String, List<StoragePreviewFile>> previews = explorerRepository.previewFilesForVirtualGroups(
                    organizationId, VirtualFolderAddress.Level.WORKFLOW, null, null, null, null, excludedIds, filter);
            for (StorageExplorerRepository.VirtualGroup g : groups) {
                folders.add(StorageExplorerDto.virtualFolder(
                        "wf:" + g.key(), "WORKFLOW", g.key(), null,
                        null, null, null, (int) g.count(),
                        previewFilesForKey(previews, g.key()), g.latestCreatedAt()));
            }
        }

        // All folders (manual + virtual) sort together by last activity, newest first - so the root is
        // ordered by recency uniformly instead of manual-then-virtual. Stable on equal dates; a folder
        // with no activity date (childless manual folder whose own createdAt was also null) sorts last.
        folders.sort(Comparator.comparing(StorageExplorerDto::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // ALL root folders go on the FIRST page; only the loose files paginate (page-aligned). A user
        // with many workflow folders then sees them ALL at once instead of folders spilling onto later
        // pages where they're easy to miss. The pager counts the files only - folders always ride page 1.
        int fileOffset = (int) pageable.getOffset();
        int fileLimit = pageable.getPageSize();
        StorageExplorerRepository.SliceResult looseSlice = explorerRepository.listVirtualLeafFiles(
                organizationId, null, null, null, null, null, search, sourceType, storageType,
                filesOnly, s3Only, fileCategory, dateFrom, dateTo,
                excludedIds, /* nullItemOnly */ false, fileLimit, fileOffset);

        return foldersOnFirstPage(folders, looseSlice, pageable);
    }

    /**
     * WORKFLOW: &gt;1 run → RUN folders (epoch numbers restart per run, so runs must be separated, else
     * two runs' "epoch 0" would merge). Exactly 0/1 run → COLLAPSE the run level and list epochs
     * directly (identical to the pre-run-level behaviour; a single-run workflow gains no extra nesting).
     * Paginated in-memory (bounded).
     */
    private Page<StorageExplorerDto> virtualWorkflow(String organizationId, VirtualFolderAddress address,
                                                     Collection<UUID> excludedIds,
                                                     StorageExplorerRepository.VirtualFileFilter filter,
                                                     Pageable pageable) {
        String wf = address.workflowId();
        // A run folder appears only if that run produced ≥1 matching file (not just step outputs);
        // rows ordered oldest-first so the frontend can number them "Run 1, Run 2, …".
        List<StorageExplorerRepository.VirtualGroup> runs = explorerRepository.listVirtualGroups(
                organizationId, VirtualFolderAddress.Level.RUN, wf, null, null, null, excludedIds, filter);

        if (runs.size() > 1) {
            // EDGE (null run_id): the RUN grouping excludes rows with run_id IS NULL. Such rows
            // exist only for a workflow that pre-dates run-id threading; for that workflow there is at
            // most ONE distinct (non-null) run_id, so it takes the collapse branch below - null-run rows
            // are NOT lost there (the collapsed epoch grouping is run-unconstrained). A workflow that is
            // BOTH multi-run AND has null-run rows cannot occur for run-threaded executions; prod has 0
            // such rows. If one ever appeared, its null-run files would be hidden here (no "Run ?" bucket)
            // - an accepted, documented limitation, not a crash.
            Map<String, List<StoragePreviewFile>> previews = explorerRepository.previewFilesForVirtualGroups(
                    organizationId, VirtualFolderAddress.Level.RUN, wf, null, null, null, excludedIds, filter);
            List<StorageExplorerDto> folders = new ArrayList<>(runs.size());
            // runs come oldest-first, so the 1-based position IS the "Run N" number. It is carried in
            // the (otherwise-null) epoch coordinate so the label travels with the entry - independent of
            // render position - which the breadcrumb relies on (it labels a single folder out of context).
            int runNumber = 0;
            for (StorageExplorerRepository.VirtualGroup g : runs) {
                runNumber++;
                folders.add(StorageExplorerDto.virtualFolder(
                        "wf:" + wf + "/r" + g.key(), "RUN", wf, null,
                        /* epoch = run number */ runNumber, null, null, (int) g.count(),
                        previewFilesForKey(previews, g.key()), g.latestCreatedAt()));
            }
            return foldersOnlyPage(folders, pageable);
        }

        // 0 or 1 run → collapse: list epochs across the workflow (run unconstrained), as before.
        return epochFolders(organizationId, wf, /* runId */ null, excludedIds, filter, pageable);
    }

    /** RUN: epoch folders of one run (no files). Paginated in-memory (bounded). */
    private Page<StorageExplorerDto> virtualRun(String organizationId, VirtualFolderAddress address,
                                                Collection<UUID> excludedIds,
                                                StorageExplorerRepository.VirtualFileFilter filter,
                                                Pageable pageable) {
        return epochFolders(organizationId, address.workflowId(), address.runId(), excludedIds, filter, pageable);
    }

    /**
     * List a workflow's epoch folders, optionally pinned to a single {@code runId} (multi-run) or
     * unconstrained ({@code runId == null}, the collapsed single-run case). The child epoch address
     * carries the run segment when set so navigation stays scoped to the run.
     */
    private Page<StorageExplorerDto> epochFolders(String organizationId, String wf, String runId,
                                                  Collection<UUID> excludedIds,
                                                  StorageExplorerRepository.VirtualFileFilter filter,
                                                  Pageable pageable) {
        // An epoch folder appears only if that epoch produced ≥1 matching file (not just step outputs).
        List<StorageExplorerRepository.VirtualGroup> epochs = explorerRepository.listVirtualGroups(
                organizationId, VirtualFolderAddress.Level.EPOCH, wf, runId, null, null, excludedIds, filter);
        Map<String, List<StoragePreviewFile>> previews = explorerRepository.previewFilesForVirtualGroups(
                organizationId, VirtualFolderAddress.Level.EPOCH, wf, runId, null, null, excludedIds, filter);

        String runSeg = runId != null ? "/r" + runId : "";
        // Display epochs oldest-first and label each one with its REAL stored epoch value ("Epoch 0",
        // "Epoch 4", …) - NOT a positional 1-based index. The real epoch is the SAME number the user
        // already sees for that run everywhere else: the run-inspector and application EpochSlider read
        // the raw workflow_epochs.epoch (see WorkflowRunController#epochTimestamps), so the Files folder
        // must match it. Positional renumbering diverged: only epochs that produced ≥1 file appear here,
        // so a file produced at real epoch 4 while epochs 1-3 produced none showed as "Epoch 1" - a
        // different number than the epoch the user actually ran. We keep the oldest-first sort (the raw
        // epoch is monotonic with time within a run) and carry the real epoch in the DTO's epoch
        // coordinate so the label travels with the entry (grid AND breadcrumb). The raw epoch also stays
        // in the virtualId ("/e<realEpoch>") so navigation/leaf-file lookup keeps targeting the right epoch.
        // (RUN folders DO stay positional - a run_id is an opaque string with no meaningful number, so
        // "Run 1, Run 2" is the only sensible label there; that is handled in virtualWorkflow, not here.)
        List<StorageExplorerRepository.VirtualGroup> ordered = new ArrayList<>(epochs);
        ordered.sort(Comparator.comparingInt(g -> Integer.parseInt(g.key())));
        List<StorageExplorerDto> folders = new ArrayList<>(ordered.size());
        for (StorageExplorerRepository.VirtualGroup g : ordered) {
            int realEpoch = Integer.parseInt(g.key());
            folders.add(StorageExplorerDto.virtualFolder(
                    "wf:" + wf + runSeg + "/e" + realEpoch, "EPOCH", wf, null,
                    /* epoch = REAL stored epoch (matches the run/interface EpochSlider) */ realEpoch,
                    null, null, (int) g.count(),
                    previewFilesForKey(previews, g.key()), g.latestCreatedAt()));
        }
        return foldersOnlyPage(folders, pageable);
    }

    /**
     * EPOCH: spawns. &gt;1 spawn → spawn folders. Exactly 1 spawn → recurse into iterations of that
     * spawn (&gt;1 item → iteration folders; ≤1 item → COLLAPSE to the epoch's leaf files). 0 spawns → empty.
     */
    private Page<StorageExplorerDto> virtualEpoch(String organizationId, VirtualFolderAddress address,
                                                  String search, String sourceType, String storageType,
                                                  boolean filesOnly, boolean s3Only, String fileCategory,
                                                  Instant dateFrom, Instant dateTo,
                                                  Collection<UUID> excludedIds, Pageable pageable) {
        String wf = address.workflowId();
        String runId = address.runId();
        int epoch = address.epoch();
        String runSeg = runId != null ? "/r" + runId : "";
        StorageExplorerRepository.VirtualFileFilter filter =
                new StorageExplorerRepository.VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, dateFrom, dateTo);
        List<StorageExplorerRepository.VirtualGroup> spawns = explorerRepository.listVirtualGroups(
                organizationId, VirtualFolderAddress.Level.SPAWN, wf, runId, epoch, null, excludedIds, filter);

        if (spawns.size() > 1) {
            Map<String, List<StoragePreviewFile>> previews = explorerRepository.previewFilesForVirtualGroups(
                    organizationId, VirtualFolderAddress.Level.SPAWN, wf, runId, epoch, null, excludedIds, filter);
            List<StorageExplorerDto> folders = new ArrayList<>(spawns.size());
            for (StorageExplorerRepository.VirtualGroup g : spawns) {
                int spawn = Integer.parseInt(g.key());
                folders.add(StorageExplorerDto.virtualFolder(
                        "wf:" + wf + runSeg + "/e" + epoch + "/s" + spawn, "SPAWN", wf, null,
                        epoch, spawn, null, (int) g.count(),
                        previewFilesForKey(previews, g.key()), g.latestCreatedAt()));
            }
            return foldersOnlyPage(folders, pageable);
        }

        if (spawns.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Exactly one spawn value - descend a level: look at its iterations.
        int spawnValue = Integer.parseInt(spawns.get(0).key());
        return iterationsOrCollapse(organizationId, wf, runId, epoch, spawnValue, search, sourceType, storageType,
                filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
    }

    /** SPAWN: iterations. &gt;1 item → iteration folders; ≤1 → COLLAPSE to the spawn's leaf files. */
    private Page<StorageExplorerDto> virtualSpawn(String organizationId, VirtualFolderAddress address,
                                                  String search, String sourceType, String storageType,
                                                  boolean filesOnly, boolean s3Only, String fileCategory,
                                                  Instant dateFrom, Instant dateTo,
                                                  Collection<UUID> excludedIds, Pageable pageable) {
        return iterationsOrCollapse(organizationId, address.workflowId(), address.runId(), address.epoch(), address.spawn(),
                search, sourceType, storageType, filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
    }

    /**
     * Shared SPAWN-scope rule used both by SPAWN navigation and by the single-spawn EPOCH collapse:
     * if the {@code (wf, [run,] epoch, spawn)} has more than one iteration → iteration folders;
     * otherwise COLLAPSE to that spawn's leaf files (itemIndex unconstrained). {@code runId} pins the
     * run when navigating a multi-run workflow, or is null when the run level is collapsed.
     */
    private Page<StorageExplorerDto> iterationsOrCollapse(String organizationId, String wf, String runId,
                                                          int epoch, int spawn,
                                                          String search, String sourceType, String storageType,
                                                          boolean filesOnly, boolean s3Only, String fileCategory,
                                                          Instant dateFrom, Instant dateTo,
                                                          Collection<UUID> excludedIds, Pageable pageable) {
        String runSeg = runId != null ? "/r" + runId : "";
        StorageExplorerRepository.VirtualFileFilter filter =
                new StorageExplorerRepository.VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, dateFrom, dateTo);
        List<StorageExplorerRepository.VirtualGroup> items = explorerRepository.listVirtualGroups(
                organizationId, VirtualFolderAddress.Level.ITERATION, wf, runId, epoch, spawn, excludedIds, filter);

        if (items.size() > 1) {
            Map<String, List<StoragePreviewFile>> previews = explorerRepository.previewFilesForVirtualGroups(
                    organizationId, VirtualFolderAddress.Level.ITERATION, wf, runId, epoch, spawn, excludedIds, filter);
            List<StorageExplorerDto> folders = new ArrayList<>(items.size());
            for (StorageExplorerRepository.VirtualGroup g : items) {
                int item = Integer.parseInt(g.key());
                folders.add(StorageExplorerDto.virtualFolder(
                        "wf:" + wf + runSeg + "/e" + epoch + "/s" + spawn + "/i" + item, "ITERATION", wf, null,
                        epoch, spawn, item, (int) g.count(),
                        previewFilesForKey(previews, g.key()), g.latestCreatedAt()));
            }
            // A spawn can ALSO hold files produced OUTSIDE a split (item_index IS NULL) - e.g. a
            // non-split interface screenshot alongside split-produced files. Those are not their own
            // iteration folder, so show them at the spawn level alongside the iteration folders
            // (header/body) rather than orphaning them from the virtual tree.
            int fileOffset = Math.max(0, (int) pageable.getOffset() - folders.size());
            int fileLimit = remainingFileLimit(folders.size(), pageable);
            StorageExplorerRepository.SliceResult nullItemFiles = explorerRepository.listVirtualLeafFiles(
                    organizationId, wf, runId, epoch, spawn, /* itemIndex */ null, search, sourceType, storageType,
                    filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds,
                    /* nullItemOnly */ true, fileLimit, fileOffset);
            return headerBody(folders, nullItemFiles, pageable);
        }

        // ≤1 iteration group → collapse: list the spawn's leaf files (itemIndex unconstrained).
        StorageExplorerRepository.SliceResult slice = leafFiles(organizationId, wf, runId, epoch, spawn, null,
                search, sourceType, storageType, filesOnly, s3Only, fileCategory, dateFrom, dateTo, excludedIds, pageable);
        return filesOnlyPage(slice, pageable);
    }

    /** Issue the leaf-file query at the page's own offset/limit (no folders precede leaf files). */
    private StorageExplorerRepository.SliceResult leafFiles(String organizationId, String wf, String runId, Integer epoch,
                                                            Integer spawn, Integer itemIndex, String search,
                                                            String sourceType, String storageType, boolean filesOnly,
                                                            boolean s3Only, String fileCategory,
                                                            Instant dateFrom, Instant dateTo,
                                                            Collection<UUID> excludedIds, Pageable pageable) {
        return explorerRepository.listVirtualLeafFiles(organizationId, wf, runId, epoch, spawn, itemIndex,
                search, sourceType, storageType, filesOnly, s3Only, fileCategory,
                dateFrom, dateTo, excludedIds,
                /* nullItemOnly */ false, pageable.getPageSize(), (int) pageable.getOffset());
    }

    /** The preview files for one group key (empty when the group has no preview-able file). */
    private static List<StoragePreviewFile> previewFilesForKey(Map<String, List<StoragePreviewFile>> previews, String key) {
        return previews.getOrDefault(key, List.of());
    }

    /** Page of only files (the slice is already at the page offset). */
    private static Page<StorageExplorerDto> filesOnlyPage(StorageExplorerRepository.SliceResult slice,
                                                          Pageable pageable) {
        List<StorageExplorerDto> dtos = slice.rows().stream()
                .map(p -> StorageExplorerDto.from(p, null))
                .toList();
        return new PageImpl<>(dtos, pageable, slice.total());
    }

    /** Page of only (bounded) folders - sub-list the in-memory ordered folder list for this page. */
    private static Page<StorageExplorerDto> foldersOnlyPage(List<StorageExplorerDto> folders, Pageable pageable) {
        int n = folders.size();
        int from = Math.min((int) pageable.getOffset(), n);
        int to = Math.min(from + pageable.getPageSize(), n);
        return new PageImpl<>(new ArrayList<>(folders.subList(from, to)), pageable, n);
    }

    /**
     * HEADER/BODY composition: an in-memory ordered {@code folders} list followed by a file slice
     * already fetched at {@code fileOffset = max(0, offset - folders.size())} /
     * {@code fileLimit = remaining}. The page takes the folder window first, then the (already
     * correctly-offset) file rows; total = folders + fileTotal.
     */
    private static Page<StorageExplorerDto> headerBody(List<StorageExplorerDto> folders,
                                                       StorageExplorerRepository.SliceResult fileSlice,
                                                       Pageable pageable) {
        int folderCount = folders.size();
        long fileTotal = fileSlice.total();
        long total = folderCount + fileTotal;

        int offset = (int) pageable.getOffset();
        int limit = pageable.getPageSize();

        List<StorageExplorerDto> combined = new ArrayList<>(limit);
        // Folder window for this page.
        int folderFrom = Math.min(offset, folderCount);
        int folderTo = Math.min(offset + limit, folderCount);
        combined.addAll(folders.subList(folderFrom, folderTo));
        // File rows fill the rest of the page. The slice was fetched at the right offset already.
        int remaining = limit - combined.size();
        if (remaining > 0) {
            List<StorageExplorerDto> fileDtos = fileSlice.rows().stream()
                    .map(p -> StorageExplorerDto.from(p, null))
                    .toList();
            combined.addAll(fileDtos.subList(0, Math.min(remaining, fileDtos.size())));
        }
        return new PageImpl<>(combined, pageable, total);
    }

    /**
     * ROOT composition: EVERY folder on the FIRST page, then the loose files paginated page-aligned
     * after them. Unlike {@link #headerBody} (which paginates the folders as the page header, so they
     * spill onto later pages), this never splits the folders across pages - a user with many workflow
     * folders sees them ALL at once. The file slice is fetched page-aligned ({@code fileOffset =
     * pageable.getOffset()}, {@code fileLimit = pageSize}), so the pager counts the FILES only
     * ({@code totalElements = fileTotal}); the folders always ride along on page 1. A folders-only
     * listing (no files) is a single page; page 1 carries the folders + the first file page, and
     * later pages carry only the subsequent file pages.
     */
    private static Page<StorageExplorerDto> foldersOnFirstPage(List<StorageExplorerDto> folders,
                                                               StorageExplorerRepository.SliceResult fileSlice,
                                                               Pageable pageable) {
        boolean firstPage = pageable.getOffset() == 0;
        long fileTotal = fileSlice.total();
        List<StorageExplorerDto> combined = new ArrayList<>();
        if (firstPage) {
            combined.addAll(folders); // every folder, on the first page only
        }
        fileSlice.rows().forEach(p -> combined.add(StorageExplorerDto.from(p, null)));

        // When every loose file already fits on page 1 (fileTotal <= pageSize) the listing is a SINGLE
        // page of folders + all files. Size the page to its content so Spring's PageImpl "last page"
        // heuristic (which would recompute total = offset + content.size()) can't invent a phantom
        // page 2 - the bug a user with many folders would otherwise hit when paging. Otherwise the
        // files paginate normally (folders enlarge page 1 only); total = fileTotal drives totalPages.
        if (firstPage && fileTotal <= pageable.getPageSize()) {
            int size = Math.max(1, combined.size());
            return new PageImpl<>(combined, PageRequest.of(0, size), combined.size());
        }
        return new PageImpl<>(combined, pageable, fileTotal);
    }

    /**
     * Page-size budget for the file query when {@code folderCount} folders precede the files. If the
     * page's offset hasn't reached the files yet, the full page is available to files; if the page
     * starts mid-folders, only the leftover slots after the folder window go to files. Always ≥0 and
     * never larger than the page size, so the file query never over-fetches.
     */
    private static int remainingFileLimit(int folderCount, Pageable pageable) {
        int offset = (int) pageable.getOffset();
        int limit = pageable.getPageSize();
        int foldersOnThisPage = Math.max(0, Math.min(offset + limit, folderCount) - Math.min(offset, folderCount));
        return Math.max(0, limit - foldersOnThisPage);
    }

    /**
     * Every manual folder in the org (flat list, for the Files "Move to…" tree picker). Org-scoped,
     * ordered by name. {@code childCount}/{@code previewFiles} are NOT needed for the picker (it
     * only renders the folder names/hierarchy), so they are left null. {@code excludedIds} drops the
     * folders being moved + their descendants (and folds in the member restricted-id deny-list) so an
     * invalid destination is never offered. See {@link StorageExplorerRepository#listAllManualFolders}.
     */
    public List<StorageExplorerDto> listAllManualFolders(String tenantId, String organizationId,
                                                         Collection<UUID> excludedIds) {
        return explorerRepository.listAllManualFolders(organizationId, excludedIds).stream()
                .map(p -> StorageExplorerDto.from(p, null))
                .toList();
    }

    /**
     * Offset-based, files-only slice for the agent-facing {@code files} tool.
     * Delegates to {@link StorageExplorerRepository#searchSlice} and maps to DTOs.
     * Strict org-scope (same rule as {@link #search}); {@code tenantId} is kept
     * for call-site symmetry/logging but org membership is the authoritative scope.
     */
    public FilesSlice searchFilesSlice(
            String tenantId, String organizationId, String search, String sourceType,
            String storageType, String workflowId, String runId, Instant dateFrom, Instant dateTo,
            boolean filesOnly, int limit, int offset) {
        return searchFilesSlice(tenantId, organizationId, search, sourceType, storageType,
                workflowId, runId, dateFrom, dateTo, filesOnly, List.of(), limit, offset);
    }

    public FilesSlice searchFilesSlice(
            String tenantId, String organizationId, String search, String sourceType,
            String storageType, String workflowId, String runId, Instant dateFrom, Instant dateTo,
            boolean filesOnly, Collection<UUID> excludedIds, int limit, int offset) {

        StorageExplorerRepository.SliceResult slice = explorerRepository.searchSlice(
                organizationId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, excludedIds, limit, offset);

        List<StorageExplorerDto> files = slice.rows().stream()
                .map(p -> StorageExplorerDto.from(p, null))
                .toList();
        return new FilesSlice(files, slice.total());
    }

    /** Files slice + total matching count (agent files tool, offset pagination). */
    public record FilesSlice(List<StorageExplorerDto> files, long total) {}

    /**
     * Strict org-scope aggregate stats. Same scope rule as {@link #search}.
     */
    public List<Map<String, Object>> getStats(String tenantId, String organizationId) {
        return getStats(tenantId, organizationId, List.of());
    }

    public List<Map<String, Object>> getStats(String tenantId, String organizationId,
                                              Collection<UUID> excludedIds) {
        return explorerRepository.getStats(organizationId, excludedIds);
    }
}
