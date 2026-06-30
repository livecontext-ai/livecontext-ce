/**
 * Agent config snapshot captured at workflow execution time.
 * Used to display agent configuration and run results in the Agent tab.
 */
export interface AgentSnapshotConfig {
  /** Workflow node ID, e.g. "agent:sales_agent" */
  nodeId: string;
  /** Display name from the workflow node */
  nodeLabel: string;
  /** Agent entity UUID (null for inline-configured agents) */
  agentConfigId?: string;
  /** Agent entity name from Fleet */
  agentName?: string;
  /** Avatar URL or preset identifier */
  avatarUrl?: string;

  /** Frozen snapshot from agent_config_snapshot in run output (null if not yet executed) */
  snapshot?: {
    provider?: string;
    model?: string;
    temperature?: number;
    maxTokens?: number;
    maxIterations?: number;
    withMemory?: boolean;
    toolsMode?: string;
    tools?: string[];
    allowedTables?: string[];
    allowedInterfaces?: string[];
    allowedAgents?: string[];
    allowedWorkflows?: string[];
    enabledModules?: string[];
    systemPromptHash?: string;
    agentDescription?: string;
  };

  /** Run result data extracted from step output */
  runResult?: {
    iterations?: number;
    toolCalls?: number;
    tokensUsed?: number;
    durationMs?: number;
    toolCallsDetail?: Array<{
      toolName: string;
      toolCallId: string;
      success: boolean;
      durationMs: number;
    }>;
  };
}
