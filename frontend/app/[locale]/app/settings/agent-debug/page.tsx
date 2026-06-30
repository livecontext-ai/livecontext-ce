"use client";

import React, { useState, useEffect, useCallback } from "react";
import {
  Bot,
  Terminal,
  Play,
  ChevronDown,
  ChevronRight,
  Copy,
  Check,
  Search,
  FileText,
  Wrench,
  AlertCircle,
  Shield,
  User,
} from "lucide-react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { agentToolsService } from "@/lib/api/orchestrator";
import type {
  AgentTool,
  ToolCategory,
  AgentPrompt,
  ToolExecutionResult,
} from "@/lib/api/orchestrator";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from "next-intl";

// ============================================
// Prompt Composition Info Component
// ============================================

function PromptCompositionInfo({ promptName }: { promptName: string | null }) {
  if (!promptName) return null;

  const getCompositionInfo = () => {
    switch (promptName) {
      case "VERSATILE_AGENT":
        return {
          usedWhen: "General chat conversations (not linked to a workflow)",
          blocks: [
            { name: "Base intro", desc: "You are an autonomous assistant..." },
            { name: "CORE_RULES", desc: "Tool calling behavior, autonomy rules" },
            { name: "RESPONSE_RULES", desc: "Markdown formatting, visualization handling" },
            { name: "TOOL_SELECTION", desc: "How to choose one-time vs automation" },
            { name: "NODE_OVERVIEW", desc: "30 nodes in 5 categories + workflow_help references" },
            { name: "TASK_MANAGEMENT", desc: "When to plan tasks, actions available" },
            { name: "AUTOMATION_HINT", desc: "When to suggest workflows" },
          ],
          injected: [
            { key: "NEW_CONVERSATION_INSTRUCTION", when: "First message", desc: "Call set_conversation_title, optionally plan tasks" },
            { key: "FOLLOW_UP_INSTRUCTION", when: "Follow-up messages", desc: "Conditional task planning" },
          ],
          note: "DATA_FLOW_RULES and WORKFLOW_BUILDING moved to init/load responses (workflow-specific context).",
        };

      case "WORKFLOW_AGENT":
        return {
          usedWhen: "Agent nodes executing inside a workflow run",
          blocks: [
            { name: "Intro", desc: "Execute task immediately, no questions" },
            { name: "Key points", desc: "Variables resolved, max 10 iterations, output becomes step data" },
            { name: "Limitations", desc: "Catalog tools only (no table/interface)" },
            { name: "Response style", desc: "GitHub Flavored Markdown, concise" },
          ],
          injected: [],
          note: "This prompt is lighter - no workflow building rules, no tool selection guide. The agent just executes its task with pre-resolved variables.",
        };

      default:
        return null;
    }
  };

  const info = getCompositionInfo();
  if (!info) return null;

  return (
    <div className="mb-4 p-4 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg">
      <h4 className="text-sm font-semibold text-blue-800 dark:text-blue-200 mb-2">
        Prompt Composition
      </h4>

      <div className="text-xs space-y-3">
        <div>
          <span className="font-medium text-blue-700 dark:text-blue-300">Used when: </span>
          <span className="text-blue-600 dark:text-blue-400">{info.usedWhen}</span>
        </div>

        <div>
          <span className="font-medium text-blue-700 dark:text-blue-300">Composed of:</span>
          <ol className="mt-1 ml-4 space-y-0.5 list-decimal">
            {info.blocks.map((block, i) => (
              <li key={i} className="text-blue-600 dark:text-blue-400">
                <span className="font-mono text-blue-800 dark:text-blue-200">{block.name}</span>
                <span className="text-blue-500 dark:text-blue-500"> - {block.desc}</span>
              </li>
            ))}
          </ol>
        </div>

        {info.injected.length > 0 && (
          <div>
            <span className="font-medium text-blue-700 dark:text-blue-300">Dynamically injected:</span>
            <ul className="mt-1 ml-4 space-y-0.5 list-disc">
              {info.injected.map((item, i) => (
                <li key={i} className="text-blue-600 dark:text-blue-400">
                  <span className="font-mono text-blue-800 dark:text-blue-200">{item.key}</span>
                  <span className="text-blue-500 dark:text-blue-500"> ({item.when}) - {item.desc}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {info.note && (
          <div className="mt-2 pt-2 border-t border-blue-200 dark:border-blue-700">
            <span className="text-blue-600 dark:text-blue-400 italic">{info.note}</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ============================================
// Workflow Prompt Info Component
// ============================================

function WorkflowPromptInfo() {
  return (
    <div className="mb-6 p-4 bg-purple-50 dark:bg-purple-900/20 border border-purple-200 dark:border-purple-800 rounded-lg">
      <h4 className="text-sm font-semibold text-purple-800 dark:text-purple-200 mb-2">
        Workflow-Linked Conversations (buildWorkflowPrompt)
      </h4>

      <div className="text-xs space-y-3">
        <div>
          <span className="font-medium text-purple-700 dark:text-purple-300">Used when: </span>
          <span className="text-purple-600 dark:text-purple-400">Chat is linked to a specific workflow (user viewing/editing)</span>
        </div>

        <div>
          <span className="font-medium text-purple-700 dark:text-purple-300">Composed of:</span>
          <ol className="mt-1 ml-4 space-y-0.5 list-decimal text-purple-600 dark:text-purple-400">
            <li><span className="font-mono text-purple-800 dark:text-purple-200">Base intro</span> - Autonomous assistant for workflow</li>
            <li><span className="font-mono text-purple-800 dark:text-purple-200">CORE_RULES</span> - Same as VERSATILE</li>
            <li><span className="font-mono text-purple-800 dark:text-purple-200">RESPONSE_RULES</span> - Same as VERSATILE</li>
            <li><span className="font-mono text-purple-800 dark:text-purple-200">WORKFLOW_CONTEXT</span> - Workflow info + quick actions</li>
            <li><span className="font-mono text-purple-800 dark:text-purple-200">NODE_OVERVIEW</span> - 30 nodes in 5 categories</li>
            <li><span className="font-mono text-purple-800 dark:text-purple-200">TASK_MANAGEMENT</span> - Same as VERSATILE</li>
          </ol>
        </div>

        <div>
          <span className="font-medium text-purple-700 dark:text-purple-300">Injected context:</span>
          <ul className="mt-1 ml-4 space-y-0.5 list-disc text-purple-600 dark:text-purple-400">
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{workflow_name}"}</code> - Current workflow name</li>
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{workflow_id}"}</code> - UUID for load action</li>
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{workflow_status}"}</code> - ACTIVE, INACTIVE, etc.</li>
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{flow_diagram}"}</code> - ASCII flow representation</li>
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{datasource_id}"}</code> - Table ID for CRUD</li>
            <li><code className="bg-purple-100 dark:bg-purple-800 px-1 rounded">{"{last_run}"}</code> - Last execution info</li>
          </ul>
        </div>

        <div className="mt-2 pt-2 border-t border-purple-200 dark:border-purple-700">
          <span className="text-purple-600 dark:text-purple-400 italic">
            DATA_FLOW + WORKFLOW_BUILDING rules now in init/load responses. Not included: TOOL_SELECTION, AUTOMATION_HINT.
          </span>
        </div>
      </div>
    </div>
  );
}

// ============================================
// Prompts Tab Component
// ============================================

function PromptsTab() {
  const [prompts, setPrompts] = useState<AgentPrompt[]>([]);
  const [selectedPrompt, setSelectedPrompt] = useState<AgentPrompt | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingPrompt, setLoadingPrompt] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    loadPrompts();
  }, []);

  const loadPrompts = async () => {
    try {
      const data = await agentToolsService.getPrompts();
      setPrompts(data.prompts);
    } catch (error) {
      console.error("Error loading prompts:", error);
    } finally {
      setLoading(false);
    }
  };

  const selectPrompt = async (name: string) => {
    setLoadingPrompt(true);
    try {
      const prompt = await agentToolsService.getPrompt(name);
      setSelectedPrompt(prompt);
    } catch (error) {
      console.error("Error loading prompt:", error);
    } finally {
      setLoadingPrompt(false);
    }
  };

  const copyToClipboard = async () => {
    if (selectedPrompt?.content) {
      await navigator.clipboard.writeText(selectedPrompt.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Workflow Prompt Info - Always visible at top */}
      <WorkflowPromptInfo />

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Prompt List */}
        <div className="space-y-2">
          <h3 className="text-sm font-medium text-theme-secondary mb-3">
            System Prompts
          </h3>
          {prompts.map((prompt) => (
            <button
              key={prompt.name}
              onClick={() => selectPrompt(prompt.name)}
              className={cn(
                "w-full text-left p-3 rounded-lg border transition-all",
                selectedPrompt?.name === prompt.name
                  ? "border-blue-500 bg-blue-50 dark:bg-blue-900/20"
                  : "border-gray-200 dark:border-gray-700 hover:border-gray-300 dark:hover:border-gray-600"
              )}
            >
              <div className="flex items-center justify-between">
                <span className="font-medium text-sm text-theme-primary">
                  {prompt.name}
                </span>
                {prompt.isDefault && (
                  <Badge variant="secondary" className="text-xs">
                    Default
                  </Badge>
                )}
              </div>
              <p className="text-xs text-theme-secondary mt-1">
                {prompt.description}
              </p>
              <div className="flex gap-3 mt-2 text-xs text-theme-tertiary">
                <span>~{prompt.tokenEstimate} tokens</span>
                <span>{prompt.lineCount} lines</span>
              </div>
            </button>
          ))}
        </div>

        {/* Prompt Content */}
        <div className="lg:col-span-2">
          {loadingPrompt ? (
            <div className="flex items-center justify-center py-12">
              <LoadingSpinner size="sm" />
            </div>
          ) : selectedPrompt ? (
            <div>
              {/* Composition Info - Above the content */}
              <PromptCompositionInfo promptName={selectedPrompt.name} />

              <Card>
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-lg flex items-center gap-2">
                      <FileText className="h-5 w-5" />
                      {selectedPrompt.name}
                    </CardTitle>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={copyToClipboard}
                      className="h-8"
                    >
                      {copied ? (
                        <Check className="h-4 w-4 mr-1" />
                      ) : (
                        <Copy className="h-4 w-4 mr-1" />
                      )}
                      {copied ? "Copied" : "Copy"}
                    </Button>
                  </div>
                  <p className="text-sm text-theme-secondary">
                    {selectedPrompt.description}
                  </p>
                </CardHeader>
                <CardContent>
                  <pre className="bg-theme-tertiary p-4 rounded-lg text-xs overflow-auto max-h-[600px] whitespace-pre-wrap font-mono">
                    {selectedPrompt.content}
                  </pre>
                </CardContent>
              </Card>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-12 text-theme-secondary">
              <FileText className="h-12 w-12 mb-3 opacity-50" />
              <p>Select a prompt to view its content</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ============================================
// Tools Tab Component
// ============================================

function ToolsTab({
  onSelectTool,
}: {
  onSelectTool: (tool: AgentTool) => void;
}) {
  const [categories, setCategories] = useState<ToolCategory[]>([]);
  const [tools, setTools] = useState<AgentTool[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [expandedTools, setExpandedTools] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      const [categoriesData, toolsData] = await Promise.all([
        agentToolsService.getCategories(),
        agentToolsService.getTools(),
      ]);
      setCategories(categoriesData.categories);
      setTools(toolsData.tools);
    } catch (error) {
      console.error("Error loading tools:", error);
    } finally {
      setLoading(false);
    }
  };

  const toggleTool = (toolName: string) => {
    setExpandedTools((prev) => {
      const next = new Set(prev);
      if (next.has(toolName)) {
        next.delete(toolName);
      } else {
        next.add(toolName);
      }
      return next;
    });
  };

  const filteredTools = tools.filter((tool) => {
    const matchesSearch =
      !searchQuery ||
      tool.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      tool.description.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesCategory =
      !selectedCategory || tool.category === selectedCategory;
    return matchesSearch && matchesCategory;
  });

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Search and Filter */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-tertiary" />
          <Input
            placeholder="Search tools..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex gap-2 flex-wrap">
          <Button
            variant={selectedCategory === null ? "default" : "outline"}
            size="sm"
            onClick={() => setSelectedCategory(null)}
          >
            All ({tools.length})
          </Button>
          {categories.map((cat) => (
            <Button
              key={cat.slug}
              variant={selectedCategory === cat.slug ? "default" : "outline"}
              size="sm"
              onClick={() => setSelectedCategory(cat.slug)}
            >
              {cat.name} ({cat.toolCount})
            </Button>
          ))}
        </div>
      </div>

      {/* Tools List */}
      <div className="space-y-2">
        {filteredTools.map((tool) => (
          <Collapsible
            key={tool.name}
            open={expandedTools.has(tool.name)}
            onOpenChange={() => toggleTool(tool.name)}
          >
            <div className="border rounded-lg overflow-hidden">
              <CollapsibleTrigger className="w-full">
                <div className="flex items-center justify-between p-3 hover:bg-gray-50 dark:hover:bg-gray-800">
                  <div className="flex items-center gap-3">
                    {expandedTools.has(tool.name) ? (
                      <ChevronDown className="h-4 w-4 text-theme-tertiary" />
                    ) : (
                      <ChevronRight className="h-4 w-4 text-theme-tertiary" />
                    )}
                    <div className="text-left">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-sm font-medium text-theme-primary">
                          {tool.name}
                        </span>
                        <Badge variant="outline" className="text-xs">
                          {tool.category}
                        </Badge>
                      </div>
                      <p className="text-xs text-theme-secondary mt-0.5">
                        {tool.description}
                      </p>
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelectTool(tool);
                    }}
                    className="h-8"
                  >
                    <Play className="h-4 w-4 mr-1" />
                    Test
                  </Button>
                </div>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="px-3 pb-3 pt-0 border-t bg-gray-50 dark:bg-gray-800/50">
                  <div className="mt-3">
                    <h4 className="text-xs font-medium text-theme-secondary mb-2">
                      Parameters
                    </h4>
                    {tool.parameters && tool.parameters.length > 0 ? (
                      <div className="space-y-2">
                        {tool.parameters.map((param: any) => (
                          <div
                            key={param.name}
                            className="flex items-start gap-2 text-xs"
                          >
                            <code className="font-mono bg-theme-tertiary px-1.5 py-0.5 rounded">
                              {param.name}
                            </code>
                            <span className="text-theme-tertiary">
                              {param.type}
                            </span>
                            {param.required && (
                              <Badge
                                variant="destructive"
                                className="text-[10px] px-1 py-0"
                              >
                                required
                              </Badge>
                            )}
                            <span className="text-theme-secondary flex-1">
                              {param.description}
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-xs text-theme-tertiary">
                        No parameters
                      </p>
                    )}
                  </div>
                </div>
              </CollapsibleContent>
            </div>
          </Collapsible>
        ))}
      </div>
    </div>
  );
}

// ============================================
// Tool Tester Component
// ============================================

function ToolTester({
  tool,
  onClose,
}: {
  tool: AgentTool | null;
  onClose: () => void;
}) {
  const [parameters, setParameters] = useState<string>("{}");
  const [result, setResult] = useState<ToolExecutionResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (tool) {
      // Build default parameters from tool definition
      const defaultParams: Record<string, unknown> = {};
      tool.parameters?.forEach((param: any) => {
        if (param.defaultValue !== undefined) {
          defaultParams[param.name] = param.defaultValue;
        } else if (param.required) {
          if (param.enumValues?.length) {
            defaultParams[param.name] = param.enumValues[0];
          } else if (param.type === "string") {
            defaultParams[param.name] = "";
          } else if (param.type === "number" || param.type === "integer") {
            defaultParams[param.name] = 0;
          } else if (param.type === "boolean") {
            defaultParams[param.name] = false;
          } else if (param.type === "array") {
            defaultParams[param.name] = [];
          } else if (param.type === "object") {
            defaultParams[param.name] = {};
          }
        }
      });
      setParameters(JSON.stringify(defaultParams, null, 2));
      setResult(null);
      setError(null);
    }
  }, [tool]);

  const executeTool = async () => {
    if (!tool) return;

    setExecuting(true);
    setError(null);
    setResult(null);

    try {
      const parsedParams = JSON.parse(parameters);
      const response = await agentToolsService.executeTool({
        tool: tool.name,
        parameters: parsedParams,
      });
      setResult(response);
    } catch (err: any) {
      if (err instanceof SyntaxError) {
        setError("Invalid JSON in parameters");
      } else {
        setError(err.message || "Execution failed");
      }
    } finally {
      setExecuting(false);
    }
  };

  if (!tool) {
    return (
      <Card className="h-full">
        <CardContent className="flex flex-col items-center justify-center h-full py-12">
          <Terminal className="h-12 w-12 mb-3 opacity-50 text-theme-tertiary" />
          <p className="text-theme-secondary">
            Select a tool from the list to test it
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-lg flex items-center gap-2">
            <Terminal className="h-5 w-5" />
            Test: {tool.name}
          </CardTitle>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
        <p className="text-sm text-theme-secondary">{tool.description}</p>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Parameters Input */}
        <div>
          <label className="text-sm font-medium text-theme-primary block mb-2">
            Parameters (JSON)
          </label>
          <Textarea
            value={parameters}
            onChange={(e) => setParameters(e.target.value)}
            className="font-mono text-sm min-h-[150px]"
            placeholder="{}"
          />
        </div>

        {/* Execute Button */}
        <Button
          onClick={executeTool}
          disabled={executing}
          className="w-full"
        >
          {executing ? (
            <>
              <LoadingSpinner size="xs" className="mr-2" />
              Executing...
            </>
          ) : (
            <>
              <Play className="h-4 w-4 mr-2" />
              Execute Tool
            </>
          )}
        </Button>

        {/* Error */}
        {error && (
          <div className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
            <div className="flex items-center gap-2 text-red-600 dark:text-red-400">
              <AlertCircle className="h-4 w-4" />
              <span className="text-sm font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* Result */}
        {result && (
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-sm font-medium text-theme-primary">
                Result
              </label>
              <Badge variant={result.success ? "default" : "destructive"}>
                {result.success ? "Success" : "Failed"}
              </Badge>
            </div>
            <pre className="bg-theme-tertiary p-4 rounded-lg text-xs overflow-auto max-h-[300px] whitespace-pre-wrap font-mono">
              {JSON.stringify(result, null, 2)}
            </pre>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ============================================
// Main Page Component
// ============================================

export default function AgentDebugPage() {
  const { user, isLoading, isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect, hasRole } = useAuth();
  const tSettings = useTranslations("settings");
  const [selectedTool, setSelectedTool] = useState<AgentTool | null>(null);
  const [activeTab, setActiveTab] = useState("prompts");

  // Wait for auth to be ready
  if (isAuthChecking || isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <div className="mx-auto max-w-4xl">
          <div className="min-h-[300px] flex items-center justify-center">
            <div className="text-center">
              <h1 className="text-2xl font-bold text-theme-primary mb-4">
                {tSettings('unauthorized')}
              </h1>
              <p className="text-theme-secondary mb-6">
                {tSettings('mustBeLoggedIn')}
              </p>
              <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                <User className="w-4 h-4 mr-1" />
                {tSettings('signIn')}
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!hasRole('ADMIN')) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <Bot className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings('unauthorized')}</h2>
          <p className="text-sm text-theme-secondary">Only the platform administrator can access agent debug.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="space-y-2">
          <h1 className="text-2xl font-bold text-theme-primary flex items-center gap-2">
            <Bot className="h-6 w-6" />
            Agent Debug
          </h1>
          <p className="text-theme-secondary">
            Visualize system prompts and test agent tools
          </p>
        </div>
        <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
          <Shield className="w-3.5 h-3.5" />
          Admin only
        </div>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="prompts" className="flex items-center gap-2">
            <FileText className="h-4 w-4" />
            Prompts
          </TabsTrigger>
          <TabsTrigger value="tools" className="flex items-center gap-2">
            <Wrench className="h-4 w-4" />
            Tools
          </TabsTrigger>
          <TabsTrigger value="tester" className="flex items-center gap-2">
            <Terminal className="h-4 w-4" />
            Tool Tester
          </TabsTrigger>
        </TabsList>

        <TabsContent value="prompts" className="mt-6">
          <PromptsTab />
        </TabsContent>

        <TabsContent value="tools" className="mt-6">
          <ToolsTab
            onSelectTool={(tool) => {
              setSelectedTool(tool);
              setActiveTab("tester");
            }}
          />
        </TabsContent>

        <TabsContent value="tester" className="mt-6">
          <ToolTester
            tool={selectedTool}
            onClose={() => setSelectedTool(null)}
          />
        </TabsContent>
      </Tabs>
    </div>
  );
}
