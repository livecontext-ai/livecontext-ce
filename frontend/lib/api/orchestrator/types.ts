/**
 * Orchestrator API Types
 *
 * Centralized type definitions for all orchestrator-related entities.
 * Split from orchestrator-api.ts following Single Responsibility Principle.
 */

// ============================================
// Core Entity Types
// ============================================

/**
 * Map of trigger IDs to webhook tokens.
 * Used for multi-DAG webhook support where each trigger has its own URL.
 */
export type WebhookTokensMap = Record<string, string>;

/**
 * NodeIcon-compatible props stored in the backend node_icons JSONB column.
 * Can be spread directly into the NodeIcon component: <NodeIcon {...iconData} size="xs" />
 */
export interface NodeIconData {
  nodeId?: string;
  nodeKind?: string;
  iconSlug?: string;
  isMcp?: boolean;
  avatarUrl?: string;
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  tenantId?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  /** ISO timestamp of the workflow's most recent execution (null when never run). Drives the
   *  "Last executed" sort on the workflow list, mirroring the board's `lastExecutedAt`. */
  lastExecutedAt?: string | null;
  plan?: any;
  runCount?: number;
  /** Map of triggerId -> token for multi-DAG webhook support */
  webhookTokens?: WebhookTokensMap;
  /** Pre-computed NodeIcon props extracted from the plan */
  nodeIcons?: NodeIconData[];
  sourcePublicationId?: string;
  acquiredAt?: string;
  /** Whether this workflow has been published AND approved (status === 'ACTIVE') */
  isPublished?: boolean;
  /**
   * Moderation state of this workflow's publication when one exists and is
   * still shared. `null`/absent = not shared. Drives the list badge: an
   * orange "shared · in review" chip for PENDING_REVIEW, red for REJECTED,
   * the plain "shared" Globe for ACTIVE.
   */
  publicationStatus?: 'ACTIVE' | 'PENDING_REVIEW' | 'REJECTED' | null;
  projectId?: string | null;
  /** Workflow type: WORKFLOW (default) or APPLICATION */
  workflowType?: 'WORKFLOW' | 'APPLICATION';
  /** Immutable reference plan for APPLICATION workflows (used for reset) */
  basePlan?: any;
  /** Pinned production version - triggers use this version's plan when set */
  pinnedVersion?: number | null;
  /** Whether this workflow has an active run (RUNNING, PAUSED, or WAITING_TRIGGER) */
  hasActiveRun?: boolean;
  /** Board column classification: draft | production | needsReview | paused */
  boardColumn?: WorkflowBoardColumn;
}

// ============================================
// Workflow Board Types
// ============================================

export type WorkflowBoardColumn = 'draft' | 'production' | 'needsReview' | 'paused';

export interface WorkflowBoardCard {
  workflowId: string;
  name: string;
  description?: string;
  nodeIcons?: NodeIconData[];
  pinnedVersion?: number | null;
  productionRunId?: string | null;
  productionRunStatus?: string | null;
  /**
   * Number of epochs fired by the production run (count of EPOCH_HEADER rows
   * in `workflow_epochs`). Null when no production run exists or it has not
   * fired any epoch yet. Surfaced in the card footer with a calendar icon -
   * gives an at-a-glance view of how active a pinned workflow is in prod.
   */
  productionRunEpochCount?: number | null;
  lastExecutedAt?: string | null;
  updatedAt?: string | null;
  runCount: number;
  column: WorkflowBoardColumn;
  /**
   * Applications board: the publication this row resolves to, so the card opens the application
   * surface (`/app/applications/{sourcePublicationId}`) instead of the workflow builder. Set for
   * acquired APPLICATION-type rows and for the publisher's own published-as-application rows.
   * Null/absent for regular workflows (→ workflow route).
   */
  sourcePublicationId?: string | null;
  /**
   * Workflows board: whether the source workflow has an ACTIVE publication. Drives the same
   * "shared" marker as the `/app/workflow` list (`Workflow.isPublished`).
   */
  isPublished?: boolean;
  /**
   * Workflows board: full publication moderation state, so the card shows "shared" / "in review" /
   * "rejected" exactly like the `/app/workflow` list. Null when the workflow is not shared.
   */
  publicationStatus?: 'ACTIVE' | 'PENDING_REVIEW' | 'REJECTED' | null;
  /**
   * Applications board, own published-as-application rows only: the publication's showcase
   * interface + run, so the card previews via the authenticated per-run path (valid at any
   * visibility - the run is the caller's own), exactly like `/app/applications`. Absent for
   * acquired rows (cross-tenant → public showcase via `sourcePublicationId`) and workflows.
   */
  showcaseInterfaceId?: string | null;
  showcaseRunId?: string | null;
  /**
   * Marketplace visibility (PUBLIC / PRIVATE / UNLISTED) of the card's OWN publication - drives the
   * footer public / private indicator and the board's visibility filter, mirroring
   * `/app/applications` (`WorkflowPublication.visibility`). Null/absent for acquired rows (the
   * publisher's visibility, not the viewer's) and for unpublished workflows.
   */
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED' | null;
  /**
   * Applications board, ACQUIRED rows only: true when the source publication is a CLOUD id absent
   * from the local catalog (a cloud-sourced acquisition on a cloud-linked CE). The card then routes
   * its showcase render through the cloud proxy (`remote`) instead of the local render (which 404s
   * on a cloud-only pub id). False/absent for LOCAL acquired apps (rendered via the receipt-gated
   * authenticated showcase), own published apps and regular workflows. Mirrors `/app/applications`.
   */
  remote?: boolean | null;
}

export interface WorkflowBoardResponse {
  columns: Record<WorkflowBoardColumn, WorkflowBoardCard[]>;
  /** Total workflows for the tenant (across all pages). */
  totalCount: number;
  /** Zero-based page index. */
  page: number;
  /** Page size used by the server. */
  size: number;
}

export interface WorkflowsPage {
  workflows: Workflow[];
  count: number;
  totalCount: number;
  page: number;
  size: number;
}

/**
 * One workflow's batched Applications-card metadata from POST /workflows/applications/run-version-batch:
 * the application run id (live preview), its last-executed timestamp (execution sort) and the pinned
 * version (Live/Active badge). Any field may be null; a workflowId absent from the response map means
 * "load failed / no data" (the card hides the badge).
 */
export interface ApplicationRunVersionEntry {
  applicationRunId?: string | null;
  lastExecutedAt?: string | null;
  pinnedVersion?: number | null;
}

