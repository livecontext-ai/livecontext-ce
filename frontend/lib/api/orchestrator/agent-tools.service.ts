/**
 * Agent Tools Service
 *
 * Handles agent tools discovery, execution, and prompts visualization.
 * Used by the Agent Debug settings page.
 */

import { apiClient } from '../api-client';

// ============================================
// Types
// ============================================

export interface ToolParameter {
  name: string;
  type: string;
  description: string;
  required: boolean;
  defaultValue?: unknown;
  enumValues?: string[];
}

export interface AgentTool {
  name: string;
  description: string;
  category: string;
  parameters: ToolParameter[];
  requiredParameters: string[];
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  examples?: string[];
  helpText?: string;
  requiresAuth?: boolean;
  tags?: string[];
}

export interface ToolCategory {
  slug: string;
  name: string;
  description: string;
  toolCount?: number;
  tools?: string[];
}

export interface ToolExecutionRequest {
  tool: string;
  parameters: Record<string, unknown>;
  conversationId?: string;
  approvedServices?: string[];
  viewingWorkflowId?: string;
  viewingWorkflowName?: string;
}

export interface ToolExecutionResult {
  success: boolean;
  tool: string;
  data?: unknown;
  error?: string;
  errorCode?: string;
  errorType?: string;
  metadata?: Record<string, unknown>;
}

export interface AgentPrompt {
  name: string;
  description: string;
  content?: string;
  isDefault?: boolean;
  tokenEstimate: number;
  lineCount: number;
  preview?: string;
}

export interface PromptBlock {
  description: string;
  content: string;
  usedIn: string[];
}

// ============================================
// Service
// ============================================

export class AgentToolsService {
  /**
   * Get all available tools
   */
  async getTools(category?: string): Promise<{ tools: AgentTool[]; count: number; categories: ToolCategory[] }> {
    const params = category ? `?category=${category}` : '';
    return apiClient.get(`/agent-tools${params}`);
  }

  /**
   * Get tool details by name
   */
  async getTool(name: string): Promise<AgentTool> {
    return apiClient.get(`/agent-tools/${name}`);
  }

  /**
   * Get all categories with tool counts
   */
  async getCategories(): Promise<{ categories: ToolCategory[]; count: number }> {
    return apiClient.get('/agent-tools/categories');
  }

  /**
   * Get tools by category
   */
  async getToolsByCategory(category: string): Promise<{ category: ToolCategory; tools: AgentTool[]; count: number }> {
    return apiClient.get(`/agent-tools/category/${category}`);
  }

  /**
   * Search tools by query
   */
  async searchTools(query: string, maxResults = 10): Promise<{ query: string; tools: AgentTool[]; count: number }> {
    return apiClient.get(`/agent-tools/search?q=${encodeURIComponent(query)}&max=${maxResults}`);
  }

  /**
   * Get tool input schema
   */
  async getToolSchema(name: string): Promise<Record<string, unknown>> {
    return apiClient.get(`/agent-tools/${name}/schema`);
  }

  /**
   * Get tool examples
   */
  async getToolExamples(name: string): Promise<{ name: string; examples: string[]; helpText: string }> {
    return apiClient.get(`/agent-tools/${name}/examples`);
  }

  /**
   * Execute a tool
   */
  async executeTool(request: ToolExecutionRequest): Promise<ToolExecutionResult> {
    return apiClient.post('/agent-tools/execute', request);
  }

  /**
   * Get all system prompts
   */
  async getPrompts(): Promise<{ prompts: AgentPrompt[]; count: number }> {
    return apiClient.get('/agent-prompts');
  }

  /**
   * Get a specific prompt by name
   */
  async getPrompt(name: string): Promise<AgentPrompt> {
    return apiClient.get(`/agent-prompts/${name}`);
  }

  /**
   * Get prompt building blocks
   */
  async getPromptBlocks(): Promise<{ blocks: Record<string, PromptBlock>; count: number }> {
    return apiClient.get('/agent-prompts/blocks');
  }

  /**
   * Build a workflow-specific prompt preview
   */
  async buildWorkflowPrompt(context: {
    workflowName?: string;
    workflowId?: string;
    workflowStatus?: string;
    flowDiagram?: string;
    datasourceId?: string;
    lastRunInfo?: string;
  }): Promise<AgentPrompt & { context: Record<string, string> }> {
    return apiClient.post('/agent-prompts/build-workflow', context);
  }
}

export const agentToolsService = new AgentToolsService();
