import * as React from 'react';
import Image from 'next/image';
import { Info, MemoryStick } from 'lucide-react';
import { AvatarDisplay } from '@/components/agents';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ExpressionField, ConnectionProps } from './ExpressionField';
import { OptionalSection } from './OptionalSection';
import { ModelPicker } from '@/components/ai/ModelPicker';
import type { SelectedModel } from '@/hooks/useModels';
import { getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { useTranslations } from 'next-intl';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { Agent as AgentEntity } from '@/lib/api/orchestrator/types';
import type { Node, Edge } from 'reactflow';

interface ConnectedTool {
    id: string;
    name: string;
    apiName?: string;
    toolSlug?: string;
    iconSlug?: string;
}

interface AgentConfigurationPanelProps {
    node: any;
    data: any;
    onUpdate: (data: any) => void;
    isRunMode?: boolean;
    connectionProps: ConnectionProps;
    findUnknownVariables: (expressions: Record<string, string>) => string[];
    getParamExpression: (key: string) => string;
    handleParamExpressionChange: (key: string, value: string) => void;
    showOptionalParams: boolean;
    setShowOptionalParams: (show: boolean) => void;
    allNodes?: Node[];
    edges?: Edge[];
}

// Default values
const DEFAULT_TEMPERATURE = 0.7;
const DEFAULT_MAX_TOKENS = 4096;
const DEFAULT_MAX_ITERATIONS = 10;
const DEFAULT_MAX_TOOLS = 5;

export function AgentConfigurationPanel({
    node,
    data,
    onUpdate,
    isRunMode = false,
    connectionProps,
    findUnknownVariables,
    getParamExpression,
    handleParamExpressionChange,
    showOptionalParams,
    setShowOptionalParams,
    allNodes = [],
    edges = [],
}: AgentConfigurationPanelProps) {
    const t = useTranslations('workflowBuilder.agentConfig');
    const tf = useTranslations('workflowBuilder.forms');

    // AI Agent nodes always use entity reference panel
    // (classify/guardrail/browser_agent are filtered out by ParameterColumn before reaching here)
    if (nodeRegistry.isAiAgentNode(node)) {
        return (
            <AgentEntityPanel
                node={node}
                data={data}
                onUpdate={onUpdate}
                isRunMode={isRunMode}
                connectionProps={connectionProps}
                findUnknownVariables={findUnknownVariables}
                getParamExpression={getParamExpression}
                handleParamExpressionChange={handleParamExpressionChange}
                t={t}
                tf={tf}
            />
        );
    }

    // Classify/Guardrail: keep existing inline config panel
    return (
        <InlineConfigPanel
            node={node}
            data={data}
            onUpdate={onUpdate}
            isRunMode={isRunMode}
            connectionProps={connectionProps}
            findUnknownVariables={findUnknownVariables}
            getParamExpression={getParamExpression}
            handleParamExpressionChange={handleParamExpressionChange}
            showOptionalParams={showOptionalParams}
            setShowOptionalParams={setShowOptionalParams}
            allNodes={allNodes}
            edges={edges}
            tf={tf}
        />
    );
}

// ============================================================================
// Agent Entity Panel - Simplified panel for type='agent'
// ============================================================================

function AgentEntityPanel({
    node,
    data,
    onUpdate,
    isRunMode,
    connectionProps,
    findUnknownVariables,
    getParamExpression,
    handleParamExpressionChange,
    t,
    tf,
}: {
    node: any;
    data: any;
    onUpdate: (data: any) => void;
    isRunMode: boolean;
    connectionProps: ConnectionProps;
    findUnknownVariables: (expressions: Record<string, string>) => string[];
    getParamExpression: (key: string) => string;
    handleParamExpressionChange: (key: string, value: string) => void;
    t: (key: string) => string;
    tf: (key: string) => string;
}) {
    const [agents, setAgents] = React.useState<AgentEntity[]>([]);
    const [loading, setLoading] = React.useState(true);
    const [fetchKey, setFetchKey] = React.useState(0);

    // Fetch available agents
    React.useEffect(() => {
        let cancelled = false;
        agentService.getAgents()
            .then((result) => {
                if (!cancelled) setAgents(result);
            })
            .catch(() => {
                if (!cancelled) setAgents([]);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => { cancelled = true; };
    }, [fetchKey]);

    // Phase 6c (2026-05-19) - drop the agent list on workspace switch
    // and refetch. The inspector remains mounted while the workflow
    // builder repaints; without this reset the previous workspace's
    // agents stay selectable in the dropdown (and a stale agentConfigId
    // can be saved into the node).
    useOrgScopedReset(() => {
        setAgents([]);
        setLoading(true);
        setFetchKey((k) => k + 1);
    });

    const selectedAgent = React.useMemo(() => {
        if (!data.agentConfigId) return null;
        return agents.find(a => a.id === data.agentConfigId) ?? null;
    }, [data.agentConfigId, agents]);

    const handleAgentChange = (agentId: string) => {
        const agent = agents.find(a => a.id === agentId);
        if (agent) {
            onUpdate({
                ...data,
                agentConfigId: agent.id,
                agentConfigName: agent.name,
                agentAvatarUrl: agent.avatarUrl,
            });
        }
    };

    const handleMemoryToggle = (checked: boolean) => {
        onUpdate({ ...data, withMemory: checked });
    };

    const withMemory = data.withMemory ?? true;

    const getAgentSubtitle = (agent: AgentEntity) => {
        const parts: string[] = [];
        if (agent.modelProvider && agent.modelName) {
            const providerName = getProviderDisplayName(agent.modelProvider);
            parts.push(`${providerName} ${agent.modelName}`);
        } else if (agent.modelName) {
            parts.push(agent.modelName);
        }
        if (agent.toolsConfig && Array.isArray(agent.toolsConfig)) {
            const count = agent.toolsConfig.length;
            if (count > 0) {
                parts.push(`${count} ${count === 1 ? 'tool' : 'tools'}`);
            }
        }
        return parts.join(' · ') || undefined;
    };

    return (
        <div className="space-y-5 pt-2">
            {/* Agent Selector */}
            <div className="space-y-2">
                <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                    {t('agentLabel')}
                </Label>
                <Select
                    value={data.agentConfigId || ''}
                    onValueChange={handleAgentChange}
                    disabled={isRunMode || loading}
                >
                    <SelectTrigger className="w-full">
                        {data.agentConfigId && selectedAgent ? (
                            <div className="flex items-center gap-2 min-w-0">
                                <AvatarDisplay avatarUrl={selectedAgent.avatarUrl} name={selectedAgent.name} size="sm" className="w-7 h-7" />
                                <div className="flex flex-col items-start min-w-0">
                                    <span className="text-sm truncate">{selectedAgent.name}</span>
                                    {getAgentSubtitle(selectedAgent) && (
                                        <span className="text-xs text-slate-400 dark:text-slate-500 truncate">
                                            {getAgentSubtitle(selectedAgent)}
                                        </span>
                                    )}
                                </div>
                            </div>
                        ) : (
                            <SelectValue placeholder={t('agentPlaceholder')} />
                        )}
                    </SelectTrigger>
                    <SelectContent>
                        {agents.length === 0 && !loading ? (
                            <div className="px-3 py-2 text-sm text-slate-400">
                                {t('noAgents')}
                            </div>
                        ) : (
                            agents.map((agent) => (
                                <SelectItem key={agent.id} value={agent.id} description={getAgentSubtitle(agent) || undefined}>
                                    <div className="flex items-center gap-2">
                                        <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="w-7 h-7" />
                                        <span className="text-sm">{agent.name}</span>
                                    </div>
                                </SelectItem>
                            ))
                        )}
                    </SelectContent>
                </Select>
            </div>

            {/* Prompt - Required */}
            <ExpressionField
                label={t('promptLabel')}
                value={getParamExpression('prompt')}
                onChange={(value) => handleParamExpressionChange('prompt', value)}
                nodeId={node.id}
                fieldName="param-prompt"
                isRequired={true}
                isRunMode={isRunMode}
                findUnknownVariables={findUnknownVariables}
                connectionProps={connectionProps}
                placeholder={t('promptPlaceholder')}
            />

            {/* Memory Toggle */}
            <div className="space-y-2">
                <div className="flex items-center justify-between">
                    <div className="flex items-center gap-1.5">
                        <MemoryStick className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400" />
                        <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                            {t('memoryLabel')}
                        </Label>
                        <Popover>
                            <PopoverTrigger asChild>
                                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                    <Info className="h-3 w-3 text-slate-400" />
                                </button>
                            </PopoverTrigger>
                            <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                <p className="text-xs text-slate-600 dark:text-slate-300">
                                    {t('memoryDescription')}
                                </p>
                            </PopoverContent>
                        </Popover>
                    </div>
                    <Switch
                        checked={withMemory}
                        onCheckedChange={handleMemoryToggle}
                        disabled={isRunMode}
                    />
                </div>
            </div>
        </div>
    );
}

// ============================================================================
// Inline Config Panel - For classify/guardrail (unchanged behavior)
// ============================================================================

function InlineConfigPanel({
    node,
    data,
    onUpdate,
    isRunMode,
    connectionProps,
    findUnknownVariables,
    getParamExpression,
    handleParamExpressionChange,
    showOptionalParams,
    setShowOptionalParams,
    allNodes,
    edges,
    tf,
}: {
    node: any;
    data: any;
    onUpdate: (data: any) => void;
    isRunMode: boolean;
    connectionProps: ConnectionProps;
    findUnknownVariables: (expressions: Record<string, string>) => string[];
    getParamExpression: (key: string) => string;
    handleParamExpressionChange: (key: string, value: string) => void;
    showOptionalParams: boolean;
    setShowOptionalParams: (show: boolean) => void;
    allNodes: Node[];
    edges: Edge[];
    tf: (key: string) => string;
}) {
    const temperature = data.temperature ?? DEFAULT_TEMPERATURE;
    const maxTokens = data.maxTokens ?? DEFAULT_MAX_TOKENS;
    const maxIterations = data.maxIterations ?? DEFAULT_MAX_ITERATIONS;
    const maxTools = data.maxTools ?? DEFAULT_MAX_TOOLS;

    // Build the typed selection from the node's legacy two-field storage.
    // ModelPicker handles the provider/model cascade + casing consistency.
    const modelSelection: SelectedModel = {
        provider: data.provider ?? '',
        id: data.model ?? '',
    };

    const handleModelPick = (next: SelectedModel) => {
        onUpdate({ ...data, provider: next.provider, model: next.id });
    };

    // Compute connected MCP tools from edges
    const connectedTools = React.useMemo<ConnectedTool[]>(() => {
        if (!node?.id || !edges || !allNodes) return [];

        // Find edges where the AI Agent is the source and uses bottom handle for tools
        const toolEdges = edges.filter(edge =>
            edge.source === node.id &&
            (edge.sourceHandle === 'source-bottom-tools' || edge.sourceHandle === 'source-bottom-1' || edge.sourceHandle === 'source-bottom-2')
        );

        // Get the target nodes and extract tool information
        // Use a Map to deduplicate by node id (in case multiple edges point to the same tool)
        const toolsMap = new Map<string, ConnectedTool>();
        for (const edge of toolEdges) {
            const targetNode = allNodes.find(n => n.id === edge.target);
            if (targetNode?.data && !toolsMap.has(targetNode.id)) {
                const nodeData = targetNode.data as any;
                // Check if it's a tool node
                if (nodeData.toolData) {
                    toolsMap.set(targetNode.id, {
                        id: targetNode.id,
                        name: nodeData.toolData.toolName || nodeData.toolData.name || nodeData.label || 'Unknown Tool',
                        apiName: nodeData.toolData.apiName,
                        toolSlug: nodeData.toolData.toolSlug,
                        iconSlug: nodeData.toolData.iconSlug,
                    });
                } else if (nodeData.apiData) {
                    // API node - will have all tools from that API
                    toolsMap.set(targetNode.id, {
                        id: targetNode.id,
                        name: `${nodeData.apiData.apiName || nodeData.label || 'API'} (all tools)`,
                        apiName: nodeData.apiData.apiName,
                        iconSlug: nodeData.apiData.iconSlug,
                    });
                } else if (nodeData.label) {
                    // Generic node with a label
                    toolsMap.set(targetNode.id, {
                        id: targetNode.id,
                        name: nodeData.label,
                    });
                }
            }
        }
        return Array.from(toolsMap.values());
    }, [node?.id, edges, allNodes]);

    const hasConnectedTools = connectedTools.length > 0;

    const handleTemperatureChange = (value: string) => {
        const numValue = value === '' ? DEFAULT_TEMPERATURE : Math.min(2, Math.max(0, parseFloat(value) || DEFAULT_TEMPERATURE));
        onUpdate({ ...data, temperature: numValue });
    };

    const handleMaxTokensChange = (value: string) => {
        const numValue = value === '' ? DEFAULT_MAX_TOKENS : Math.min(128000, Math.max(1, parseInt(value) || DEFAULT_MAX_TOKENS));
        onUpdate({ ...data, maxTokens: numValue });
    };

    const handleMaxIterationsChange = (value: string) => {
        const numValue = value === '' ? DEFAULT_MAX_ITERATIONS : Math.min(50, Math.max(1, parseInt(value) || DEFAULT_MAX_ITERATIONS));
        onUpdate({ ...data, maxIterations: numValue });
    };

    const handleMaxToolsChange = (value: string) => {
        const numValue = value === '' ? DEFAULT_MAX_TOOLS : Math.min(20, Math.max(1, parseInt(value) || DEFAULT_MAX_TOOLS));
        onUpdate({ ...data, maxTools: numValue });
    };

    // Calculate optional params count
    const optionalParamsCount = 5; // systemPrompt, temperature, maxTokens, maxIterations, maxTools

    return (
        <div className="space-y-5 pt-2">
            <ModelPicker
                value={modelSelection}
                onChange={handleModelPick}
                disabled={isRunMode}
                providerLabel={tf('provider')}
                modelLabel={tf('model')}
            />

            {/* Prompt - Required */}
            <ExpressionField
                label={tf('agentInline.prompt')}
                value={getParamExpression('prompt')}
                onChange={(value) => handleParamExpressionChange('prompt', value)}
                nodeId={node.id}
                fieldName="param-prompt"
                isRequired={true}
                isRunMode={isRunMode}
                findUnknownVariables={findUnknownVariables}
                connectionProps={connectionProps}
                placeholder={tf('agentInline.promptPlaceholder')}
            />

            {/* Tools - Required field showing connected tools or auto-discover */}
            <div className="space-y-2">
                <div className="flex items-center justify-between">
                    <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('agentInline.tools')}</Label>
                    <span className="text-sm text-slate-500 dark:text-slate-400">{tf('required')}</span>
                </div>
                <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 px-3 py-2">
                    {hasConnectedTools ? (
                        <div className="space-y-1.5">
                            {connectedTools.map((tool) => (
                                <div key={tool.id} className="flex items-center gap-2">
                                    <div className="flex-shrink-0 w-5 h-5 flex items-center justify-center">
                                        {tool.iconSlug ? (
                                            <Image
                                                src={`/icons/services/${tool.iconSlug}.svg`}
                                                alt={tool.name}
                                                width={18}
                                                height={18}
                                                className="w-[18px] h-[18px] rounded-full p-0.5 dark:bg-slate-100/10"
                                                onError={(e) => {
                                                    const target = e.target as HTMLImageElement;
                                                    target.src = "/mcp_black.png";
                                                }}
                                            />
                                        ) : (
                                            <Image
                                                src="/mcp_black.png"
                                                alt="Tool"
                                                width={18}
                                                height={18}
                                                className="w-[18px] h-[18px] rounded-full p-0.5 dark:bg-slate-100/10"
                                            />
                                        )}
                                    </div>
                                    <span className="text-sm text-slate-700 dark:text-slate-300">{tool.name}</span>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="flex items-center gap-2">
                            <Image
                                src="/mcp_black.png"
                                alt="MCP"
                                width={18}
                                height={18}
                                className="w-[18px] h-[18px] rounded-full p-0.5 dark:bg-slate-100/10"
                            />
                            <span className="text-sm text-slate-700 dark:text-slate-300">{tf('agentInline.allTools')}</span>
                        </div>
                    )}
                </div>
                {!hasConnectedTools && (
                    <p className="text-xs text-slate-400 dark:text-slate-500">
                        {tf('agentInline.connectToolsHint')}
                    </p>
                )}
            </div>

            {/* Optional Parameters */}
            <OptionalSection
                isOpen={showOptionalParams}
                onToggle={() => setShowOptionalParams(!showOptionalParams)}
                count={optionalParamsCount}
            >
                {/* System Prompt */}
                <ExpressionField
                    label={tf('agentInline.systemPrompt')}
                    value={getParamExpression('systemPrompt')}
                    onChange={(value) => handleParamExpressionChange('systemPrompt', value)}
                    nodeId={node.id}
                    fieldName="param-systemPrompt"
                    isRequired={false}
                    isRunMode={isRunMode}
                    findUnknownVariables={findUnknownVariables}
                    connectionProps={connectionProps}
                    placeholder={tf('agentInline.systemPromptPlaceholder')}
                    infoContent={tf('agentInline.systemPromptInfo')}
                />

                {/* Temperature */}
                <div className="space-y-2">
                    <div className="flex items-center gap-1.5">
                        <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('temperature')}</Label>
                        <Popover>
                            <PopoverTrigger asChild>
                                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                    <Info className="h-3 w-3 text-slate-400" />
                                </button>
                            </PopoverTrigger>
                            <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                <p className="text-xs text-slate-600 dark:text-slate-300">{tf('agentInline.temperatureDescription')}</p>
                            </PopoverContent>
                        </Popover>
                    </div>
                    <Input
                        type="number"
                        min="0"
                        max="2"
                        step="0.1"
                        value={temperature}
                        onChange={(e) => handleTemperatureChange(e.target.value)}
                        disabled={isRunMode}
                    />
                </div>

                {/* Max Tokens */}
                <div className="space-y-2">
                    <div className="flex items-center gap-1.5">
                        <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('maxTokens')}</Label>
                        <Popover>
                            <PopoverTrigger asChild>
                                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                    <Info className="h-3 w-3 text-slate-400" />
                                </button>
                            </PopoverTrigger>
                            <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                <p className="text-xs text-slate-600 dark:text-slate-300">{tf('agentInline.maxTokensDescription')}</p>
                            </PopoverContent>
                        </Popover>
                    </div>
                    <Input
                        type="number"
                        min="1"
                        max="128000"
                        step="100"
                        value={maxTokens}
                        onChange={(e) => handleMaxTokensChange(e.target.value)}
                        disabled={isRunMode}
                    />
                </div>

                {/* Max Iterations */}
                <div className="space-y-2">
                    <div className="flex items-center gap-1.5">
                        <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('agentInline.maxIterations')}</Label>
                        <Popover>
                            <PopoverTrigger asChild>
                                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                    <Info className="h-3 w-3 text-slate-400" />
                                </button>
                            </PopoverTrigger>
                            <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                <p className="text-xs text-slate-600 dark:text-slate-300">{tf('agentInline.maxIterationsDescription')}</p>
                            </PopoverContent>
                        </Popover>
                    </div>
                    <Input
                        type="number"
                        min="1"
                        max="50"
                        step="1"
                        value={maxIterations}
                        onChange={(e) => handleMaxIterationsChange(e.target.value)}
                        disabled={isRunMode}
                    />
                </div>

                {/* Max Tools - Only show when auto-discovering */}
                {!hasConnectedTools && (
                    <div className="space-y-2">
                        <div className="flex items-center gap-1.5">
                            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{tf('agentInline.maxToolsToDiscover')}</Label>
                            <Popover>
                                <PopoverTrigger asChild>
                                    <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                        <Info className="h-3 w-3 text-slate-400" />
                                    </button>
                                </PopoverTrigger>
                                <PopoverContent className="w-[280px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                    <p className="text-xs text-slate-600 dark:text-slate-300">{tf('agentInline.maxToolsDescription')}</p>
                                </PopoverContent>
                            </Popover>
                        </div>
                        <Input
                            type="number"
                            min="1"
                            max="20"
                            step="1"
                            value={maxTools}
                            onChange={(e) => handleMaxToolsChange(e.target.value)}
                            disabled={isRunMode}
                        />
                    </div>
                )}
            </OptionalSection>
        </div>
    );
}