export interface WorkflowRun {
  id: string;
  runId: string;
  workflowId: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
  endedAt?: string;
  durationMs?: number;
  error?: string;
  totalNodes?: number;
  executionMode?: 'automatic' | 'step_by_step';
  planVersion?: number;
  /** Run metadata including lastCycleResult for reusable trigger workflows */
  metadata?: Record<string, any>;
  currentEpoch?: number;
  /**
   * started_at of the most recent epoch header for this run. For reusable
   * trigger runs whose `startedAt` is run birth (often days old), this is the
   * "last execution" timestamp the run history panel displays. Null when no
   * epoch has fired yet - caller falls back to `startedAt`.
   */
  lastFireAt?: string;
}

export interface WorkflowStep {
  id: string;
  runId: string;
  name: string;
  status: string;
  input?: any;
  output?: any;
  error?: string;
}

export interface PagedStepsResponse {
  content: WorkflowStep[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface DataSource {
  id: string;
  name: string;
  tenantId?: string;
  type?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
  created_at?: string;
  updated_at?: string;
  sourceWorkflowId?: string;
  source_workflow_id?: string;
  projectId?: string | null;
  project_id?: string | null;
  // Column metadata used by the table-card list. The backend serializes the
  // DataSource record with snake_case @JsonProperty names; camelCase variants
  // are kept for resilience to other producers. `type` per column is lowercase
  // (ColumnType @JsonValue, e.g. "text" | "vector").
  mapping_spec?: Record<string, { type?: string; [k: string]: unknown }>;
  mappingSpec?: Record<string, { type?: string; [k: string]: unknown }>;
  column_order?: Array<Record<string, unknown>>;
  columnOrder?: Array<Record<string, unknown>>;
}

export interface DataSourceColumn {
  id: string;
  name: string;
  type: string;
  order?: number;
}

export interface DataSourceItem {
  id: string;
  [key: string]: any;
}

export interface Interface {
  id: string;
  name: string;
  tenantId?: string;
  workflowId?: string;
  description?: string;
  schema?: any;
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  dataSourceId?: number | null;
  createdAt?: string;
  updatedAt?: string;
  sourceWorkflowId?: string;
  interfaceType?: string;
  data?: Record<string, unknown>;
  agentId?: string;
  messageId?: string;
  conversationId?: string;
  projectId?: string | null;
}

export interface Agent {
  id: string;
  name: string;
  tenantId: string;
  description?: string;
  systemPrompt?: string;
  modelProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  executionTimeout?: number;
  inactivityTimeout?: number;
  toolsConfig?: any;
  workflowId?: string;
  dataSourceId?: number | null;
  conversationId?: string | null;
  config?: any;
  avatarUrl?: string;
  isPublic?: boolean;
  isActive?: boolean;
  createdAt?: string;
  updatedAt?: string;
  // Credit budget - see the project docs
  // `creditBudget` is the configured cap (writable).
  // `creditsConsumed`, `creditsReserved`, `creditsFree` are server-managed and READ-ONLY:
  // they reflect in-flight sub-agent cascade reservations and must never be sent on a PUT.
  // Use `AgentUpdateInput` to get compile-time protection against accidental writes.
  creditBudget?: number | null;
  creditsConsumed?: number;
  /**
   * Subset of `creditsConsumed` billed by DESCENDANT agents via the cascade settlement
   * path. Invariant: `creditsConsumedFromSubagents <= creditsConsumed`. Derived
   * "own spend" is `consumed_own = creditsConsumed - creditsConsumedFromSubagents`.
   * Server-managed, READ-ONLY.
   */
  creditsConsumedFromSubagents?: number;
  /**
   * Credits currently locked by in-flight sub-agent cascade reservations.
   * Non-zero means at least one descendant is running and holding budget from this agent.
   */
  creditsReserved?: number;
  /**
   * Spendable budget: `creditBudget - creditsConsumed - creditsReserved`, clamped ≥ 0.
   * `null` when `creditBudget` is null (unlimited agent). Computed server-side.
   */
  creditsFree?: number | null;
  budgetResetMode?: 'cumulative' | 'monthly' | 'weekly';
  budgetLastReset?: string | null;
  // Observability counter columns
  totalExecutions?: number;
  totalTokensUsed?: number;
  totalToolCalls?: number;
  successCount?: number;
  failureCount?: number;
  cancelledCount?: number;
  loopDetectedCount?: number;
  totalDurationMs?: number;
  lastExecutionAt?: string;
  projectId?: string | null;
  // Per-agent guard overrides (null → use platform default)
  maxPerResourcePerTurn?: number | null;
  loopIdenticalStop?: number | null;
  loopConsecutiveStop?: number | null;
  // Stage 5.2b - per-agent override for the COLD summariser model.
  // Null on both ⇒ resolver falls back to the agent's primary model and then
  // to the platform default (ai.agent.defaults.compaction-model.*).
  compactionModelProvider?: string | null;
  compactionModelName?: string | null;
  // V350 - per-agent compaction enable + cadence override. Null ⇒ inherit
  // (conversation override, then the conversation.compaction.* platform default).
  compactionEnabled?: boolean | null;
  compactionAfterTurns?: number | null;
}

/**
 * Shape accepted by `agentService.updateAgent` and `createAgent`.
 * Server-managed budget fields (`creditsConsumed`, `creditsReserved`, `creditsFree`) are
 * stripped at compile time via `Omit` so client code cannot accidentally send them.
 */
export type AgentUpdateInput = Partial<
  Omit<Agent, 'creditsConsumed' | 'creditsConsumedFromSubagents' | 'creditsReserved'
    | 'creditsFree' | 'budgetLastReset'
    | 'totalExecutions' | 'totalTokensUsed' | 'totalToolCalls' | 'successCount'
    | 'failureCount' | 'cancelledCount' | 'loopDetectedCount' | 'totalDurationMs'
    | 'lastExecutionAt' | 'createdAt' | 'updatedAt' | 'id' | 'tenantId'>
>;

// ============================================
// Skill types
// ============================================

export interface Skill {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  icon?: string;
  instructions: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  folderId?: string | null;
  defaultKey?: string | null;
  /**
   * When true, this skill is admin-managed and available to every tenant.
   * Non-admins see it read-only alongside their own skills (Deep-Research-like).
   */
  isGlobal?: boolean;
  /**
   * V275 (2026-05-21) - owner/admin flag: when true, this skill is auto-included
   * in NEW general-chat conversations for every member of its visibility scope.
   * Users can override on a per-user basis via {@link user_skill_overrides};
   * see {@code SkillService.userActiveOverride}.
   */
  isDefaultActive?: boolean;
}

export interface AgentSkill {
  id: string;
  agentId: string;
  skillId: string;
  skill: Skill;
  sortOrder: number;
  createdAt: string;
}

export interface AgentSkillAssignment {
  skillId: string;
}

// ============================================
// Skill Folder types
// ============================================

export interface SkillFolder {
  id: string;
  tenantId: string;
  name: string;
  parentId: string | null;
  createdAt: string;
  updatedAt: string;
  /**
   * V275 (2026-05-21) - admin-only visibility flag. When true the folder is
   * read-only and visible to every tenant. Independent of child skill globalness
   * (no cascade): a global folder can still contain a mix of personal + global
   * skills.
   */
  isGlobal?: boolean;
}

export interface SkillFolderContents {
  folders: SkillFolder[];
  skills: Skill[];
}

export type WebhookAuthType = 'none' | 'basic' | 'header' | 'jwt';
export type WebhookHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface AgentWebhookAuthConfig {
  // For basic auth
  username?: string;
  password?: string;
  // For header auth
  headerName?: string;
  headerValue?: string;
  // For JWT auth
  jwtSecret?: string;
  jwtAlgorithm?: 'HS256' | 'HS384' | 'HS512';
}

export interface AgentWebhook {
  agentId: string;
  token: string;
  httpMethod: WebhookHttpMethod;
  authType: WebhookAuthType;
  isActive: boolean;
  memoryEnabled: boolean;
  webhookUrl: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAgentWebhookRequest {
  httpMethod?: WebhookHttpMethod;
  authType?: WebhookAuthType;
  authConfig?: AgentWebhookAuthConfig;
  memoryEnabled?: boolean;
}

// Agent Schedule Types
export interface AgentSchedule {
  id: string;
  agentEntityId: string;
  cronExpression: string;
  timezone: string;
  maxExecutions?: number | null;
  schedulePrompt: string;
  withMemory: boolean;
  enabled: boolean;
  nextExecutionAt?: string;
  lastExecutionAt?: string;
  executionCount: number;
  createdAt: string;
}

export interface CreateAgentScheduleRequest {
  cron: string;
  timezone?: string;
  maxExecutions?: number | null;
  schedulePrompt: string;
  withMemory?: boolean;
}

// Widget Configuration Types
export type WidgetPosition = 'bottom-right' | 'bottom-left' | 'top-right' | 'top-left';
export type WidgetTheme = 'light' | 'dark' | 'auto';

export interface AgentWidgetConfig {
  agentId: string;
  widgetToken?: string;
  position: WidgetPosition;
  theme: WidgetTheme;
  primaryColor: string;
  welcomeMessage: string;
  bubbleText: string;
  showAvatar: boolean;
  autoOpenDelay: number;
  allowedOrigins?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  // Additional info from backend
  agentName?: string;
  agentAvatarUrl?: string;
  widgetScriptUrl?: string;
  embedCode?: string;
}

export interface CreateAgentWidgetRequest {
  position?: WidgetPosition;
  theme?: WidgetTheme;
  primaryColor?: string;
  welcomeMessage?: string;
  bubbleText?: string;
  showAvatar?: boolean;
  autoOpenDelay?: number;
  allowedOrigins?: string;
}

// ============================================
// Category & Publication Types
// ============================================

export interface WorkflowCategory {
  id: string;
  slug: string;
  name: string;
  description?: string;
  iconSlug?: string;
  color?: string;
  displayOrder: number;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface WorkflowPublication {
  id: string;
  publicationType?: 'WORKFLOW' | 'AGENT' | 'TABLE' | 'INTERFACE' | 'SKILL';
  workflowId?: string;
  agentConfigId?: string;
  /** Resource id for standalone TABLE / INTERFACE / SKILL publications (null for WORKFLOW/AGENT,
   *  which use workflowId / agentConfigId). Lets the resource listing pages resolve a card's
   *  shared/private state from ONE /publications/my fetch instead of one status call per item. */
  resourceId?: string | null;
  title: string;
  description?: string;
  showcaseInterfaceId?: string;
  showcaseRunId?: string;
  /** A1 - a CLOUD purchase backed by a LOCAL clone. The synth carries the clone's workflow id +
   *  entry interface so the My-Purchases card renders the acquirer's OWN clone (via the local
   *  authenticated per-run path), which stays alive after the cloud publisher unpublishes/deletes
   *  the source. {@code localShowcase} flips the card off the (dead) cloud showcase proxy. */
  acquiredWorkflowId?: string;
  localShowcase?: boolean;
  /** V273 - publisher's pinned epoch for the marketplace preview; null = legacy multi-epoch view */
  showcaseChosenEpoch?: number | null;
  hasShowcase?: boolean;
  /** True if publication has an interface (application), false if workflow-only */
  isApplication?: boolean;
  /** Pre-computed node icon props for marketplace card display */
  nodeIcons?: NodeIconData[];
  /** Display mode: how the publication renders in the marketplace */
  displayMode?: 'WORKFLOW' | 'INTERFACE' | 'APPLICATION' | 'AGENT' | 'TABLE' | 'SKILL';
  category?: {
    id: string;
    slug: string;
    name: string;
    iconSlug?: string;
    color?: string;
  };
  planSnapshot?: any;
  agentSnapshot?: AgentPublicationSnapshot;
  planVersion?: number;
  creditsPerUse: number;
  publisherId: string;
  publisherName?: string;
  publisherEmail?: string;
  publisherAvatarUrl?: string;
  /** Ownership scope (V223/#151). owner_id is the scoping source of truth; publisherId is
   *  only the human who clicked publish. Used for org-aware "already owned" on the marketplace. */
  ownerType?: 'USER' | 'ORG';
  ownerId?: string;
  /** Server-computed: the caller's ACTIVE workspace already owns this publication (owner is the
   *  active org, or the caller personally) → the card shows "Installed" instead of "Acquire". */
  ownedByMe?: boolean;
  status: 'ACTIVE' | 'INACTIVE' | 'PENDING_REVIEW' | 'REJECTED';
  reviewerId?: string;
  reviewedAt?: string;
  rejectionReason?: string;
  visibility: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
  useCount: number;
  totalCreditsEarned: number;
  agentCount?: number;
  skillCount?: number;
  interfaceCount?: number;
  datasourceCount?: number;
  workflowCount?: number;
  agentAvatarUrl?: string;
  agentModelProvider?: string;
  agentModelName?: string;
  averageRating?: number;
  reviewCount?: number;
  publishedAt?: string;
  updatedAt?: string;
  published?: boolean;
  /** Cloud-sourced publication on a cloud-linked CE: the source publication lives on the
   *  cloud (absent from the local catalog), so per-publication reads (by-id, showcase-render)
   *  must route through the `/publications/remote/by-id/...` cloud proxy. Set by the backend
   *  for remote acquisitions (and on cloud-marketplace cards); local publications omit it. */
  remote?: boolean;
}

export interface ModerationStats {
  pendingCount: number;
  approvedCount: number;
  rejectedCount: number;
}

export interface PendingPublicationsResponse {
  publications: WorkflowPublication[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface PublicationComparisonData {
  publicationId: string;
  publicationType: 'WORKFLOW' | 'AGENT' | 'TABLE' | 'INTERFACE' | 'SKILL';
  title: string;
  description?: string;
  publisherId: string;
  publisherName?: string;
  visibility?: string;
  creditsPerUse?: number;
  status: string;
  snapshot: any;
  currentSource: any;
}

// ============================================
// Agent Publication Types
// ============================================

export interface AgentSnapshotData {
  id: string;
  name: string;
  description?: string;
  systemPrompt: string;
  modelProvider: string;
  modelName: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  executionTimeout?: number;
  inactivityTimeout?: number;
  avatarUrl?: string;
  config?: Record<string, unknown>;
  dataSourceId?: number;
  toolsConfig?: Record<string, unknown>;
  skills?: { name: string; description: string; icon?: string; instructions: string; sortOrder: number }[];
}

export interface AgentPublicationSnapshot {
  agent: AgentSnapshotData;
  subAgents?: Record<string, { agent: AgentSnapshotData; subAgents?: Record<string, unknown> }>;
  workflows?: Record<string, { name: string; description: string; plan: unknown }>;
  interfaces?: Record<string, { name: string; description?: string; htmlTemplate: string; cssTemplate: string; jsTemplate: string; interfaceType: string }>;
  datasources?: Record<string, { name: string; description?: string; sourceType: string; items?: unknown[] }>;
}

export interface PublishAgentRequest {
  agentConfigId: string;
  /** Landing page interface shown on the marketplace listing (required by the backend). */
  interfaceId: string;
  title?: string;
  description?: string;
  categoryId?: string;
  creditsPerUse?: number;
  publisherName?: string;
  publisherEmail?: string;
  publisherAvatarUrl?: string;
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
}

// Standalone resource publications (TABLE / INTERFACE / SKILL)

export type ResourceType = 'TABLE' | 'INTERFACE' | 'SKILL';

export interface PublishResourceRequest {
  type: ResourceType;
  resourceId: string;
  title: string;
  description?: string;
  /** Required for TABLE + SKILL (landing page). Must be omitted for INTERFACE (the resource IS its own landing). */
  interfaceId?: string;
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
  creditsPerUse?: number;
  categoryId?: string;
  publisherName?: string;
  publisherEmail?: string;
  publisherAvatarUrl?: string;
}

export interface ResourcePublicationResponse {
  id: string;
  title: string;
  status: string;
  type: ResourceType;
  resourceId: string;
  visibility?: string;
  creditsPerUse?: number;
}

export interface ResourcePublicationStatus {
  exists: boolean;
  status?: string;
  published: boolean;
  publicationId?: string;
  rejectionReason?: string | null;
}

export interface AcquireAgentResponse {
  agentId: string;
  name: string;
}

export interface PublishWorkflowRequest {
  workflowId: string;
  title: string;
  description?: string;
  showcaseInterfaceId?: string;
  /** Required for PUBLIC/UNLISTED, optional for PRIVATE */
  showcaseRunId?: string;
  categoryId?: string;
  creditsPerUse?: number;
  publisherName?: string;
  publisherEmail?: string;
  publisherAvatarUrl?: string;
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
  planVersion?: number;
  /** Display mode: WORKFLOW, INTERFACE, APPLICATION */
  displayMode?: 'WORKFLOW' | 'INTERFACE' | 'APPLICATION';
  /** V273 - pin one captured epoch as the canonical marketplace preview (null/undefined = multi-epoch view) */
  showcaseEpoch?: number;
  /** V274 - true when this publish goes through the screening wizard (which POSTs decisions separately); false / omitted = backend auto-screens and writes SKIPPED audit rows */
  viaScreeningWizard?: boolean;
  imageReplacements?: Array<{ originalUrl: string; storageKey: string }>;
}

export interface UpdatePublicationRequest {
  title: string;
  description?: string;
  showcaseInterfaceId?: string;
  /** Required for PUBLIC/UNLISTED, optional for PRIVATE */
  showcaseRunId?: string;
  categoryId?: string;
  creditsPerUse?: number;
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
  /** Display mode: WORKFLOW, INTERFACE, APPLICATION */
  displayMode?: 'WORKFLOW' | 'INTERFACE' | 'APPLICATION';
  /** V273 - clear the selected epoch and return to the multi-epoch showcase view */
  clearShowcaseEpoch?: boolean;
  /** V273 - pin a different epoch; omit to preserve the existing pin */
  showcaseEpoch?: number;
  /** V274 - same semantics as on PublishWorkflowRequest */
  viaScreeningWizard?: boolean;
  imageReplacements?: Array<{ originalUrl: string; storageKey: string }>;
}

export interface PublicationsListResponse {
  count: number;
  publications: WorkflowPublication[];
}

export interface MarketplacePublicationsResponse {
  count: number;
  page: number;
  size: number;
  totalPages: number;
  category?: string;
  publications: WorkflowPublication[];
}

export interface CategoriesListResponse {
  count: number;
  categories: WorkflowCategory[];
}

// ============================================
// Acquired Application Types
// ============================================

export interface AcquiredApplication {
  workflowId: string;
  sourcePublicationId: string;
  name: string;
  description?: string;
  acquiredAt?: string;
  nodeIcons?: NodeIconData[];
  publication: WorkflowPublication | null;
}

export interface AcquiredApplicationsResponse {
  count: number;
  applications: AcquiredApplication[];
}

export interface AcquirePublicationResponse {
  workflowId: string;
  publicationId: string;
  title: string;
}

// ============================================
// Purchase (Receipt) Types
// ============================================

export interface Purchase {
  publicationId: string;
  creditsPaid: number;
  acquiredAt: string;
  hasActiveWorkflow: boolean;
  publication: WorkflowPublication | null;
}

export interface PurchasesResponse {
  count: number;
  purchases: Purchase[];
}

// ============================================
// Publication Review Types
// ============================================

export interface PublicationReview {
  id: string;
  publicationId: string;
  parentId?: string | null;
  reviewerId: string;
  reviewerName?: string;
  reviewerAvatarUrl?: string;
  rating?: number | null;
  comment?: string;
  createdAt: string;
  updatedAt: string;
  replyCount?: number;
}

export interface PublicationReviewsResponse {
  reviews: PublicationReview[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PublicationRepliesResponse {
  replies: PublicationReview[];
  count: number;
}

// ============================================
// Workflow Version Types
// ============================================

export interface WorkflowPlanVersion {
  version: number;
  label?: string | null;
  nodeCount?: number;
  runCount?: number;
  createdAt: string;
  createdBy?: string;
}

export interface WorkflowVersionDetail extends WorkflowPlanVersion {
  plan: any;
}

export interface WorkflowVersionsResponse {
  versions: WorkflowPlanVersion[];
  currentVersion: number;
  pinnedVersion?: number | null;
  totalVersions: number;
}

export interface RestoreVersionResponse {
  success: boolean;
  message: string;
  restoredFromVersion: number;
  currentVersion: number;
  plan: any;
}

// ============================================
// Credential Types
// ============================================

export interface Credential {
  id: number;
  tenant_id: string;
  name: string;
  integration: string;
  type: CredentialType;
  environment: CredentialEnvironment;
  status: CredentialStatus;
  description?: string;
  credential_data: Record<string, unknown>;
  scopes: string[];
  tags: string[];
  owner?: string;
  last_used?: string;
  icon_url?: string;
  is_default: boolean;
  created_at: string;
  updated_at: string;
}

export type CredentialType = 'OAuth2' | 'API Key' | 'Basic Auth' | 'Webhook';
export type CredentialEnvironment = 'Production' | 'Sandbox';
// `needs_reauth` is the V113 terminal-user state the OAuth2 refresh pipeline
// flips to when the user must re-authorize (refresh_token revoked, scope
// removed, BYOK platform_credential cascade-deleted in Phase 2). Distinct
// from `error` (terminal_config - admin must repair the template/secret).
// The frontend MUST acknowledge this state to render the Reconnect CTA;
// before this fix the type collapsed it onto `error` silently.
export type CredentialStatus = 'active' | 'expiring' | 'error' | 'needs_reauth';

export interface PaginatedCredentialsResponse {
  credentials: Credential[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface CredentialTemplate {
  id: string;
  credential_name: string;
  display_name: string;
  description?: string;
  credential_type?: string;
  auth_type: string;
  /**
   * V103 variant identifier - one of oauth2, api_key, basic_auth, bearer_token, custom, or
   * 'primary' for legacy single-variant rows. A single credential_name can have multiple
   * variant rows (e.g. Gmail exposes both oauth2 and api_key); the wizard lists them via
   * {@link orchestratorApi.getCredentialVariants} and renders tabs when more than one exists.
   */
  variant?: string;
  /**
   * All auth variants available for this credential_name, populated by the list endpoint
   * (GET /api/catalog/credentials) so the UI can show a "N auth methods" chip without an
   * extra round-trip. Length ≥ 1 when present; single-variant APIs have exactly one entry.
   */
  variants?: Array<{ variant: string; auth_type: string }>;
  test_endpoint?: string;
  documentation_url?: string;
  icon_url?: string;
  icon_slug?: string;
  properties: CredentialProperty[] | string;
  extends_?: unknown;
  metadata?: unknown;
  /** 'custom' for user-registered APIs, 'import' for seeded APIs, undefined if unknown */
  source?: string;
}

export interface CredentialProperty {
  name: string;
  displayName: string;
  type: 'string' | 'number' | 'boolean' | 'options' | 'hidden' | 'json' | 'notice';
  default?: string;
  required?: boolean;
  description?: string;
  placeholder?: string;
  typeOptions?: {
    password?: boolean;
    expirable?: boolean;
  };
  displayOptions?: {
    show?: Record<string, unknown[]>;
    hide?: Record<string, unknown[]>;
  };
  options?: PropertyOption[];
}

export interface PropertyOption {
  name: string;
  value: string;
  description?: string;
}

export interface PaginatedTemplatesResponse {
  credentials: CredentialTemplate[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface OAuth2InitiateRequest {
  credential_template_id: string;
  credential_name?: string;
  client_id?: string;
  client_secret?: string;
  environment?: string;
  integration?: string;
  return_url?: string;
}

export interface OAuth2SimpleInitiateRequest {
  credential_template_id: string;
  credential_name?: string;
  environment?: string;
  integration?: string;
}

export interface OAuth2InitiateResponse {
  authorization_url: string;
  state: string;
}

export interface PlatformCredentialsAvailability {
  available: boolean;
  showUnverifiedAppWarning?: boolean;
}

// ============================================
// Platform Credentials Types (Admin)
// ============================================

export type PlatformAuthType = 'oauth2' | 'api_key' | 'basic' | 'bearer' | 'custom' | 'none';

export interface PlatformCredential {
  id: number;
  integrationName: string;
  displayName: string;
  authType: PlatformAuthType;
  clientIdMasked?: string;
  hasClientSecret: boolean;
  hasApiKey: boolean;
  hasBasicAuth: boolean;
  hasCustomFields: boolean;
  authUrl?: string;
  tokenUrl?: string;
  defaultScopes?: string;
  iconSlug?: string;
  category?: string;
  description?: string;
  showUnverifiedAppWarning: boolean;
  isEnabled: boolean;
  endpoints: EndpointStatus[];
  createdAt: string;
  updatedAt: string;
  /**
   * V103 per-auth-method discriminator - one of `oauth2`, `api_key`,
   * `basic_auth`, `bearer_token`, `custom`, or `primary` (legacy fallback for
   * rows created before V103). An integration may have several rows, one per
   * variant, each with its own secrets and `isEnabled` flag. Optional on the
   * type so pre-Phase 2d callers that don't read variants still compile.
   */
  variant?: string;
  /** Tenant id when the row is tenant-scoped; null/undefined when platform-wide. */
  tenantId?: string | null;
}

export interface EndpointStatus {
  toolId: string;
  toolName: string;
  method?: string;
  endpoint?: string;
  isEnabled: boolean;
}

/** Immutable pricing snapshot attached to a platform credential. */
export interface PricingVersion {
  id: number;
  credentialId: number;
  version: number;
  /**
   * Nullable since V135: null means "no API-wide default - per-tool overrides
   * are the only source of markup". Stored as number|string|null to keep
   * decimal precision across the wire.
   */
  defaultMarkupCredits: number | string | null;
  createdAt?: string;
  createdBy?: string | null;
  /** apiToolId (UUID) -> markupCredits as a string (to preserve precision). */
  overrides: Record<string, string>;
}

export interface PublishPricingVersionRequest {
  /** Pass null to publish a version with no API-wide default. */
  defaultMarkupCredits: string | null;
  overrides: Record<string, string>;
}

/**
 * Non-admin view of a platform credential - returned by the inspector when
 * deciding whether to show the "Platform credential" toggle on an MCP step.
 * Secret fields are never included.
 *
 * <p>When a specific {@code apiToolId} is supplied, {@code hasPricing} and
 * {@code markupCredits} reflect the rate resolved for that tool (per-tool
 * override beats version default). Without {@code apiToolId}, the response
 * falls back to "any non-zero rate on this integration", which is how the
 * inspector renders the toggle when the node has not yet picked a tool.
 */
export interface PlatformCredentialPublicInfo {
  integrationName: string;
  /** Present when a platform credential row exists for this integration. */
  platformCredentialId?: number | null;
  /** True when the credential is enabled AND has a configured secret. */
  available: boolean;
  /** True when the OAuth sign-in flow should show the unverified-app heads-up. */
  showUnverifiedAppWarning?: boolean;
  /**
   * True when a positive rate applies - either resolved for {@code apiToolId}
   * (when supplied) or anywhere on the integration (default > 0 OR any
   * override > 0).
   */
  hasPricing: boolean;
  /** Per-tool resolved markup when apiToolId was supplied and hasPricing is true. */
  markupCredits?: string;
  /** Default markup of the latest version when it is set (may be absent / null). */
  defaultMarkupCredits?: string;
  /** Version number of the latest pricing snapshot when one exists. */
  pricingVersion?: number;
}

/**
 * Tenant-facing summary of a single BYOK custom OAuth connection (Phase 2).
 * Mirrors the backend {@code MyOAuthAppDto} 13-field allowlist. NEVER carries
 * secrets - only presence booleans and a masked clientId display value.
 */
export interface MyOAuthApp {
  id: number;
  integrationName: string;
  displayName: string;
  iconSlug: string | null;
  authType: string;
  /** "abcd****wxyz" for OAuth2 rows; null for non-OAuth2 rows. */
  clientIdMasked: string | null;
  hasClientSecret: boolean;
  hasApiKey: boolean;
  isEnabled: boolean;
  createdAt: string;
  updatedAt: string;
  createdBy: string | null;
  /**
   * Org (workspace) that owns this connection (V362). null = personal scope
   * (visible in every workspace). When set, the connection is scoped to that
   * workspace; compare to the active org to render a "This workspace" badge.
   */
  organizationId: string | null;
}

/**
 * Cascade impact preview returned by GET /my/{name}/delete-impact. The count is
 * capped at 999 server-side to prevent precise tenant-size fingerprinting; the
 * truncated flag tells the UI to show "999+" instead of an exact number.
 */
export interface DeleteImpact {
  integrationName: string;
  affectedCredentialCount: number;
  truncated: boolean;
}

/**
 * Result of DELETE /my/{name}. The backend cascade-revokes dependent user
 * credentials BEFORE deleting the BYOK row; revokedCredentialCount reports
 * how many transitioned to needs_reauth.
 */
export interface DeleteResult {
  deleted: boolean;
  integrationName: string;
  revokedCredentialCount: number;
}

export interface CreatePlatformCredentialRequest {
  integrationName: string;
  displayName: string;
  authType: string;
  clientId?: string;
  clientSecret?: string;
  apiKey?: string;
  username?: string;
  password?: string;
  authUrl?: string;
  tokenUrl?: string;
  defaultScopes?: string;
  iconSlug?: string;
  category?: string;
  description?: string;
  customFields?: Record<string, string>;
  showUnverifiedAppWarning?: boolean;
  /**
   * V103 variant targeting the `(integration_name, variant)` UNIQUE key. The
   * admin Configure dialog sends this so editing Airtable's `bearer_token` tab
   * does not clobber the `oauth2` row (and vice versa). Omitted by legacy
   * callers - the backend then defaults to the `primary` row for back-compat.
   */
  variant?: string;
}

export interface UpdatePlatformCredentialRequest {
  displayName?: string;
  clientId?: string;
  clientSecret?: string;
  apiKey?: string;
  username?: string;
  password?: string;
  authUrl?: string;
  tokenUrl?: string;
  defaultScopes?: string;
  iconSlug?: string;
  category?: string;
  description?: string;
  showUnverifiedAppWarning?: boolean;
  isEnabled?: boolean;
}

export interface CategoryInfo {
  slug: string;
  name: string;
  icon?: string;
  integrationCount: number;
}

export interface IntegrationInfo {
  id: string;
  name: string;
  iconSlug: string;
  authType: string;
  category: string;
  hasCredential: boolean;
  isEnabled: boolean;
  endpoints: EndpointInfo[];
}

export interface EndpointInfo {
  toolId: string;
  toolName: string;
  method: string;
  endpoint: string;
  description?: string;
}

export interface CredentialSourceInfo {
  integrationName: string;
  hasDbCredentials: boolean;
  hasCredentials: boolean;
  source: 'database' | 'config' | 'none';
}

// ============================================
// LLM Provider Types
// ============================================

export interface LlmProviderStatus {
  providerName: string;
  integrationName: string;
  configured: boolean;
  hasDbKey: boolean;
  source: 'database' | 'environment' | 'none';
}

export interface LlmProviderDefinition {
  providerName: string;
  integrationName: string;
  displayName: string;
  docsUrl: string;
  placeholder: string;
}

// ============================================
// Pagination Types
// ============================================

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
  first: boolean;
  last: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
  numberOfElements: number;
}

// Activity Feed Types removed 2026-05-08 - the ActivityFeed UI + ActivityLog
// backend stack were deleted (V177 drops orchestrator.activity_log).

// ============================================
// Interface Render Types
// ============================================

export interface InterfaceResolvedItem {
  epoch: number;
  itemIndex: number;
  spawn: number;
  data: Record<string, any>;
  triggerData?: Record<string, Record<string, unknown>>;
}

export interface InterfaceRenderResult {
  htmlTemplate: string;
  cssTemplate?: string;
  jsTemplate?: string;
  items: InterfaceResolvedItem[];
  pagination: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
  actionMappings?: Record<string, string>;
}

export interface InterfaceSnapshot {
  id: string;
  tenantId: string;
  interfaceId: string;
  workflowRunId: string;
  name: string;
  description?: string;
  htmlTemplate: string;
  cssTemplate?: string;
  jsTemplate?: string;
  variableMappings?: Record<string, string>;
  actionMappings?: Record<string, string>;
  createdAt: string;
}

// ============================================
// Execution State Types
// ============================================

export interface StepState {
  stepId: string;
  stepAlias: string;
  toolId?: string;
  status: string;
  inputData?: Record<string, any>;
  output?: Record<string, any>;
  itemIndex?: number;
  iteration?: number;
  httpStatus?: number;
  errorMessage?: string;
  startTime?: string;
  endTime?: string;
  executionTimeMs: number;
  dependencies: string[];
  canExecute: boolean;
  statusCounts?: Record<string, number>;
}

export interface EdgeState {
  from: string;
  to: string;
  status: string;
  completedCount: number;
  skippedCount?: number;
  totalCount: number;
}

export interface WorkflowRunState {
  runId: string;
  workflowId?: string;
  status: string;
  executionMode?: ExecutionMode;
  startedAt?: string;
  endedAt?: string;
  completedAt?: string;
  pausedAt?: string;
  durationMs?: number;
  plan: Record<string, any>;
  steps: StepState[];
  edges: EdgeState[];
  readySteps: string[];
  completedStepIds: string[];
  failedStepIds: string[];
  skippedStepIds: string[];
  runningStepIds?: string[];
  awaitingSignalStepIds?: string[];
  currentEpoch?: number;
  epochTimestamps?: Array<{ epoch: number; startedAt: string; endedAt: string | null }>;
  loops?: Record<string, {
    loopId: string;
    type: string;
    timestamp?: number;
    payload: {
      loopNodeId?: string;
      currentIteration?: number;
      completedIterations?: number;
      maxIterationSeen?: number;
      totalIterations?: number;
      completedItems?: number;
      maxIterations?: number;
      exitReason?: string;
      lastItemIndex?: number;
      loopLabel?: string;
    };
  }>;
}

// ============================================
// Pause/Resume/Step-by-Step Types
// ============================================

export interface PauseResumeResponse {
  success: boolean;
  runId: string;
  status: string;
  readySteps: string[];
  completedSteps?: number;
  message: string;
}

export interface StepExecutionResponse {
  success: boolean;
  runId: string;
  stepId: string;
  status: string;
  output: Record<string, any>;
  executionTime: number;
  message: string;
  readySteps: string[];
  workflowStatus: string;
}

export interface TriggerResponse {
  runId: string;
  triggerId: string | null;
  triggerType: string | null;
  status: 'triggered' | 'error';
  message: string;
  epoch: number;
  readySteps?: string[];
  availableTriggers?: TriggerInfo[];
}

// ============================================
// Multi-DAG Trigger Types
// ============================================

/**
 * Trigger type values matching backend TriggerType enum.
 */
export type TriggerTypeValue = 'manual' | 'chat' | 'webhook' | 'datasource' | 'schedule' | 'form' | 'workflow';

/**
 * Information about a trigger in a multi-DAG workflow.
 * Used for trigger selection UI.
 */
export interface TriggerInfo {
  triggerId: string;      // Normalized key, e.g., "trigger:my_webhook"
  label: string;          // Display label, e.g., "My Webhook"
  type: TriggerTypeValue; // Trigger type
  isReusable: boolean;    // Can fire multiple times (epochs)
  config?: TriggerConfig; // Type-specific configuration
}

/**
 * Type-specific configuration for triggers.
 */
export interface TriggerConfig {
  // Form trigger
  formTitle?: string;
  formDescription?: string;
  submitButtonText?: string;
  fields?: FormField[];
  // Chat trigger
  matchType?: string;
  matchValue?: string;
  caseSensitive?: boolean;
  // Webhook trigger
  httpMethod?: string;
  authType?: string;
  // Datasource trigger
  datasourceId?: string;
  strategy?: string;
  // Schedule trigger
  cron?: string;
  timezone?: string;
}

/**
 * Form field definition for form triggers.
 */
export interface FormField {
  id: string;
  name: string;
  label: string;
  type: string;
  placeholder?: string;
  required?: boolean;
  options?: { label: string; value: string }[];
  accept?: string;
}

export interface ReadyStepsResponse {
  runId: string;
  readySteps: string[];
  count: number;
}

export type ExecutionMode = 'automatic' | 'step_by_step';

export interface ExecutionModeResponse {
  runId: string;
  executionMode: ExecutionMode;
  isStepByStep: boolean;
  readySteps?: string[];
  status?: string;
  success?: boolean;
}

export interface ConditionEvaluation {
  type: 'if' | 'elseif' | 'else';
  expression: string | null;
  result: boolean;
  destination: string | null;
}

export interface CoreExecutionResponse {
  success: boolean;
  runId: string;
  coreId: string;
  normalizedCoreId: string;
  selectedBranch: string;
  skippedBranches: string[];
  evaluations: ConditionEvaluation[];
  readySteps: string[];
  message: string;
}

export interface DecisionEvaluatedEvent {
  type: 'decisionEvaluated';
  runId: string;
  coreId: string;
  selectedBranch: string;
  skippedBranches: string[];
  evaluations: ConditionEvaluation[];
  timestamp: number;
  message: string;
}

// ============================================
// Step Re-run Types
// ============================================

export interface StepRerunResponse {
  success: boolean;
  runId: string;
  stepId: string;
  epoch: number;
  spawn: number;
  resetSteps: string[];
  readySteps: string[];
  status: string;
  seq: number;
}

export interface StepAttemptRecord {
  epoch: number;
  status: string;
  startTime?: string;
  endTime?: string;
  errorMessage?: string;
  outputStorageId?: string;
}

// ============================================
// Detailed Step Data Types (Node-Specific Display)
// ============================================

/**
 * Column types for step data display.
 */
export type ColumnType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'DATETIME' | 'JSON';

/**
 * Render types for column display.
 * The frontend uses these to determine how to render each column.
 */
export type RenderType =
  | 'TEXT'
  | 'CODE'
  | 'STATUS_BADGE'
  | 'BRANCH_BADGE'
  | 'HTTP_STATUS_BADGE'
  | 'HTTP_METHOD_BADGE'
  | 'BOOLEAN_BADGE'
  | 'BADGE'
  | 'DURATION'
  | 'RELATIVE_TIME'
  | 'PERCENTAGE'
  | 'PROGRESS_BAR'
  | 'JSON_PREVIEW'
  | 'JSON_NAVIGABLE'
  | 'EVALUATIONS_TABLE'
  | 'CASES_TABLE'
  | 'STRING_LIST'
  | 'TEXT_PREVIEW'
  | 'HTML_PREVIEW'
  | 'LOOP_PROGRESS'
  | 'SPLIT_PROGRESS';

/**
 * Node types for workflow execution.
 */
/**
 * Node types for workflow execution.
 * Each node type has its own display logic and columns.
 */
export type NodeType =
  | 'TRIGGER'           // Entry point
  | 'MCP'               // Step/Tool execution
  | 'AGENT'             // AI Agent with tool-calling
  | 'DECISION'          // If/ElseIf/Else branching
  | 'SWITCH'            // Value-based switch/case branching
  | 'LOOP_CONTROLLER'   // While loop
  | 'SPLIT_CONTROLLER'// Split parallel iteration
  | 'MERGE'             // Merge multiple branches (AND mode)
  | 'FORK';             // Fork to parallel branches (no condition)

/**
 * Column definition from backend.
 * The backend is the source of truth for column order and rendering.
 */
export interface ColumnDefinition {
  field: string;
  header: string;
  type: ColumnType;
  renderType: RenderType;
  width?: number;
  sortable: boolean;
  filterable: boolean;
  expandable?: boolean;
}

/**
 * Pagination info for detailed step data.
 */
export interface PaginationInfo {
  page: number;
  pageSize: number;
  totalRows: number;
  hasMore: boolean;
}

/**
 * Response for detailed step data with node-specific columns.
 */
export interface DetailedStepDataResponse {
  nodeType: NodeType | null;
  stepAlias: string;
  toolId: string | null;
  columns: ColumnDefinition[];
  rows: Record<string, any>[];
  pagination: PaginationInfo;
}

/**
 * Branch evaluation info for Decision nodes.
 */
export interface BranchEvaluation {
  branch: string;
  condition: string | null;
  resolved: string | null;
  result: boolean | null;
  selected: boolean;
}

/**
 * Loop progress info for Loop nodes.
 */
export interface LoopProgress {
  current: number;
  max: number;
}

/**
 * Split progress info for Split nodes.
 */
export interface SplitProgress {
  total: number;
  processed: number;
}

// ============================================
// Aggregated Step Timing (per-epoch node timing)
// ============================================

/**
 * Aggregated step timing data returned by the aggregated steps endpoint.
 * Used for per-epoch node timing display in the epoch timeline.
 */
export interface AggregatedStepTiming {
  alias: string;
  status: string;
  toolId?: string;
  startTime?: string;
  endTime?: string;
  executionTimeMs?: number;
  statusCounts?: Record<string, number>;
}

/**
 * Active signal info for a specific epoch.
 * Used to show pending signals (user approval, interface, etc.) when viewing historical epochs.
 */
export interface EpochSignalInfo {
  nodeId: string;
  signalType: 'USER_APPROVAL' | 'WAIT_TIMER' | 'WEBHOOK_WAIT' | 'INTERFACE_SIGNAL';
  status: 'PENDING' | 'CLAIMED';
  itemId: string;
  createdAt?: string;
  expiresAt?: string;
}

/**
 * Pre-aggregated per-epoch node and edge status counts.
 * Returned by GET /runs/{runId}/epochs/{epoch}/state.
 *
 * `runningNodeIds` carries node keys (e.g. `mcp:step1`, `core:my_check`) that
 * are currently in flight for this epoch. For active epochs the backend
 * overlays Redis live counts on top of the JSONB snapshot (drained at close),
 * so the set shrinks to empty once the epoch closes - the per-epoch view can
 * rely on the field for the live shimmer.
 *
 * `isActive` is true while the epoch is still running. The focus view uses it
 * to decide whether to overlay live running ITEM counts (from the all-mode
 * batch stream) onto nodes/edges - running counts are never persisted in the
 * per-epoch count rows, so they exist only live and only for the active epoch.
 */
export interface EpochState {
  epoch: number;
  nodes: Record<string, Record<string, number>>;
  edges: Record<string, Record<string, number>>;
  runningNodeIds?: string[];
  /** True while this epoch is still executing (header not yet closed). */
  isActive?: boolean;
}

export type BridgeCliId = 'claudeCode' | 'codex' | 'geminiCli' | 'mistralVibe';

export interface BridgeCliEntry {
  id?: BridgeCliId;
  label?: string;
  installed: boolean;
  /** True iff the CLI is installed AND has auth configured (a provider API-key env
   *  or a login credential file). The status badge needs this so an installed-but-
   *  not-logged-in CLI isn't shown as "Connected" (it would still fail at run time
   *  with "please log in"). Consumers treat only an EXPLICIT `false` as "login
   *  required"; a bridge too old to report it (undefined) keeps the prior behavior. */
  authenticated?: boolean;
  binary: string | null;
  version: string | null;
  error: string | null;
}

export interface BridgeStatusResponse {
  /** True iff bridge reachable AND (the requested CLI installed | at least one CLI installed). */
  connected: boolean;
  bridgeReachable: boolean;
  /** Bridge host OS - "linux" | "darwin" | "win32" */
  platform?: string;
  /** Present when no `cli` filter was passed. */
  clis?: Partial<Record<BridgeCliId, BridgeCliEntry>>;
  /** Present when a `cli` filter was passed. */
  cli?: BridgeCliEntry;
  /** Error/explanation message when not connected. */
  error?: string;
  service?: string;
}

// ========================================
// Bridge Access Policy (V118)
// ========================================

/**
 * Four access regimes for CLI bridge dispatch (Claude Code / Codex / Gemini CLI /
 * Mistral Vibe). Opt-in by default - every bridge ships as `disabled`. Persisted
 * in `auth.bridge_access_policy.access_mode` (lowercase on the wire).
 */
export type BridgeAccessMode = 'disabled' | 'admin_only' | 'allowlist' | 'all_users';

export interface BridgeAccessPolicy {
  id: number;
  bridgeProvider: string;
  accessMode: BridgeAccessMode;
  /** Null / undefined means unlimited. */
  maxRequestsPerUserPerDay: number | null;
  updatedAt: string;
  updatedBy: string;
}

export interface BridgeAccessAllowlistEntry {
  policyId: number;
  userId: string;
  grantedAt: string;
  grantedBy: string;
}

export interface BridgeAccessUsageStat {
  userId: string;
  requestsToday: number;
  lastRequestAt: string;
}

export interface BridgeAccessView {
  policy: BridgeAccessPolicy;
  allowlist: BridgeAccessAllowlistEntry[];
  recentUsage: BridgeAccessUsageStat[];
}

export interface UpdateBridgeAccessPolicyRequest {
  accessMode: BridgeAccessMode;
  maxRequestsPerUserPerDay?: number | null;
}

