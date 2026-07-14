/**
 * Orchestrator API - Unified Export
 *
 * This module provides a unified interface for all orchestrator-related operations.
 * Follows SOLID principles by separating concerns into specialized services.
 *
 * Structure:
 * - types.ts: All type definitions
 * - workflow.service.ts: Workflow CRUD, execution, streaming
 * - datasource.service.ts: DataSource CRUD, columns, items
 * - interface.service.ts: Interface CRUD, render
 * - agent.service.ts: Agent CRUD
 * - credential.service.ts: Credential CRUD, OAuth2
 * - execution.service.ts: Pause/Resume, Step-by-step
 * - publication.service.ts: Publications, Categories
 *
 * Usage:
 * - New code: Import specific services (e.g., import { workflowService } from './orchestrator')
 * - Legacy code: Import orchestratorApi for backward compatibility
 */

// ============================================
// Re-export all types
// ============================================
export * from './types';

// ============================================
// Re-export individual services
// ============================================
export { WorkflowService, workflowService } from './workflow.service';
export { DataSourceService, dataSourceService } from './datasource.service';
export { InterfaceService, interfaceService } from './interface.service';
export { AgentService, agentService } from './agent.service';
export { CredentialService, credentialService } from './credential.service';
export { BridgeAccessService, bridgeAccessService } from './bridge-access.service';
export { ExecutionService, executionService, type StepOutputSkeleton } from './execution.service';
export { PublicationService, publicationService } from './publication.service';
export { VersionService, versionService } from './version.service';
// ActivityService removed 2026-05-08 - right-side-panel ActivityFeed +
// ActivityLog backend stack deleted (V177 migration drops the table).
export { DashboardService, dashboardService } from './dashboard.service';
export type {
  ActiveAutomation,
  ActiveAutomationSchedule,
  ActiveAutomationWebhook,
  ResourceType as ActiveAutomationResourceType,
  TriggerType as ActiveAutomationTriggerType,
} from './dashboard.service';
export { fileService, getFileUrlById, fileRefToUrl, isFileRef, findFileRefs, type FileRef } from './file.service';
export { SkillService, skillService } from './skill.service';
export { SkillFolderService, skillFolderService } from './skill-folder.service';
export { AgentToolsService, agentToolsService } from './agent-tools.service';
export { WebhookSettingsService, webhookSettingsService } from './webhook-settings.service';
export { ChatEndpointSettingsService, chatEndpointSettingsService } from './chat-endpoint-settings.service';
export { FormEndpointSettingsService, formEndpointSettingsService } from './form-endpoint-settings.service';
export { scheduleSettingsService } from './schedule-settings.service';
export type { ScheduleOverview, ScheduleConfig } from './schedule-settings.service';
export { ProjectService, projectService } from './project.service';
export { OrgAccessService, orgAccessService } from './org-access.service';
export { TaskService, taskService } from './task.service';
export type {
  Task,
  TaskNote,
  TaskEvent,
  TaskStats,
  TaskStatus,
  TaskPriority,
  TaskListParams,
  TaskListResponse,
  CreateTaskInput,
  UpdateTaskInput,
} from './task.types';
export { NodeTypeSettingsService, nodeTypeSettingsService } from './node-type-settings.service';
export type { NodeTypeSetting, ToggleResponse } from './node-type-settings.service';
export { NodeDefinitionService, nodeDefinitionService } from './node-definitions.service';
export type { NodeDefinitionDto, NodeDefinitionOutputField } from './node-definitions.service';
export { customApiService } from './custom-api.service';
export type {
  CustomApiSummary,
  CustomApiListResponse,
  CustomApiRegistrationResponse,
  CustomApiEndpointParam,
  CustomApiSynthesis,
  CustomApiPagination,
  CustomApiExecution,
  CustomApiOutputField,
  CustomApiFixture,
  CustomApiEndpoint,
  CustomApiDefinition,
  CustomApiDetails,
} from './custom-api.service';
export type { ResourceRestriction, SetRestrictionsRequest, RestrictRequest } from './org-access.service';
export type {
  Project,
  ProjectRole,
  ProjectResourceCounts,
  ProjectDetailResponse,
  CreateProjectRequest,
  UpdateProjectRequest,
  AssignResourceRequest,
  ProjectListResponse,
} from './project.types';
export type {
  StandaloneWebhook,
  CreateWebhookRequest,
  UpdateWebhookRequest,
  WebhookCallLog,
  WebhookCallLogsPage,
  WebhookConfig as WebhookSettingsConfig,
} from './webhook-settings.service';
export type {
  StandaloneChatEndpoint,
  CreateChatEndpointRequest,
  UpdateChatEndpointRequest,
  ChatEndpointAccessLog,
  ChatEndpointAccessLogsPage,
  ChatEndpointConfig,
} from './chat-endpoint-settings.service';
export type {
  StandaloneFormEndpoint,
  CreateFormEndpointRequest,
  UpdateFormEndpointRequest,
  FormSubmissionLog,
  FormSubmissionLogsPage,
  FormEndpointConfig,
} from './form-endpoint-settings.service';
export type {
  ToolParameter,
  AgentTool,
  ToolCategory,
  ToolExecutionRequest,
  ToolExecutionResult,
  AgentPrompt,
  PromptBlock,
} from './agent-tools.service';

