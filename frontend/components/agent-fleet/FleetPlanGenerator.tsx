'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { FileJson, Copy, Check, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import type { Agent, AgentSkill } from '@/lib/api/orchestrator/types';
import { getAllowedIds, getToolsMode, isWebSearchEnabled, type InternalResourceKey } from '@/lib/agents/toolsConfigAccess';

interface FleetPlanGeneratorProps {
  agents: Agent[];
  skillsByAgent: Map<string, AgentSkill[]>;
  workflowNames: Map<string, string>;
  interfaceNames: Map<string, string>;
  dataSourceNames: Map<string, string>;
}

/**
 * Resolves the LLM-facing resource access label for a single internal category.
 * Security rule: absent key === [] === "none" (NOT "all"). The plan generator's
 * output is consumed by an LLM that may write workflows/instructions based on
 * what it sees - emitting "all" for an absent key would invite the LLM to
 * assume scope it doesn't actually have.
 */
function resolveResourceAccess(
  tc: any,
  field: InternalResourceKey,
  nameMap: Map<string, string>,
): 'none' | string[] {
  const ids = getAllowedIds(tc, field);
  if (ids.length === 0) return 'none';
  return ids.map(id => nameMap.get(id) || id);
}

/**
 * Generates a structured fleet plan from agent data for LLM consumption.
 *
 * toolsConfig contract (post-V163 + AgentService.normalizeToolsConfig):
 *   .mode      → 'all'|'custom'|'none' for MCP catalogue tools (defaults to 'all')
 *   .workflows → [] === absent === NO access; [ids] === explicit grants only
 *   .tables    → same rule
 *   .interfaces → same rule
 *   .agents    → same rule
 *   .applications → same rule
 *   null tc    → MCP defaults to 'all', the 5 internal lists default to [] (no access).
 *
 * The plan emitted here goes to an LLM - it MUST NEVER claim "all" for the 5
 * internal categories. Use `resolveResourceAccess` (returns 'none' for absent/[]).
 */
function generateFleetPlan(
  agents: Agent[],
  skillsByAgent: Map<string, AgentSkill[]>,
  workflowNames: Map<string, string>,
  interfaceNames: Map<string, string>,
  dataSourceNames: Map<string, string>,
) {
  const agentNameById = new Map<string, string>();
  agents.forEach(a => agentNameById.set(a.id, a.name));

  const agentPlans = agents.map(agent => {
    const tc = agent.toolsConfig;
    const skills = skillsByAgent.get(agent.id) || [];

    const plan: Record<string, any> = {
      name: agent.name,
    };

    if (agent.description) plan.description = agent.description;

    // Model info
    const model = [agent.modelProvider, agent.modelName].filter(Boolean).join('/');
    if (model) plan.model = model;

    if (agent.systemPrompt) plan.system_prompt = agent.systemPrompt;
    if (agent.temperature != null) plan.temperature = agent.temperature;
    if (agent.maxTokens != null) plan.max_tokens = agent.maxTokens;
    if (agent.maxIterations != null) plan.max_iterations = agent.maxIterations;

    // Skills
    if (skills.length > 0) {
      plan.skills = skills.map(s => s.skill?.name || s.skillId);
    }

    // Tools (MCP) - mode IS allowed to default to 'all' (product behavior).
    const toolsMode = getToolsMode(tc);
    if (toolsMode === 'all') {
      plan.tools = 'all';
    } else if (toolsMode === 'none') {
      plan.tools = 'none';
    } else if (toolsMode === 'custom') {
      plan.tools = Array.isArray(tc?.tools) ? tc.tools : [];
    }

    // Resource access - absent key === 'none' (security rule). The LLM-facing
    // plan must NEVER claim "all" for the 5 internal lists; the agent's true
    // scope is exactly what's been explicitly granted. `applications` (UUIDs of
    // marketplace apps) is included for completeness - name resolution is best-effort
    // since this generator doesn't currently receive an applications name map; the
    // raw IDs are returned in that case.
    const emptyNameMap = new Map<string, string>();
    plan.resource_access = {
      workflows:    resolveResourceAccess(tc, 'workflows',    workflowNames),
      interfaces:   resolveResourceAccess(tc, 'interfaces',   interfaceNames),
      tables:       resolveResourceAccess(tc, 'tables',       dataSourceNames),
      agents:       resolveResourceAccess(tc, 'agents',       agentNameById),
      applications: resolveResourceAccess(tc, 'applications', emptyNameMap),
      web_search:   isWebSearchEnabled(tc),
    };

    // Sub-agents (explicit delegation) - only emit when explicitly granted
    const subAgents = getAllowedIds(tc, 'agents')
      .map(id => agentNameById.get(id) || id);
    if (subAgents.length > 0) plan.sub_agents = subAgents;

    // Raw toolsConfig for debug
    plan._raw_toolsConfig = tc ?? null;

    return plan;
  });

  return { agents: agentPlans };
}

export function FleetPlanGenerator({
  agents,
  skillsByAgent,
  workflowNames,
  interfaceNames,
  dataSourceNames,
}: FleetPlanGeneratorProps) {
  const [isOpen, setIsOpen] = React.useState(false);
  const [planJson, setPlanJson] = React.useState('');
  const [copied, setCopied] = React.useState(false);

  const handleGenerate = React.useCallback(() => {
    try {
      const plan = generateFleetPlan(agents, skillsByAgent, workflowNames, interfaceNames, dataSourceNames);
      setPlanJson(JSON.stringify(plan, null, 2));
      setIsOpen(true);
    } catch (error) {
      setPlanJson(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
      setIsOpen(true);
    }
  }, [agents, skillsByAgent, workflowNames, interfaceNames, dataSourceNames]);

  const handleCopy = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(planJson);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('Failed to copy:', error);
    }
  }, [planJson]);

  return (
    <>
      <Button
        onClick={handleGenerate}
        variant="default"
        size="sm"
        className="w-full flex items-center justify-center gap-2"
        title="Generate Fleet Plan"
      >
        <FileJson className="h-4 w-4" />
        Generate Plan
      </Button>

      {isOpen && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setIsOpen(false)}
        >
          <div
            className="max-w-4xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="px-8 pt-8 pb-4 flex items-start justify-between">
              <div>
                <h3 className="text-lg font-semibold text-theme-primary flex items-center gap-2">
                  <FileJson className="h-5 w-5 text-theme-secondary" />
                  Fleet Plan JSON
                </h3>
                <p className="text-sm text-theme-secondary mt-1">
                  Agent fleet structure with resource access and connections
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8 rounded-full shrink-0"
                onClick={() => setIsOpen(false)}
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto px-8 pb-4 min-h-0">
              <Textarea
                value={planJson}
                readOnly
                className="min-h-[400px] font-mono text-sm"
                style={{ resize: 'none' }}
              />
            </div>

            {/* Footer */}
            <div className="px-8 py-4 border-t border-theme flex justify-end">
              <Button
                onClick={handleCopy}
                variant="outline"
                className="flex items-center gap-2"
              >
                {copied ? (
                  <>
                    <Check className="h-4 w-4" />
                    Copied!
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4" />
                    Copy
                  </>
                )}
              </Button>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
