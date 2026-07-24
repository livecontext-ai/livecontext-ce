package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.ProjectDataSourcesPreviewDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.domain.*;
import com.apimarketplace.orchestrator.repository.*;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Business logic for project management.
 * Handles CRUD and resource assignment. Access control is org-based via OrgAccessGuard.
 */
@Service
public class ProjectService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final OrgAccessGuard orgAccessService;
    private final WorkflowRepository workflowRepository;
    private final InterfaceClient interfaceClient;
    private final AgentClient agentClient;
    private final DataSourceClient dataSourceClient;
    private final PublicationClient publicationClient;
    private final StorageService storageService;

    public ProjectService(ProjectRepository projectRepository,
                          OrgAccessGuard orgAccessService,
                          WorkflowRepository workflowRepository,
                          InterfaceClient interfaceClient,
                          AgentClient agentClient,
                          DataSourceClient dataSourceClient,
                          PublicationClient publicationClient,
                          StorageService storageService) {
        this.projectRepository = projectRepository;
        this.orgAccessService = orgAccessService;
        this.workflowRepository = workflowRepository;
        this.interfaceClient = interfaceClient;
        this.agentClient = agentClient;
        this.dataSourceClient = dataSourceClient;
        this.publicationClient = publicationClient;
        this.storageService = storageService;
    }

    // ===================== CRUD =====================

    @Transactional
    public ProjectEntity createProject(String userId, String name, String description, String color, String icon, String organizationId) {
        String slug = generateUniqueSlug(userId, name);

        ProjectEntity project = new ProjectEntity(name, slug, userId);
        project.setDescription(description);
        if (color != null) project.setColor(color);
        if (icon != null) project.setIcon(icon);
        if (organizationId != null) project.setOrganizationId(organizationId);

        project = projectRepository.save(project);
        logger.info("Project created: {} (id={}) by user {}", name, project.getId(), userId);
        return project;
    }

    public Optional<ProjectEntity> getProject(UUID projectId, String userId) {
        return getProject(projectId, userId, null, null);
    }

    public Optional<ProjectEntity> getProject(UUID projectId, String userId, String orgId, String orgRole) {
        // 2026-05-18 - strict-isolation via ScopeGuard: the active workspace
        // (org or default personal org) matches only rows tagged with that
        // org id (then canAccess deny-list). Post-V261 (2026-05-19) the
        // gateway always injects X-Organization-ID, so the personal-strict
        // ({@code organizationId IS NULL}) branch of {@link ScopeGuard} is
        // unreachable for normal traffic. The prior owner-OR-org-AND-canAccess
        // shape was the lax || bug shape.
        return projectRepository.findById(projectId)
            .filter(p -> ScopeGuard.isInStrictScope(userId, orgId,
                    p.getOwnerId(), p.getOrganizationId()))
            .filter(p -> {
                if (orgId != null && !orgId.isBlank()) {
                    return orgAccessService.canAccess(orgId, userId, "project",
                            p.getId().toString(), orgRole);
                }
                return true;
            });
    }

    public List<ProjectEntity> getUserProjects(String userId) {
        return getUserProjects(userId, null, null);
    }

    public List<ProjectEntity> getUserProjects(String userId, String orgId, String orgRole) {
        if (orgId != null && !orgId.isBlank()) {
            List<ProjectEntity> projects = projectRepository.findByOrganizationOrOwner(orgId, userId);
            return orgAccessService.filterAccessible(projects, orgId, userId, "project", orgRole,
                    p -> p.getId().toString());
        }

        // No org context - return owned projects only
        return projectRepository.findByOwnerIdAndIsArchivedFalseOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public Optional<ProjectEntity> updateProject(UUID projectId, String userId, Map<String, Object> updates) {
        return updateProject(projectId, userId, null, null, updates);
    }

    /**
     * Workspace-scoped overload (2026-05-18). Uses {@link #getProject} which
     * enforces strict-isolation via ScopeGuard; in org workspace an org admin
     * with the right canAccess can update a teammate's project, and a personal
     * caller cannot accidentally hit this endpoint on their own org project.
     */
    @Transactional
    public Optional<ProjectEntity> updateProject(UUID projectId, String userId, String orgId,
                                                  String orgRole, Map<String, Object> updates) {
        // Separate genuine not-found/out-of-scope (Optional.empty → 404, no info leak)
        // from a per-resource WRITE restriction on a project the member CAN read
        // (→ 403 via OrgAccessDeniedException), matching workflow/datasource/agent/interface.
        Optional<ProjectEntity> visible = getProject(projectId, userId, orgId, orgRole);
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        ProjectEntity project = visible.get();
        if (!canWriteResource(orgId, userId, "project", project.getId().toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from updating project {} in org {}",
                    userId, projectId, orgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "project", project.getId().toString());
        }
        if (updates.containsKey("name")) {
            String newName = (String) updates.get("name");
            project.setName(newName);
            project.setSlug(generateUniqueSlug(project.getOwnerId(), newName));
        }
        if (updates.containsKey("description")) project.setDescription((String) updates.get("description"));
        if (updates.containsKey("color")) project.setColor((String) updates.get("color"));
        if (updates.containsKey("icon")) project.setIcon((String) updates.get("icon"));
        if (updates.containsKey("isArchived")) project.setIsArchived((Boolean) updates.get("isArchived"));
        return Optional.of(projectRepository.save(project));
    }

    @Transactional
    public boolean deleteProject(UUID projectId, String userId) {
        return deleteProject(projectId, userId, null, null);
    }

    /**
     * Workspace-scoped overload (2026-05-18). See {@link #updateProject}.
     */
    @Transactional
    public boolean deleteProject(UUID projectId, String userId, String orgId, String orgRole) {
        return getProject(projectId, userId, orgId, orgRole)
            .filter(project -> canWriteResource(orgId, userId, "project", project.getId().toString(), orgRole))
            .map(project -> {
                unassignAllResources(projectId, userId, orgId);
                projectRepository.delete(project);
                logger.info("Project deleted: {} by user {} (org {})", projectId, userId, orgId);
                return true;
            })
            .orElse(false);
    }

    // ===================== Resource Assignment =====================

    @Transactional
    public boolean assignResource(UUID projectId, String resourceType, String resourceId, String userId) {
        return assignResource(projectId, resourceType, resourceId, userId, null, null);
    }

    /**
     * Workspace-scoped overload (2026-05-18). Uses {@link #getProject} for
     * visibility instead of strict-tenant ownership.
     */
    @Transactional
    public boolean assignResource(UUID projectId, String resourceType, String resourceId,
                                   String userId, String orgId, String orgRole) {
        // Genuine not-found/out-of-scope stays false (→ 400/404, no info leak); a
        // per-resource WRITE restriction on a VISIBLE project becomes a clean 403.
        if (getProject(projectId, userId, orgId, orgRole).isEmpty()) return false;
        if (!canWriteResource(orgId, userId, "project", projectId.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from assigning to project {} in org {}",
                    userId, projectId, orgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "project", projectId.toString());
        }
        if (!canWriteResource(orgId, userId, resourceType, resourceId, orgRole)) return false;

        return switch (resourceType.toLowerCase()) {
            case "workflow" -> assignWorkflow(projectId, UUID.fromString(resourceId), userId, orgId);
            case "interface" -> assignInterface(projectId, UUID.fromString(resourceId), userId);
            case "datasource", "table" -> assignDataSource(projectId, Long.parseLong(resourceId), userId, orgId);
            case "agent" -> assignAgent(projectId, UUID.fromString(resourceId), userId);
            case "application" -> assignPublication(projectId, UUID.fromString(resourceId), userId);
            case "file" -> assignFile(projectId, UUID.fromString(resourceId), userId, orgId, orgRole);
            default -> false;
        };
    }

    @Transactional
    public boolean removeResource(UUID projectId, String resourceType, String resourceId, String userId) {
        return removeResource(projectId, resourceType, resourceId, userId, null, null);
    }

    /**
     * Workspace-scoped overload (2026-05-18). See {@link #assignResource}.
     */
    @Transactional
    public boolean removeResource(UUID projectId, String resourceType, String resourceId,
                                   String userId, String orgId, String orgRole) {
        // Genuine not-found/out-of-scope stays false (→ 400/404, no info leak); a
        // per-resource WRITE restriction on a VISIBLE project becomes a clean 403.
        if (getProject(projectId, userId, orgId, orgRole).isEmpty()) return false;
        if (!canWriteResource(orgId, userId, "project", projectId.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from removing from project {} in org {}",
                    userId, projectId, orgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "project", projectId.toString());
        }
        if (!canWriteResource(orgId, userId, resourceType, resourceId, orgRole)) return false;

        return switch (resourceType.toLowerCase()) {
            case "workflow" -> {
                workflowRepository.findById(UUID.fromString(resourceId))
                    .filter(w -> projectId.equals(w.getProjectId()))
                    .filter(w -> ScopeGuard.isInStrictScope(userId, orgId, w.getTenantId(), w.getOrganizationId()))
                    .ifPresent(w -> { w.setProjectId(null); workflowRepository.save(w); });
                yield true;
            }
            case "interface" -> {
                interfaceClient.removeFromProject(UUID.fromString(resourceId), projectId, userId);
                yield true;
            }
            case "agent" -> {
                agentClient.removeFromProject(UUID.fromString(resourceId), projectId, userId);
                yield true;
            }
            case "datasource", "table" -> {
                dataSourceClient.updateProjectId(Long.parseLong(resourceId), null, userId);
                yield true;
            }
            case "application" -> {
                publicationClient.removeFromProject(UUID.fromString(resourceId), projectId, userId);
                yield true;
            }
            case "file" -> {
                yield removeFile(projectId, UUID.fromString(resourceId), userId, orgId, orgRole);
            }
            default -> false;
        };
    }

    /**
     * Returns resource counts for a project grouped by type.
     */
    public Map<String, Long> getResourceCounts(UUID projectId, String tenantId) {
        return getResourceCounts(projectId, tenantId, null);
    }

    public Map<String, Long> getResourceCounts(UUID projectId, String tenantId, String orgId) {
        return getResourceCounts(projectId, tenantId, orgId, null);
    }

    public Map<String, Long> getResourceCounts(UUID projectId, String tenantId, String orgId, String orgRole) {
        if (hasOrg(orgId)) {
            Map<String, Object> resources = getProjectResources(projectId, tenantId, orgId, orgRole);
            Map<String, Long> filteredCounts = new LinkedHashMap<>();
            filteredCounts.put("workflows", collectionSize(resources.get("workflows")));
            filteredCounts.put("interfaces", collectionSize(resources.get("interfaces")));
            filteredCounts.put("agents", collectionSize(resources.get("agents")));
            filteredCounts.put("datasources", collectionSize(resources.get("datasources")));
            filteredCounts.put("applications", collectionSize(resources.get("applications")));
            filteredCounts.put("files", collectionSize(resources.get("files")));
            return filteredCounts;
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("workflows", workflowRepository.countByProjectId(projectId));
        counts.put("interfaces", interfaceClient.countByProject(projectId, tenantId));
        counts.put("agents", agentClient.countByProjectId(projectId, tenantId));
        counts.put("datasources", (long) dataSourceClient.countByProjectId(projectId, tenantId));
        counts.put("applications", publicationClient.countByProjectId(projectId, tenantId));
        counts.put("files", 0L);
        return counts;
    }

    /**
     * Returns all resources for a project, grouped by type.
     * Interfaces are converted to Maps to force LOB field reads within the transaction
     * (PostgreSQL LOBs cannot be accessed after transaction commit).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getProjectResources(UUID projectId, String tenantId) {
        return getProjectResources(projectId, tenantId, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProjectResources(UUID projectId, String tenantId, String orgId) {
        return getProjectResources(projectId, tenantId, orgId, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProjectResources(UUID projectId, String tenantId, String orgId, String orgRole) {
        Map<String, Object> resources = new LinkedHashMap<>();
        List<WorkflowEntity> workflows = hasOrg(orgId)
                ? workflowRepository.findByProjectIdAndOrganizationId(projectId, orgId)
                : workflowRepository.findByProjectId(projectId);
        List<Map<String, Object>> interfaces = interfaceClient.getByProject(projectId, tenantId).stream()
                .map(this::toInterfaceMap).toList();
        List<AgentDto> agents = agentClient.findByProjectId(projectId, tenantId);
        // Datasources WITH the row-count + sample-row preview so the Tables tab renders the
        // identical mini-table card as /app/tables (the preview maps are keyed by id-as-string).
        ProjectDataSourcesPreviewDto dsPreview = dataSourceClient.findByProjectIdWithPreview(projectId, tenantId);
        List<DataSourceDto> datasources = dsPreview.items();
        List<Map<String, Object>> applications = publicationClient.findByProjectId(projectId, tenantId);
        List<Map<String, Object>> files = hasOrg(orgId)
                ? storageService.findFilesByProjectForScope(projectId, orgId).stream().map(this::toFileMap).toList()
                : List.of();

        if (hasOrg(orgId)) {
            workflows = orgAccessService.filterAccessible(workflows, orgId, tenantId, "workflow", orgRole,
                    workflow -> workflow.getId().toString());
            interfaces = orgAccessService.filterAccessible(interfaces, orgId, tenantId, "interface", orgRole,
                    resource -> String.valueOf(resource.get("id")));
            agents = orgAccessService.filterAccessible(agents, orgId, tenantId, "agent", orgRole,
                    agent -> agent.getId().toString());
            datasources = orgAccessService.filterAccessible(datasources, orgId, tenantId, "datasource", orgRole,
                    dataSource -> String.valueOf(dataSource.id()));
            applications = orgAccessService.filterAccessible(applications, orgId, tenantId, "application", orgRole,
                    application -> String.valueOf(application.get("id")));
            files = orgAccessService.filterAccessible(files, orgId, tenantId, "file", orgRole,
                    resource -> String.valueOf(resource.get("id")));
        }

        resources.put("workflows", workflows);
        resources.put("interfaces", interfaces);
        resources.put("agents", agents);
        resources.put("datasources", datasources);
        // Preview maps (id-as-string → count / sample rows) feed each Tables-tab card's mini-table.
        // Kept whole even after the datasource org-filter above: the frontend looks them up by id,
        // so any entry for a filtered-out table is simply never read.
        resources.put("datasourceRowCounts", dsPreview.rowCounts());
        resources.put("datasourceSampleRows", dsPreview.sampleRows());
        resources.put("applications", applications);
        resources.put("files", files);
        return resources;
    }

    private long collectionSize(Object value) {
        return value instanceof Collection<?> collection ? collection.size() : 0L;
    }

    private Map<String, Object> toInterfaceMap(InterfaceDto e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("htmlTemplate", e.getHtmlTemplate());
        m.put("cssTemplate", e.getCssTemplate());
        m.put("jsTemplate", e.getJsTemplate());
        // The format travels with the templates: the project's interface cards size their
        // thumbnail from it, and without it they fall back to the 1280x800 default.
        m.put("format", e.getFormat());
        m.put("projectId", e.getProjectId());
        m.put("interfaceType", e.getInterfaceType());
        m.put("isPublic", e.getIsPublic());
        m.put("data", e.getData());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    // ===================== Private helpers =====================

    private String generateUniqueSlug(String ownerId, String name) {
        String base = LabelNormalizer.normalizeLabel(name);
        if (base.isEmpty()) base = "project";
        String slug = base;
        int counter = 1;
        while (projectRepository.existsByOwnerIdAndSlug(ownerId, slug)) {
            slug = base + "_" + counter++;
        }
        return slug;
    }

    private boolean assignWorkflow(UUID projectId, UUID workflowId, String userId, String orgId) {
        return workflowRepository.findById(workflowId)
            .filter(w -> ScopeGuard.isInStrictScope(userId, orgId, w.getTenantId(), w.getOrganizationId()))
            .map(w -> { w.setProjectId(projectId); workflowRepository.save(w); return true; })
            .orElse(false);
    }

    private boolean assignInterface(UUID projectId, UUID interfaceId, String userId) {
        return interfaceClient.assignToProject(interfaceId, projectId, userId);
    }

    private boolean assignDataSource(UUID projectId, Long dataSourceId, String userId, String orgId) {
        DataSourceDto ds = dataSourceClient.getDataSource(dataSourceId, userId);
        if (ds == null || !matchesWorkspace(userId, orgId, ds.tenantId(), ds.organizationId())) return false;
        dataSourceClient.updateProjectId(dataSourceId, projectId, userId);
        return true;
    }

    private boolean assignAgent(UUID projectId, UUID agentId, String userId) {
        return agentClient.assignToProject(agentId, projectId, userId);
    }

    private boolean assignPublication(UUID projectId, UUID publicationId, String userId) {
        return publicationClient.assignToProject(publicationId, projectId, userId);
    }

    private boolean canWriteResource(String orgId, String userId, String resourceType, String resourceId, String orgRole) {
        if (!hasOrg(orgId)) {
            return true;
        }
        String normalizedType = switch (resourceType.toLowerCase(Locale.ROOT)) {
            case "table" -> "datasource";
            default -> resourceType.toLowerCase(Locale.ROOT);
        };
        return orgAccessService.canWrite(orgId, userId, normalizedType, resourceId, orgRole);
    }

    private boolean assignFile(UUID projectId, UUID fileId, String userId, String orgId, String orgRole) {
        // Assigning a file to a project mutates the file - requires WRITE access
        // (a READ-only or fully-denied member cannot re-assign it).
        if (!hasOrg(orgId) || !orgAccessService.canWrite(orgId, userId, "file", fileId.toString(), orgRole)) {
            return false;
        }
        return storageService.assignFileToProjectForScope(fileId, orgId, projectId);
    }

    private boolean removeFile(UUID projectId, UUID fileId, String userId, String orgId, String orgRole) {
        if (!hasOrg(orgId) || !orgAccessService.canWrite(orgId, userId, "file", fileId.toString(), orgRole)) {
            return false;
        }
        return storageService.removeFileFromProjectForScope(fileId, orgId, projectId);
    }

    private Map<String, Object> toFileMap(StorageEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getFileName());
        m.put("fileName", e.getFileName());
        m.put("projectId", e.getProjectId());
        m.put("mimeType", e.getMimeType());
        m.put("contentType", e.getContentType());
        m.put("sizeBytes", e.getSizeBytes());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private void unassignAllResources(UUID projectId, String tenantId, String orgId) {
        List<WorkflowEntity> workflows = hasOrg(orgId)
                ? workflowRepository.findByProjectIdAndOrganizationId(projectId, orgId)
                : workflowRepository.findByProjectId(projectId);
        workflows.forEach(w -> {
            w.setProjectId(null);
            workflowRepository.save(w);
        });
        interfaceClient.unassignAllFromProject(projectId, tenantId);
        agentClient.unassignAllFromProject(projectId, tenantId);
        dataSourceClient.findByProjectId(projectId, tenantId).forEach(ds ->
            dataSourceClient.updateProjectId(ds.id(), null, tenantId)
        );
        publicationClient.unassignAllFromProject(projectId, tenantId);
        if (hasOrg(orgId)) {
            storageService.unassignFilesFromProjectForScope(projectId, orgId);
        }
    }

    private boolean matchesWorkspace(String userId, String orgId, String resourceTenantId, String resourceOrgId) {
        return ScopeGuard.isInStrictScope(userId, orgId, resourceTenantId, resourceOrgId);
    }

    private boolean hasOrg(String orgId) {
        return orgId != null && !orgId.isBlank();
    }
}
