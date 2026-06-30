/**
 * API SERVICES - Point d'entree unique
 *
 * Tous les appels API passent par ce systeme centralise.
 * Les requetes transitent par:
 *   Frontend -> Next.js Proxy (/api/proxy) -> Spring Gateway (8080) -> Services Backend
 *
 * Architecture:
 * - apiClient: Base HTTP client with OIDC token management
 * - orchestratorApi: Workflows, DataSources, Interfaces, Credentials
 * - unifiedApiService: Catalog, Billing, Chat, Tools (legacy)
 */

// ============================================
// API Client - Base HTTP Client
// ============================================
export { apiClient, ApiClient, ApiError } from './api-client';
export type { ApiClientConfig, RequestOptions } from './api-client';

// ============================================
// Error Utilities - 404 and client error detection
// ============================================
export { is404Error, isClientError, isAuthError } from './error-utils';

// ============================================
// Orchestrator API - Workflows, Data, Interfaces
// ============================================
// Unified API (backward compatible)
export { orchestratorApi, OrchestratorApi } from './orchestrator';

// Individual services (SOLID - Single Responsibility)
export {
  workflowService,
  dataSourceService,
  interfaceService,
  agentService,
  credentialService,
  executionService,
  publicationService,
  skillService,
  skillFolderService,
  webhookSettingsService,
  scheduleSettingsService,
  chatEndpointSettingsService,
  formEndpointSettingsService,
  nodeTypeSettingsService,
} from './orchestrator';

// Types
export type {
  Workflow,
  WorkflowRun,
  WorkflowStep,
  WorkflowPublication,
  WorkflowCategory,
  InterfaceSnapshot,
  InterfaceRenderResult,
  InterfaceResolvedItem,
  DataSource,
  DataSourceColumn,
  DataSourceItem,
  Interface,
  Agent,
  Credential,
  CredentialTemplate,
  CredentialProperty,
  PaginatedResponse,
  PaginatedCredentialsResponse,
  PaginatedTemplatesResponse,
  // Pause / Resume / Step-by-Step types
  StepState,
  EdgeState,
  WorkflowRunState,
  PauseResumeResponse,
  StepExecutionResponse,
  CoreExecutionResponse,
  ReadyStepsResponse,
  TriggerResponse,
  // Step-by-Step V2 types
  ExecutionMode,
  ExecutionModeResponse,
  ConditionEvaluation,
  DecisionEvaluatedEvent,
  // Re-run types
  StepRerunResponse,
  StepAttemptRecord,
  // Epoch state types
  EpochState,
  // Multi-DAG trigger types
  TriggerInfo,
  TriggerTypeValue,
  TriggerConfig,
  FormField,
  // Publication types
  PublishWorkflowRequest,
  UpdatePublicationRequest,
  PublicationsListResponse,
  MarketplacePublicationsResponse,
  CategoriesListResponse,
  // OAuth2 types
  OAuth2InitiateRequest,
  OAuth2InitiateResponse,
  // Agent Webhook types
  AgentWebhook,
  AgentWebhookAuthConfig,
  CreateAgentWebhookRequest,
  WebhookAuthType,
  WebhookHttpMethod,
  // Skill types
  Skill,
  AgentSkill,
  AgentSkillAssignment,
  SkillFolder,
  SkillFolderContents
} from './orchestrator';

// ============================================
// Storage API - Quota and Usage
// ============================================
export { storageApi } from './storage-api';
export type { StorageQuota, QuotaStatus, TenantStats, StorageBreakdown, StorageCategory, StorageHistoryPoint } from './storage-api';
export { STORAGE_CATEGORY_COLORS, STORAGE_CATEGORY_HEX } from './storage-api';

// ============================================
// Quota API - Credits and Usage Tracking
// ============================================
export { quotaApi } from './services/quota-api.service';
export type {
  CreditBalance,
  CreditSummary,
  CreditHistoryEntry,
  CreditHistoryPage,
  ModelPricingEntry,
  DailyUsageEntry,
  UsageAnalytics,
} from './services/quota-api.service';

// ============================================
// Organization API - Members, Invitations, Teams
// ============================================
export { organizationApi } from './organization-api';
export type {
  Organization,
  OrganizationMember,
  OrganizationRole,
  Invitation,
  InvitationStatus,
} from './organization-api';

// ============================================
// Unified API Service - Catalog, Billing, Chat
// ============================================
export { UnifiedApiService, unifiedApiService } from './unified-api-service';
export type {
  UnifiedApiResponse,
  ToolResponse,
  ParameterResponse,
  MonetizationResponse,
  ToolCategoryInfo,
  CategoryResponse,
  ToolCategory,
  ToolName,
  SubcategoryResponse,
  DeveloperProfile,
  McpServer,
  ToolTestResult,
  ToolPerformanceMetrics
} from './unified-api-service';

