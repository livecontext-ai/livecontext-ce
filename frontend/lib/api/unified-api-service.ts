/**
 * Unified API Service - Facade
 * Maintains backward compatibility by delegating to domain-specific services.
 *
 * Architecture: This is a Facade Pattern implementation.
 * Each domain service handles its own responsibility:
 * - BillingApiService: Billing, subscriptions, quotas
 * - CatalogApiService: Categories, tool catalogs
 * - ChatApiService: Chat, streaming, messages
 * - DeveloperApiService: API management, developer profiles
 * - ToolsApiService: Tool operations, mapping
 * - UserApiService: User profile, status, authentication
 *
 * ALL services use apiClient for HTTP requests (unified auth system).
 */

import { apiClient } from './api-client';
import { BillingApiService } from './services/billing-api.service';
import { CatalogApiService } from './services/catalog-api.service';
import { ChatApiService } from './services/chat-api.service';
import { DeveloperApiService } from './services/developer-api.service';
import { ToolsApiService } from './services/tools-api.service';
import { UserApiService } from './services/user-api.service';

// Re-export types for backward compatibility
export type { ToolRuntimeMetadata } from '@/types/runtimeMetadata';
export type {
  CatalogTool,
  CatalogToolListResponse,
  CatalogToolExecutionPayload,
  CatalogIntentResolutionResponse,
  CategoryResponse,
  SubcategoryResponse,
  ToolCategory,
  ToolName,
} from './services/catalog-api.service';

export type {
  ToolResponse,
  ToolCategoryInfo,
  ParameterResponse,
  MonetizationResponse,
  ToolTestResult,
  ToolPerformanceMetrics,
} from './services/tools-api.service';

export type {
  UnifiedApiResponse,
  DeveloperProfile,
  McpServer,
  ApiResponse,
} from './services/developer-api.service';

export type {
  PlanChangeRequest,
  PlanChangeResponse,
  ScheduledChange,
  ScheduledChangeResponse,
} from './services/billing-api.service';

export type {
  ChatMessagePayload,
  StreamEventData,
} from './services/chat-api.service';

// Utility functions (kept here for backward compatibility)
export const extractApiData = (apiResponse: any) => {
  return apiResponse?.data || apiResponse;
};

export const enrichTools = (tools: any[], api: any) => {
  if (!tools || !Array.isArray(tools)) return [];

  return tools.map(tool => ({
    ...tool,
    status: tool.status || api?.status || 'Unknown',
    toolCategory: tool.toolCategory || tool.toolCategories?.name || tool.category,
    category: tool.category || tool.toolCategories?.name || tool.toolCategory,
    subcategory: tool.subcategory || tool.toolCategories?.slug
  }));
};

/**
 * Unified API Service - Facade
 * Delegates to domain-specific services for clean architecture.
 * All services use apiClient internally for HTTP requests.
 */
export class UnifiedApiService {
  private static instance: UnifiedApiService;

  // Domain-specific services (all use apiClient internally)
  private billingService: BillingApiService;
  private catalogService: CatalogApiService;
  private chatService: ChatApiService;
  private developerService: DeveloperApiService;
  private toolsService: ToolsApiService;
  private userService: UserApiService;

  constructor() {
    // Initialize domain services - all use apiClient internally
    this.billingService = new BillingApiService();
    this.catalogService = new CatalogApiService();
    this.chatService = new ChatApiService();
    this.developerService = new DeveloperApiService();
    this.toolsService = new ToolsApiService();
    this.userService = new UserApiService();
  }

  static getInstance(): UnifiedApiService {
    if (!UnifiedApiService.instance) {
      UnifiedApiService.instance = new UnifiedApiService();
    }
    return UnifiedApiService.instance;
  }

  // ==================== Developer API Methods ====================

  getUserApis = () => this.developerService.getUserApis();
  getApiById = (apiId: string) => this.developerService.getApiById(apiId);
  updateApiBasicInfo = (apiId: string, basicInfo: any) => this.developerService.updateApiBasicInfo(apiId, basicInfo);
  updateApiConfig = (apiId: string, config: any) => this.developerService.updateApiConfig(apiId, config);
  getMonetizationState = () => this.developerService.getMonetizationState();
  updatePricingModels = (apiId: string, pricingData: any) => this.developerService.updatePricingModels(apiId, pricingData);
  updateToolFreemiumConfig = (apiId: string, toolId: string, config: any) => this.developerService.updateToolFreemiumConfig(apiId, toolId, config);
  updateBatchFreemiumConfig = (apiId: string, config: any) => this.developerService.updateBatchFreemiumConfig(apiId, config);
  updateToolPaidConfig = (apiId: string, toolId: string, config: any) => this.developerService.updateToolPaidConfig(apiId, toolId, config);
  updateBatchPaidConfig = (apiId: string, config: any) => this.developerService.updateBatchPaidConfig(apiId, config);
  updatePaidPlans = (apiId: string, plans: any) => this.developerService.updatePaidPlans(apiId, plans);
  getMyTools = () => this.developerService.getMyTools();
  getPublicTools = () => this.developerService.getPublicTools();
  getToolById = (toolId: string) => this.developerService.getToolById(toolId);
  updateApiTool = (apiId: string, toolId: string, toolData: any) => this.developerService.updateApiTool(apiId, toolId, toolData);
  checkApiNameUniquenes = (apiName: string) => this.developerService.checkApiNameUniqueness(apiName);
  processApiConfiguration = (configuration: any, userId: string, accessToken: string) => this.developerService.processApiConfiguration(configuration, userId, accessToken);

  // ==================== Catalog API Methods ====================

