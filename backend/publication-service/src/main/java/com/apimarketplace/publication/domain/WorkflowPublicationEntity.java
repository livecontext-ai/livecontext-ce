package com.apimarketplace.publication.domain;

import com.apimarketplace.publication.utils.WorkflowNodeTypeExtractor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_publications")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WorkflowPublicationEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_type", nullable = false)
    private PublicationType publicationType = PublicationType.WORKFLOW;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Denormalized search index - concatenated lowercase tokens from title,
     * description, category, publisher, plus extracted nested content
     * (interface titles, agent roles, table names, …). Built at publish time
     * by {@code SearchTextBuilder} and queried via ILIKE in
     * {@code WorkflowPublicationRepository.searchMarketplace}.
     */
    @Column(name = "search_text", columnDefinition = "TEXT", nullable = false)
    private String searchText = "";

    @Column(name = "showcase_interface_id")
    private UUID showcaseInterfaceId;

    @Column(name = "showcase_run_id")
    private String showcaseRunId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "category_slug", length = 100)
    private String categorySlug;

    /**
     * True when this publication belongs in the Studio: it PRODUCES a media asset rather than
     * finding, publishing or reading one.
     *
     * <p>A SECOND AXIS, independent of {@link #categorySlug}. Modelled as another category first,
     * which was wrong: the category column is single-valued, so a video studio would have had to
     * stop being a Content app to become a studio one.
     */
    @Column(name = "studio", nullable = false)
    private boolean studio = false;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "category_icon_slug", length = 100)
    private String categoryIconSlug;

    @Column(name = "category_color", length = 50)
    private String categoryColor;

    @Column(name = "agent_config_id")
    private UUID agentConfigId;

    /**
     * Generic reference to a published standalone resource (TABLE, INTERFACE, SKILL).
     * Stored as a string to accommodate both UUID (interface, skill) and numeric IDs (datasource).
     * Null for WORKFLOW and AGENT publications, which use workflowId / agentConfigId respectively.
     */
    @Column(name = "resource_id")
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> planSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> agentSnapshot;

    /**
     * Frozen view of the publisher's source run captured at publish time.
     * Holds run state, aggregated steps, epoch signals, epoch timestamps,
     * and per-interface pre-rendered templates + items so the marketplace
     * preview can read everything from this column without ever calling
     * the orchestrator. Schema documented in
     * {@code PublicationShowcaseSnapshot} (publication-service).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "showcase_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> showcaseSnapshot;

    @Column(name = "showcase_snapshot_captured_at")
    private Instant showcaseSnapshotCapturedAt;

    /**
     * V273 - publisher's chosen epoch for the marketplace preview. When
     * non-null, {@code ShowcaseSnapshotReader} filters items[] +
     * aggregatedSteps to this single epoch so visitors see exactly one
     * canonical demo instead of paginating across every captured run.
     * Null on legacy publications (kept for backward-compat) and on new
     * publications where the publisher hasn't picked yet - the reader
     * falls back to the multi-epoch view in that case.
     */
    @Column(name = "showcase_chosen_epoch")
    private Integer showcaseChosenEpoch;

    @Column(name = "plan_version")
    private Integer planVersion;

    @Column(name = "snapshot_version")
    private Integer snapshotVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_icons", columnDefinition = "jsonb")
    private List<Map<String, Object>> nodeIcons;

    /**
     * Denormalized node-type tokens of {@link #planSnapshot} ({@code mcp:gmail},
     * {@code core:loop}, ...), so the applications list can filter by node type.
     *
     * <p>This column is what makes the filter possible at all here: the list
     * queries deliberately never select {@code plan_snapshot} (~200KB a row), so
     * without it there is no way to know a publication's node types at list time.
     *
     * <p>Derived, never set by callers - see {@link #setPlanSnapshot(Map)}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_types", columnDefinition = "jsonb", nullable = false)
    private List<String> nodeTypes = List.of();

    /**
     * True when the snapshot uses a feature that only exists on a self-hosted
     * install (today: local-CLI agents. Vector search is LABELLED in ce_exclusive_features but does not set this flag, being plan-gated at acquire instead). Recomputed from the
     * snapshot on every publish and update by
     * {@code CeExclusiveFeatureDetector} - never set by the publisher - and
     * enforced at acquisition time on managed cloud.
     */
    @Column(name = "ce_exclusive", nullable = false)
    private Boolean ceExclusive = false;

    /** Detected feature codes backing {@link #ceExclusive} (drives the badge tooltip). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ce_exclusive_features", columnDefinition = "jsonb")
    private List<String> ceExclusiveFeatures = new ArrayList<>();

    @Column(name = "credits_per_use", nullable = false)
    private Integer creditsPerUse = 0;

    @Column(name = "publisher_id", nullable = false)
    private String publisherId;

    /**
     * V223 / #151 - ownership discriminator: USER (personal workspace) or ORG
     * (team workspace). Pairs with {@link #ownerId}. Lives next to
     * {@link #publisherId}, which stays the audit field (human who clicked
     * Publish, always a user id used as audit metadata, even for ORG-owned
     * publications). The pair {@code (ownerType, ownerId)} is the source of
     * truth for list/mutation scoping; {@code publisherId} is informational
     * only. Defaults to {@code USER} so existing rows from before this column
     * existed default to personal scope.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    private OwnerType ownerType = OwnerType.USER;

    /**
     * Opaque scope id paired with {@link #ownerType}: user_id when
     * ownerType=USER (equal to {@link #publisherId}), organization_id when
     * ownerType=ORG. Used by "my publications" list endpoints + scope-aware
     * mutation guards.
     */
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "publisher_name")
    private String publisherName;

    @Column(name = "publisher_email")
    private String publisherEmail;

    @Column(name = "publisher_avatar_url", columnDefinition = "TEXT")
    private String publisherAvatarUrl;

    /**
     * V413 - the publisher's public handle, frozen at publish time next to
     * {@link #publisherName} / {@link #publisherAvatarUrl}. Lets a public page
     * link to {@code /u/{handle}} without one auth-service round-trip per card.
     * Null for publishers who have no handle yet.
     */
    @Column(name = "publisher_handle", length = 32)
    private String publisherHandle;

    /**
     * V413 - URL slug backing the crawlable public page
     * {@code /marketplace/{publicSlug}}.
     *
     * <p>Assigned once, at first publish, and deliberately NOT recomputed when the
     * title changes: an indexed URL that moves loses its ranking and breaks every
     * shared link. Null on rows created before V413 until the backfill runs; those
     * stay reachable by UUID.
     */
    @Column(name = "public_slug", length = 120)
    private String publicSlug;

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PublicationStatus status = PublicationStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private PublicationVisibility visibility = PublicationVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_mode", nullable = false)
    private DisplayMode displayMode = DisplayMode.WORKFLOW;

    @Column(name = "agent_count", nullable = false)
    private Integer agentCount = 0;

    @Column(name = "skill_count", nullable = false)
    private Integer skillCount = 0;

    @Column(name = "interface_count", nullable = false)
    private Integer interfaceCount = 0;

    @Column(name = "datasource_count", nullable = false)
    private Integer datasourceCount = 0;

    @Column(name = "workflow_count", nullable = false)
    private Integer workflowCount = 0;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 0;

    @Column(name = "total_credits_earned", nullable = false)
    private Integer totalCreditsEarned = 0;

    @Column(name = "average_rating", nullable = false)
    private Double averageRating = 0.0;

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount = 0;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "reviewer_id")
    private String reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    public enum PublicationType { WORKFLOW, AGENT, TABLE, INTERFACE, SKILL }
    public enum PublicationStatus { ACTIVE, INACTIVE, PENDING_REVIEW, REJECTED }
    public enum PublicationVisibility { PUBLIC, PRIVATE, UNLISTED }
    // WORKFLOW..SKILL are real publication types (one per resource strategy).
    // LANDING is NOT a publication type - no resource strategy ever emits it, so
    // no publication is created with displayMode=LANDING. It exists only as a
    // highlight-bucket key for publication_highlights (the curated row that drives
    // the public landing page). Its bucket holds APPLICATION-type publications -
    // see PublicationHighlightService.requiredPublicationMode.
    /**
     * Publication type, and highlight-bucket key.
     *
     * <p>The first six are real types, one per resource strategy. Everything from LANDING on
     * is a BUCKET only: no publication is ever of that type, and each holds APPLICATION
     * publications (see PublicationHighlightService.requiredPublicationMode). LANDING drives
     * the home page's curated row; the six LANDING_* keys drive one /for/&lt;persona&gt; page
     * each, so a persona can show its own apps. The list must match the
     * pub_highlights_displaymode_check constraint (V347, extended by V490).
     */
    public enum DisplayMode {
        WORKFLOW, INTERFACE, APPLICATION, AGENT, TABLE, SKILL,
        LANDING,
        LANDING_OPS, LANDING_CREATOR, LANDING_SUPPORT, LANDING_SALES, LANDING_MARKETING, LANDING_RECRUITING
    }
    /** V223 / #151 - publication ownership scope: USER (personal) or ORG (team workspace). */
    public enum OwnerType { USER, ORG }

    public WorkflowPublicationEntity() {
        this.publishedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public WorkflowPublicationEntity(UUID workflowId, String title, Map<String, Object> planSnapshot, String publisherId) {
        this();
        this.workflowId = workflowId;
        this.title = title;
        this.planSnapshot = planSnapshot;
        this.publisherId = publisherId;
        // V223 - legacy 4-arg constructor defaults to USER scope keyed on the
        // publisher_id. New code paths call assignOwnerFromContext(userId, orgId)
        // explicitly after construction.
        this.ownerType = OwnerType.USER;
        this.ownerId = publisherId;
    }

    /**
     * The id, assigning one if the entity does not have it yet.
     *
     * <p>Exists because the id is the publication's storage namespace
     * ({@code _publications/{id}/}), and the publish flow needs that namespace BEFORE the
     * first save: it copies the plan's files while enriching the snapshot. With the id
     * assigned only by {@code @PrePersist}, those copy passes read {@code getId() == null}
     * and returned at their first line, so on a FIRST publish no data-input file and no
     * plan-embedded FileRef was ever copied - silently, and only on the path where it
     * mattered most.
     *
     * <p>Safe to call early: Spring Data decides insert-vs-update from the {@code @Version}
     * field (a non-primitive {@code Long}, still null here), not from the id, so
     * {@code save} still persists. Even if it merged, a merge of an absent row is an insert
     * after one extra select.
     */
    public UUID ensureId() {
        if (this.id == null) this.id = UUID.randomUUID();
        return this.id;
    }

    @PrePersist
    private void ensureIdentifiers() {
        ensureId();
        if (this.publishedAt == null) this.publishedAt = Instant.now();
        if (this.updatedAt == null) this.updatedAt = this.publishedAt;
        // V223 / #151 - defensive default: if owner_type / owner_id are unset
        // at persist time, fall back to USER scope keyed on publisher_id. NOT
        // NULL DB constraints would otherwise reject the INSERT silently. Old
        // call sites that pre-date assignOwnerFromContext() keep working.
        if (this.ownerType == null) {
            this.ownerType = OwnerType.USER;
        }
        if (this.ownerId == null || this.ownerId.isBlank()) {
            this.ownerId = this.publisherId;
        }
    }

    /**
     * Assign the owning scope from publish-time context. Pass a non-blank
     * {@code organizationId} to mark the publication as org-owned; pass
     * {@code null} or blank to keep it personal (USER-owned by the caller).
     *
     * <p>{@link #publisherId} is set independently - it always identifies
     * the human who clicked Publish, even when the owning scope is an org.
     */
    public void assignOwnerFromContext(String userId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            this.ownerType = OwnerType.ORG;
            this.ownerId = organizationId;
        } else {
            this.ownerType = OwnerType.USER;
            this.ownerId = userId;
        }
    }

    public boolean hasAssignedOwnerScope() {
        return this.ownerType != null && this.ownerId != null && !this.ownerId.isBlank();
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    public boolean hasShowcase() {
        return showcaseInterfaceId != null && showcaseRunId != null && !showcaseRunId.isEmpty();
    }

    public boolean isApplication() {
        return showcaseInterfaceId != null;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PublicationType getPublicationType() {
        return publicationType;
    }

    public void setPublicationType(PublicationType publicationType) {
        this.publicationType = publicationType;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public UUID getAgentConfigId() {
        return agentConfigId;
    }

    public void setAgentConfigId(UUID agentConfigId) {
        this.agentConfigId = agentConfigId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Map<String, Object> getAgentSnapshot() {
        return agentSnapshot;
    }

    public void setAgentSnapshot(Map<String, Object> agentSnapshot) {
        this.agentSnapshot = agentSnapshot;
    }

    public Integer getAgentCount() {
        return agentCount;
    }

    public void setAgentCount(Integer agentCount) {
        this.agentCount = agentCount;
    }

    public Integer getSkillCount() {
        return skillCount;
    }

    public void setSkillCount(Integer skillCount) {
        this.skillCount = skillCount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText != null ? searchText : "";
    }

    public UUID getShowcaseInterfaceId() {
        return showcaseInterfaceId;
    }

    public void setShowcaseInterfaceId(UUID showcaseInterfaceId) {
        this.showcaseInterfaceId = showcaseInterfaceId;
    }

    public String getShowcaseRunId() {
        return showcaseRunId;
    }

    public void setShowcaseRunId(String showcaseRunId) {
        this.showcaseRunId = showcaseRunId;
    }

    public boolean isStudio() {
        return studio;
    }

    public void setStudio(boolean studio) {
        this.studio = studio;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategorySlug() {
        return categorySlug;
    }

    public void setCategorySlug(String categorySlug) {
        this.categorySlug = categorySlug;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryIconSlug() {
        return categoryIconSlug;
    }

    public void setCategoryIconSlug(String categoryIconSlug) {
        this.categoryIconSlug = categoryIconSlug;
    }

    public String getCategoryColor() {
        return categoryColor;
    }

    public void setCategoryColor(String categoryColor) {
        this.categoryColor = categoryColor;
    }

    public Map<String, Object> getPlanSnapshot() {
        return planSnapshot;
    }

    /**
     * Sets the plan snapshot AND the node-type tokens derived from it, so the two
     * can never disagree.
     *
     * <p>Derived here rather than in a {@code @PreUpdate} callback on purpose.
     * Hibernate computes the dirty field set before invoking that callback, so a
     * column a callback writes is not reliably included in the UPDATE - and the
     * failure would be invisible: the publication would simply stop matching a
     * node-type filter it should match. Assigning both fields in the same setter
     * marks both dirty, which is guaranteed to persist. This entity uses FIELD
     * access ({@code @Id} is on the field), so Hibernate hydrates the columns
     * directly and never calls this setter on load - no recomputation on read.
     */
    public void setPlanSnapshot(Map<String, Object> planSnapshot) {
        this.planSnapshot = planSnapshot;
        this.nodeTypes = WorkflowNodeTypeExtractor.extractNodeTypes(planSnapshot);
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public Integer getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(Integer snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<Map<String, Object>> getNodeIcons() {
        return nodeIcons;
    }

    /**
     * Node-type tokens of the current plan snapshot. Never null; empty for a
     * plan with no nodes.
     *
     * <p>No derive-from-snapshot fallback here: the column is NOT NULL, was
     * backfilled for every existing row, and {@link #setPlanSnapshot(Map)} is
     * the only way to change the snapshot. A fallback would be code that can
     * never run, and it would quietly hide the day one of those three stops
     * being true.
     */
    public List<String> getNodeTypes() {
        return nodeTypes == null ? List.of() : nodeTypes;
    }

    public void setNodeIcons(List<Map<String, Object>> nodeIcons) {
        this.nodeIcons = nodeIcons;
    }

    public Integer getCreditsPerUse() {
        return creditsPerUse;
    }

    public void setCreditsPerUse(Integer creditsPerUse) {
        this.creditsPerUse = creditsPerUse;
    }

    public String getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(OwnerType ownerType) {
        this.ownerType = ownerType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public void setPublisherName(String publisherName) {
        this.publisherName = publisherName;
    }

    public String getPublisherEmail() {
        return publisherEmail;
    }

    public void setPublisherEmail(String publisherEmail) {
        this.publisherEmail = publisherEmail;
    }

    public String getPublisherAvatarUrl() {
        return publisherAvatarUrl;
    }

    public void setPublisherAvatarUrl(String publisherAvatarUrl) {
        this.publisherAvatarUrl = publisherAvatarUrl;
    }

    public String getPublisherHandle() {
        return publisherHandle;
    }

    public void setPublisherHandle(String publisherHandle) {
        this.publisherHandle = publisherHandle;
    }

    public String getPublicSlug() {
        return publicSlug;
    }

    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
    }

    /** Never null for callers: a legacy row read before the backfill reads as false. */
    public boolean isCeExclusive() {
        return Boolean.TRUE.equals(ceExclusive);
    }

    public void setCeExclusive(boolean ceExclusive) {
        this.ceExclusive = ceExclusive;
    }

    public List<String> getCeExclusiveFeatures() {
        return ceExclusiveFeatures == null ? List.of() : ceExclusiveFeatures;
    }

    public void setCeExclusiveFeatures(List<String> ceExclusiveFeatures) {
        this.ceExclusiveFeatures = ceExclusiveFeatures == null ? new ArrayList<>() : new ArrayList<>(ceExclusiveFeatures);
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public PublicationStatus getStatus() {
        return status;
    }

    public void setStatus(PublicationStatus status) {
        this.status = status;
    }

    public PublicationVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(PublicationVisibility visibility) {
        this.visibility = visibility;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode displayMode) {
        this.displayMode = displayMode;
    }

    public Integer getInterfaceCount() {
        return interfaceCount;
    }

    public void setInterfaceCount(Integer interfaceCount) {
        this.interfaceCount = interfaceCount;
    }

    public Integer getDatasourceCount() {
        return datasourceCount;
    }

    public void setDatasourceCount(Integer datasourceCount) {
        this.datasourceCount = datasourceCount;
    }

    public Integer getWorkflowCount() {
        return workflowCount;
    }

    public void setWorkflowCount(Integer workflowCount) {
        this.workflowCount = workflowCount;
    }

    public Integer getUseCount() {
        return useCount;
    }

    public void setUseCount(Integer useCount) {
        this.useCount = useCount;
    }

    public Integer getTotalCreditsEarned() {
        return totalCreditsEarned;
    }

    public void setTotalCreditsEarned(Integer totalCreditsEarned) {
        this.totalCreditsEarned = totalCreditsEarned;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(Double averageRating) {
        this.averageRating = averageRating;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Map<String, Object> getShowcaseSnapshot() {
        return showcaseSnapshot;
    }

    public void setShowcaseSnapshot(Map<String, Object> showcaseSnapshot) {
        this.showcaseSnapshot = showcaseSnapshot;
    }

    public Instant getShowcaseSnapshotCapturedAt() {
        return showcaseSnapshotCapturedAt;
    }

    public void setShowcaseSnapshotCapturedAt(Instant showcaseSnapshotCapturedAt) {
        this.showcaseSnapshotCapturedAt = showcaseSnapshotCapturedAt;
    }

    public Integer getShowcaseChosenEpoch() {
        return showcaseChosenEpoch;
    }

    public void setShowcaseChosenEpoch(Integer showcaseChosenEpoch) {
        this.showcaseChosenEpoch = showcaseChosenEpoch;
    }

}