// ============================================
// Unified API for backward compatibility
// ============================================
import { workflowService } from './workflow.service';
import { dataSourceService } from './datasource.service';
import { interfaceService } from './interface.service';
import { agentService } from './agent.service';
import { credentialService } from './credential.service';
import { executionService } from './execution.service';
import { publicationService } from './publication.service';
import { versionService } from './version.service';
import { skillService } from './skill.service';
import { skillFolderService } from './skill-folder.service';

/**
 * Unified Orchestrator API
 *
 * Combines all services into a single object for backward compatibility.
 * New code should prefer importing specific services directly.
 */
export const orchestratorApi = {
  // ========================================
  // Workflow Service
  // ========================================
  getWorkflows: workflowService.getWorkflows.bind(workflowService),
  getWorkflowsPage: workflowService.getWorkflowsPage.bind(workflowService),
  getWorkflowBoard: workflowService.getWorkflowBoard.bind(workflowService),
  getWorkflow: workflowService.getWorkflow.bind(workflowService),
  createWorkflow: workflowService.createWorkflow.bind(workflowService),
  updateWorkflow: workflowService.updateWorkflow.bind(workflowService),
  deleteWorkflow: workflowService.deleteWorkflow.bind(workflowService),
  cloneWorkflow: workflowService.cloneWorkflow.bind(workflowService),
  updateWorkflowStatus: workflowService.updateWorkflowStatus.bind(workflowService),
  saveWorkflowPlan: workflowService.saveWorkflowPlan.bind(workflowService),
  updateWorkflowPlan: workflowService.updateWorkflowPlan.bind(workflowService),
  validateWorkflow: workflowService.validateWorkflow.bind(workflowService),
  executeWorkflow: workflowService.executeWorkflow.bind(workflowService),
  executeWorkflowLegacy: workflowService.executeWorkflowLegacy.bind(workflowService),
  getRunStatus: workflowService.getRunStatus.bind(workflowService),
  getStatusCounts: workflowService.getStatusCounts.bind(workflowService),
  calculateLevels: workflowService.calculateLevels.bind(workflowService),
  getWorkflowRuns: workflowService.getWorkflowRuns.bind(workflowService),
  getLatestWorkflowRun: workflowService.getLatestWorkflowRun.bind(workflowService),
  getPinnedWorkflowRun: workflowService.getPinnedWorkflowRun.bind(workflowService),
  getRun: workflowService.getRun.bind(workflowService),
  startWorkflowRun: workflowService.startWorkflowRun.bind(workflowService),
  getAllRunSteps: workflowService.getAllRunSteps.bind(workflowService),
  getRunStepsPaged: workflowService.getRunStepsPaged.bind(workflowService),
  getRunSteps: workflowService.getRunSteps.bind(workflowService),
  getStepSnapshot: workflowService.getStepSnapshot.bind(workflowService),
  getAggregatedSteps: workflowService.getAggregatedSteps.bind(workflowService),
  deleteRun: workflowService.deleteRun.bind(workflowService),

  // ========================================
  // DataSource Service
  // ========================================
  getDataSources: dataSourceService.getDataSources.bind(dataSourceService),
  getDataSource: dataSourceService.getDataSource.bind(dataSourceService),
  createDataSource: dataSourceService.createDataSource.bind(dataSourceService),
  updateDataSource: dataSourceService.updateDataSource.bind(dataSourceService),
  deleteDataSource: dataSourceService.deleteDataSource.bind(dataSourceService),
  cloneDataSource: dataSourceService.cloneDataSource.bind(dataSourceService),
  createDemoDataSource: dataSourceService.createDemoDataSource.bind(dataSourceService),
  getColumns: dataSourceService.getColumns.bind(dataSourceService),
  getNestedColumns: dataSourceService.getNestedColumns.bind(dataSourceService),
  createColumn: dataSourceService.createColumn.bind(dataSourceService),
  updateColumn: dataSourceService.updateColumn.bind(dataSourceService),
  deleteColumn: dataSourceService.deleteColumn.bind(dataSourceService),
  bulkUpdateColumns: dataSourceService.bulkUpdateColumns.bind(dataSourceService),
  updateColumnOrder: dataSourceService.updateColumnOrder.bind(dataSourceService),
  getItems: dataSourceService.getItems.bind(dataSourceService),
  createItem: dataSourceService.createItem.bind(dataSourceService),
  updateItem: dataSourceService.updateItem.bind(dataSourceService),
  deleteItem: dataSourceService.deleteItem.bind(dataSourceService),
  bulkUpdateItems: dataSourceService.bulkUpdateItems.bind(dataSourceService),
  getNestedItems: dataSourceService.getNestedItems.bind(dataSourceService),
  updateNestedItem: dataSourceService.updateNestedItem.bind(dataSourceService),
  deleteNestedItem: dataSourceService.deleteNestedItem.bind(dataSourceService),
  addNestedItem: dataSourceService.addNestedItem.bind(dataSourceService),
  getTables: dataSourceService.getTables.bind(dataSourceService),
  exportDataSource: dataSourceService.exportDataSource.bind(dataSourceService),

  // ========================================
  // Interface Service
  // ========================================
  getInterfaces: interfaceService.getInterfaces.bind(interfaceService),
  getInterface: interfaceService.getInterface.bind(interfaceService),
  createInterface: interfaceService.createInterface.bind(interfaceService),
  updateInterface: interfaceService.updateInterface.bind(interfaceService),
  deleteInterface: interfaceService.deleteInterface.bind(interfaceService),
  cloneInterface: interfaceService.cloneInterface.bind(interfaceService),
  renderInterface: interfaceService.renderInterface.bind(interfaceService),
  getInterfaceSnapshot: interfaceService.getInterfaceSnapshot.bind(interfaceService),
  getInterfaceSnapshotsForRun: interfaceService.getInterfaceSnapshotsForRun.bind(interfaceService),
  getInterfaceItemsCount: interfaceService.getInterfaceItemsCount.bind(interfaceService),
  renderInterfaceWithDatasource: interfaceService.renderInterfaceWithDatasource.bind(interfaceService),
  getDatasourceItemsCount: interfaceService.getDatasourceItemsCount.bind(interfaceService),
  getInterfaceItem: interfaceService.getInterfaceItem.bind(interfaceService),
  getInterfaceRunInfo: interfaceService.getInterfaceRunInfo.bind(interfaceService),

  // ========================================
  // Agent Service
  // ========================================
  getAgents: agentService.getAgents.bind(agentService),
  getAgentAvatars: agentService.getAgentAvatars.bind(agentService),
  getFleetTriggers: agentService.getFleetTriggers.bind(agentService),
  getAgent: agentService.getAgent.bind(agentService),
  getAgentByConversationId: agentService.getAgentByConversationId.bind(agentService),
  createAgent: agentService.createAgent.bind(agentService),
  updateAgent: agentService.updateAgent.bind(agentService),
  deleteAgent: agentService.deleteAgent.bind(agentService),
  cloneAgent: agentService.cloneAgent.bind(agentService),
  // Agent Webhooks
  createOrUpdateAgentWebhook: agentService.createOrUpdateWebhook.bind(agentService),
  getAgentWebhook: agentService.getWebhook.bind(agentService),
  regenerateAgentWebhookToken: agentService.regenerateWebhookToken.bind(agentService),
  setAgentWebhookActive: agentService.setWebhookActive.bind(agentService),
  deleteAgentWebhook: agentService.deleteWebhook.bind(agentService),
  // Agent Widget Configuration
  createOrUpdateWidgetConfig: agentService.createOrUpdateWidgetConfig.bind(agentService),
  getWidgetConfig: agentService.getWidgetConfig.bind(agentService),
  setWidgetActive: agentService.setWidgetActive.bind(agentService),
  deleteWidgetConfig: agentService.deleteWidgetConfig.bind(agentService),

  // ========================================
  // Credential Service
  // ========================================
  getCredentials: credentialService.getCredentials.bind(credentialService),
  getCredential: credentialService.getCredential.bind(credentialService),
  createCredential: credentialService.createCredential.bind(credentialService),
  updateCredential: credentialService.updateCredential.bind(credentialService),
  deleteCredential: credentialService.deleteCredential.bind(credentialService),
  getAllCredentials: credentialService.getAllCredentials.bind(credentialService),
  setDefaultCredential: credentialService.setDefaultCredential.bind(credentialService),
  clearDefaultCredential: credentialService.clearDefaultCredential.bind(credentialService),
  getCredentialsByIntegration: credentialService.getCredentialsByIntegration.bind(credentialService),
  getCredentialTemplates: credentialService.getCredentialTemplates.bind(credentialService),
  getCredentialTemplate: credentialService.getCredentialTemplate.bind(credentialService),
  getCredentialTemplateByName: credentialService.getCredentialTemplateByName.bind(credentialService),
  getCredentialVariants: credentialService.getCredentialVariants.bind(credentialService),
  initiateOAuth2: credentialService.initiateOAuth2.bind(credentialService),
  initiateOAuth2Simple: credentialService.initiateOAuth2Simple.bind(credentialService),
  getPlatformCredentialsAvailability: credentialService.getPlatformCredentialsAvailability.bind(credentialService),
  hasPlatformCredentials: credentialService.hasPlatformCredentials.bind(credentialService),
  saveTenantPlatformCredential: credentialService.saveTenantPlatformCredential.bind(credentialService),
  getMyOAuthApps: credentialService.getMyOAuthApps.bind(credentialService),
  getDeleteImpact: credentialService.getDeleteImpact.bind(credentialService),
  deleteMyOAuthApp: credentialService.deleteMyOAuthApp.bind(credentialService),
  getPlatformCredentialPublicInfo: credentialService.getPlatformCredentialPublicInfo.bind(credentialService),
  refreshOAuth2Token: credentialService.refreshOAuth2Token.bind(credentialService),

  // ========================================
  // Execution Service
  // ========================================
  getRunState: executionService.getRunState.bind(executionService),
  pauseWorkflow: executionService.pauseWorkflow.bind(executionService),
  resumeWorkflow: executionService.resumeWorkflow.bind(executionService),
  cancelWorkflow: executionService.cancelWorkflow.bind(executionService),
  stopWorkflow: executionService.stopWorkflow.bind(executionService),
  reactivateWorkflow: executionService.reactivateWorkflow.bind(executionService),
  isPaused: executionService.isPaused.bind(executionService),
  executeSingleStep: executionService.executeSingleStep.bind(executionService),
  getReadySteps: executionService.getReadySteps.bind(executionService),
  setExecutionMode: executionService.setExecutionMode.bind(executionService),
  getExecutionMode: executionService.getExecutionMode.bind(executionService),
  startInStepByStepMode: executionService.startInStepByStepMode.bind(executionService),
  executeCore: executionService.executeCore.bind(executionService),
  executeSingleStepInStepByStepMode: executionService.executeSingleStepInStepByStepMode.bind(executionService),
  triggerManual: executionService.triggerManual.bind(executionService),
  triggerChat: executionService.triggerChat.bind(executionService),
  triggerForm: executionService.triggerForm.bind(executionService),
  triggerDatasource: executionService.triggerDatasource.bind(executionService),
  getStepData: executionService.getStepData.bind(executionService),
  rerunFromStep: executionService.rerunFromStep.bind(executionService),
  getStepHistory: executionService.getStepHistory.bind(executionService),
  resolveSignal: executionService.resolveSignal.bind(executionService),
  resolveAllSignals: executionService.resolveAllSignals.bind(executionService),
  // Multi-DAG trigger support
  getAvailableTriggers: executionService.getAvailableTriggers.bind(executionService),
  triggerSpecific: executionService.triggerSpecific.bind(executionService),
  // Per-epoch aggregated steps (node timing)
  getEpochAggregatedSteps: executionService.getAggregatedSteps.bind(executionService),
  // Per-epoch active signals (for epoch state viewing on canvas)
  getEpochSignals: executionService.getEpochSignals.bind(executionService),
  // Per-epoch pre-aggregated node+edge status counts
  getEpochState: executionService.getEpochState.bind(executionService),
  // Update a run's plan (save-in-run mode, no version creation)
  updateRunPlan: executionService.updateRunPlan.bind(executionService),

  // Execution service object for direct access to new methods
  execution: executionService,

  // ========================================
  // Publication Service
  // ========================================
  publishWorkflow: publicationService.publishWorkflow.bind(publicationService),
  updatePublication: publicationService.updatePublication.bind(publicationService),
  unpublishWorkflow: publicationService.unpublishWorkflow.bind(publicationService),
  deletePublication: publicationService.deletePublication.bind(publicationService),
  getPublicationByWorkflowId: publicationService.getPublicationByWorkflowId.bind(publicationService),
  getPublicationById: publicationService.getPublicationById.bind(publicationService),
  getMyPublications: publicationService.getMyPublications.bind(publicationService),
  getMarketplacePublications: publicationService.getMarketplacePublications.bind(publicationService),
  getByPublisher: publicationService.getByPublisher.bind(publicationService),
  searchPublications: publicationService.searchPublications.bind(publicationService),
  getPopularPublications: publicationService.getPopularPublications.bind(publicationService),
  getReviews: publicationService.getReviews.bind(publicationService),
  getMyReview: publicationService.getMyReview.bind(publicationService),
  submitReview: publicationService.submitReview.bind(publicationService),
  deleteReview: publicationService.deleteReview.bind(publicationService),
  getReplies: publicationService.getReplies.bind(publicationService),
  submitReply: publicationService.submitReply.bind(publicationService),
  updateReply: publicationService.updateReply.bind(publicationService),
  deleteReply: publicationService.deleteReply.bind(publicationService),
  getCategories: publicationService.getCategories.bind(publicationService),
  getCategoryById: publicationService.getCategoryById.bind(publicationService),
  getCategoryBySlug: publicationService.getCategoryBySlug.bind(publicationService),

  // ========================================
  // Version Service
  // ========================================
  listVersions: versionService.listVersions.bind(versionService),
  getVersion: versionService.getVersion.bind(versionService),
  restoreVersion: versionService.restoreVersion.bind(versionService),
  renameVersion: versionService.renameVersion.bind(versionService),
  pinVersion: versionService.pinVersion.bind(versionService),

  // ========================================
  // Skill Service
  // ========================================
  getSkills: skillService.getSkills.bind(skillService),
  getSkill: skillService.getSkill.bind(skillService),
  createSkill: skillService.createSkill.bind(skillService),
  updateSkill: skillService.updateSkill.bind(skillService),
  deleteSkill: skillService.deleteSkill.bind(skillService),
  getAgentSkills: skillService.getAgentSkills.bind(skillService),
  setAgentSkills: skillService.setAgentSkills.bind(skillService),
  moveSkill: skillService.moveSkill.bind(skillService),
  resetSkill: skillService.resetSkill.bind(skillService),
  // V275/V276 (2026-05-21) - per-user override + admin folder global
  setUserSkillActive: skillService.setUserSkillActive.bind(skillService),
  clearUserSkillActive: skillService.clearUserSkillActive.bind(skillService),
  getMySkillOverrides: skillService.getMyOverrides.bind(skillService),
  getDefaultActiveSkillsSummary: skillService.getDefaultActiveSummary.bind(skillService),
  setSkillFolderGlobal: skillService.setFolderGlobal.bind(skillService),

  // ========================================
  // Skill Folder Service
  // ========================================
  getAllSkillFolders: skillFolderService.getAllFolders.bind(skillFolderService),
  createSkillFolder: skillFolderService.createFolder.bind(skillFolderService),
  renameSkillFolder: skillFolderService.renameFolder.bind(skillFolderService),
  deleteSkillFolder: skillFolderService.deleteFolder.bind(skillFolderService),
  moveSkillFolder: skillFolderService.moveFolder.bind(skillFolderService),
  getSkillFolderContents: skillFolderService.getFolderContents.bind(skillFolderService),
  getSkillRootContents: skillFolderService.getRootContents.bind(skillFolderService),
};

// Export the class for testing
export class OrchestratorApi {
  // Provide access to individual services for testing
  readonly workflow = workflowService;
  readonly dataSource = dataSourceService;
  readonly interface = interfaceService;
  readonly agent = agentService;
  readonly credential = credentialService;
  readonly execution = executionService;
  readonly publication = publicationService;
  readonly version = versionService;
}