  getCategories = () => this.catalogService.getCategories();
  getSubcategories = (categoryId: string) => this.catalogService.getSubcategories(categoryId);
  getToolNames = () => this.catalogService.getToolNames();
  getToolCategories = () => this.catalogService.getToolCategories();
  getCatalogTools = (options?: { limit?: number; category?: string; search?: string }) => this.catalogService.getCatalogTools(options);
  executeCatalogTool = (toolId: string, payload?: any) => this.catalogService.executeCatalogTool(toolId, payload);
  resolveCatalogIntent = (query: string, limit?: number) => this.catalogService.resolveCatalogIntent(query, limit);
  getToolNamesByCategory = (categoryId: string) => this.catalogService.getToolNamesByCategory(categoryId);
  getToolNamesBySubcategory = (subcategoryId: string) => this.catalogService.getToolNamesBySubcategory(subcategoryId);
  getToolNamesByToolCategoryAndSubcategory = (toolCategory: string, subcategoryId: string) => this.catalogService.getToolNamesByToolCategoryAndSubcategory(toolCategory, subcategoryId);

  // ==================== User API Methods ====================

  getUserStatus = () => this.userService.getUserStatus();
  getUserProfile = () => this.userService.getUserProfile();
  updateUserProfile = (profileData: any) => this.userService.updateUserProfile(profileData);
  getPublicProfileByHandle = (handle: string) => this.userService.getPublicProfileByHandle(handle);
  getPublicProfileById = (userId: string | number) => this.userService.getPublicProfileById(userId);
  getRemotePublicProfileById = (userId: string | number) => this.userService.getRemotePublicProfileById(userId);
  checkUsername = (username: string) => this.userService.checkUsername(username);
  checkDisplayName = (displayName: string) => this.userService.checkDisplayName(displayName);
  getDisplayNameStatus = () => this.userService.getDisplayNameStatus();
  getHandleStatus = () => this.userService.getHandleStatus();
  deleteAccount = () => this.userService.deleteAccount();

  // ==================== Tools API Methods ====================

  getToolResponses = (toolId: string) => this.toolsService.getToolResponses(toolId);
  updateToolResponse = (toolId: string, responseId: string, responseData: any) => this.toolsService.updateToolResponse(toolId, responseId, responseData);
  createToolResponse = (toolId: string, responseData: any) => this.toolsService.createToolResponse(toolId, responseData);
  testExternalEndpoint = (endpoint: string, method: string, headers: any, body: any) => this.toolsService.testExternalEndpoint(endpoint, method, headers, body);

  // ==================== Billing API Methods ====================

  getBillingData = () => this.billingService.getBillingData();
  createCheckout = (planData: any) => this.billingService.createCheckout(planData);
  getAvailablePlans = () => this.billingService.getAvailablePlans();
  finalizeCheckout = (sessionId: string) => this.billingService.finalizeCheckout(sessionId);
  createSubscription = (planCode: string, billingCycle: 'monthly' | 'yearly') => this.billingService.createSubscription(planCode, billingCycle);
  openBillingPortal = (returnUrl?: string) => this.billingService.openBillingPortal(returnUrl);
  getBillingDataWithCache = () => this.billingService.getBillingDataWithCache();
  forceRefreshBillingData = () => this.billingService.forceRefreshBillingData();
  changePlan = (request: any) => this.billingService.changePlan(request);
  scheduleDowngrade = (targetPlanCode: string) => this.billingService.scheduleDowngrade(targetPlanCode);
  changeBillingCycle = (billingCycle: 'monthly' | 'yearly') => this.billingService.changeBillingCycle(billingCycle);
  changeCreditTier = (creditTierIndex: number) => this.billingService.changeCreditTier(creditTierIndex);
  getScheduledChange = () => this.billingService.getScheduledChange();
  cancelScheduledChange = () => this.billingService.cancelScheduledChange();
  cancelSubscription = (reason: string, feedback?: string) => this.billingService.cancelSubscription(reason, feedback);
  reactivateSubscription = () => this.billingService.reactivateSubscription();
  getInvoices = () => this.billingService.getInvoices();
  // V250 - PAYG one-time top-up
  getPaygTiers = () => this.billingService.getPaygTiers();
  createPaygCheckout = (tier: 'small' | 'medium' | 'large') => this.billingService.createPaygCheckout(tier);

  // ==================== Chat API Methods ====================

  stopChatStream = (conversationId: string) => this.chatService.stopChatStream(conversationId);
  sendChatMessageWs = (
    message: string,
    model?: string,
    provider?: string,
    conversationId?: string,
    history?: Array<{ role: string; content: string }>,
    attachments?: Array<{ storageId: string; type: string; fileName: string; mimeType: string }>,
    agentId?: string,
    defaultSkillIds?: string[],
    chatConfig?: Record<string, unknown>,
    source?: string,
    taskId?: string,
    reasoningEffort?: string,
    keepPendingActions?: boolean
  ) => this.chatService.sendChatMessageWs(message, model, provider, conversationId, history, attachments, agentId, defaultSkillIds, chatConfig, source, taskId, reasoningEffort, keepPendingActions);
  getStreamReconnectionState = (conversationId: string) => this.chatService.getStreamReconnectionState(conversationId);
  getStreamStatus = (conversationId: string) => this.chatService.getStreamStatus(conversationId);
  stopStream = (streamId: string) => this.chatService.stopStream(streamId);
  getActiveStreamingConversations = () => this.chatService.getActiveStreamingConversations();
}

// Export singleton instance
export const unifiedApiService = UnifiedApiService.getInstance();
