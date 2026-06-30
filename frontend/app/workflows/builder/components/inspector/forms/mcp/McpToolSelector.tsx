'use client';

/**
 * McpToolSelector - MCP API and Tool selection UI
 *
 * Features:
 * - API list with search and lazy loading
 * - Tool list with search and lazy loading
 * - Credential configuration
 * - Tool parameters rendering
 * - Navigation between APIs and tools
 */

import * as React from 'react';
import { ArrowRight } from 'lucide-react';
import Image from 'next/image';
import clsx from 'clsx';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ApiListSkeleton, ToolListSkeleton, ToolDetailsSkeleton } from '../../../SkeletonLoaders';
import LoadingSpinner from '@/components/LoadingSpinner';
import { OptionalSection } from '../../OptionalSection';
import { CredentialSection } from '../../CredentialSection';
import { ExpressionField } from '../../ExpressionField';
import type { BuilderNodeData } from '../../../../types';

interface McpToolSelectorProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isToolNode: boolean;
  isApiNode: boolean;
  isRunMode: boolean;
  isDark: boolean;

  // Navigation state
  mcpNavigationLevel: 'apis' | 'tools';
  mcpSearchQuery: string;
  setMcpSearchQuery: (query: string) => void;

  // API state
  shouldLoadApis: boolean;
  apiInitialLoading: boolean;
  mcpLoadingApis: boolean;
  mcpApis: any[];
  apiHasMore: boolean;
  apiLoadMoreRef: any;

  // Tool state
  mcpLoadingTools: boolean;
  toolInitialLoading: boolean;
  mcpApiTools: any[];
  toolHasMore: boolean;
  toolLoadMoreRef: any;

  // Tool details state
  loadingToolDetails: boolean;
  toolParameters: any[];
  toolCredentials: any[];
  /**
   * Full tool details as returned by the workflow inspector - carries the
   * api_tool UUID used to resolve per-endpoint pricing. Optional: legacy
   * nodes without a persisted `toolData.apiToolId` rely on this to receive
   * the correct pricing answer without a data migration.
   */
  toolDetails?: any;
  allRequiredCredentialsConfigured: boolean;
  setAllRequiredCredentialsConfigured: (configured: boolean) => void;

  // Handlers
  handleMouseEnter: (title: string, description: string, element: HTMLElement) => void;
  handleMouseLeave: () => void;
  handleMcpApiClick: (api: any) => void;
  handleMcpToolSelect: (tool: any) => void;
  onUpdate: (data: BuilderNodeData) => void;

  // Optional params state
  effectiveShowOptionalParams: boolean;
  effectiveSetShowOptionalParams: (show: boolean) => void;

  // Utilities
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  connectionProps: any;
}

export function McpToolSelector({
  node,
  data,
  isToolNode,
  isApiNode,
  isRunMode,
  isDark,
  mcpNavigationLevel,
  mcpSearchQuery,
  setMcpSearchQuery,
  shouldLoadApis,
  apiInitialLoading,
  mcpLoadingApis,
  mcpApis,
  apiHasMore,
  apiLoadMoreRef,
  mcpLoadingTools,
  toolInitialLoading,
  mcpApiTools,
  toolHasMore,
  toolLoadMoreRef,
  loadingToolDetails,
  toolParameters,
  toolCredentials,
  toolDetails,
  allRequiredCredentialsConfigured,
  setAllRequiredCredentialsConfigured,
  handleMouseEnter,
  handleMouseLeave,
  handleMcpApiClick,
  handleMcpToolSelect,
  onUpdate,
  effectiveShowOptionalParams,
  effectiveSetShowOptionalParams,
  findUnknownVariables,
  connectionProps,
}: McpToolSelectorProps) {
  return (
    <div className="space-y-4 pt-2">
      {/* Search - Hide for tool nodes */}
      {!isToolNode && (
        <div className="relative">
          <Input
            type="text"
            placeholder={
              mcpNavigationLevel === 'apis' ? 'Search for an API...' :
                'Search for a tool...'
            }
            value={mcpSearchQuery}
            onChange={(e) => setMcpSearchQuery(e.target.value)}
            className="w-full"
          />
        </div>
      )}

      {/* APIs List - Only show if not an API node or if we're navigating back */}
      {mcpNavigationLevel === 'apis' && !isApiNode && !isToolNode && shouldLoadApis && (
        <div className="space-y-2 overflow-x-hidden">
          {apiInitialLoading && mcpLoadingApis ? (
            <ApiListSkeleton count={5} />
          ) : (
            <>
              {mcpApis
                .filter((api: any) =>
                  !mcpSearchQuery.trim() ||
                  api.apiName.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  api.description?.toLowerCase().includes(mcpSearchQuery.toLowerCase())
                )
                .map((api: any) => {
                  const toolsCount = api.toolsCount || 0;
                  const canExpand = toolsCount > 0;
                  return (
                    <div
                      key={api.slug}
                      className={clsx(
                        "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors",
                        canExpand
                          ? "hover:bg-gray-50 dark:hover:bg-gray-800/50 cursor-pointer"
                          : "cursor-default opacity-75"
                      )}
                      onMouseEnter={(e) => handleMouseEnter(api.apiName, api.description || '', e.currentTarget as HTMLElement)}
                      onMouseLeave={handleMouseLeave}
                      onClick={canExpand ? () => handleMcpApiClick(api) : undefined}
                    >
                      <div className="flex-shrink-0 w-11 h-11 flex items-center justify-center">
                        {api.iconSlug ? (
                          <Image
                            src={`/icons/services/${api.iconSlug}.svg`}
                            alt={api.apiName}
                            width={28}
                            height={28}
                            className="w-7 h-7"
                            onError={(e) => {
                              const target = e.target as HTMLImageElement;
                              target.src = isDark ? "/mcp.png" : "/mcp_black.png";
                              target.className = "w-7 h-7";
                            }}
                          />
                        ) : (
                          <Image
                            src={isDark ? "/mcp.png" : "/mcp_black.png"}
                            alt="API"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                          />
                        )}
                      </div>
                      <div className="flex-1 flex items-center justify-between gap-4">
                        <div className="flex-1 flex flex-col justify-center">
                          <div className="text-sm text-slate-900 dark:text-slate-100 text-left truncate">
                            {api.apiName}
                          </div>
                        </div>
                        {canExpand && (
                          <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
                        )}
                      </div>
                    </div>
                  );
                })}
              {mcpApis.length === 0 && !mcpLoadingApis && !apiInitialLoading && (
                <div className="text-center py-8 text-slate-500 dark:text-slate-400">
                  No API found
                </div>
              )}
            </>
          )}
          {/* Lazy loading indicator for APIs */}
          {apiHasMore && !apiInitialLoading && (
            <div ref={apiLoadMoreRef} className="py-4 flex justify-center">
              {mcpLoadingApis && (
                <LoadingSpinner size="sm" />
              )}
            </div>
          )}
        </div>
      )}

      {/* Tools List - Show for API nodes or when navigating to tools */}
      {(mcpNavigationLevel === 'tools' || isApiNode) && !isToolNode && (
        <div className="space-y-2 overflow-x-hidden">
          {toolInitialLoading && mcpLoadingTools ? (
            <ToolListSkeleton count={5} />
          ) : (
            <>
              {mcpApiTools
                .filter((tool: any) =>
                  !mcpSearchQuery.trim() ||
                  tool.name.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  tool.description?.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  tool.endpoint?.toLowerCase().includes(mcpSearchQuery.toLowerCase())
                )
                .map((tool: any) => {
                  // Use iconSlug from node data (same as header)
                  const iconSlug = (node?.data as any)?.apiData?.iconSlug || (node?.data as any)?.toolData?.iconSlug;
                  return (
                    <div
                      key={tool.slug}
                      className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                      onMouseEnter={(e) => handleMouseEnter(tool.name, tool.description || '', e.currentTarget as HTMLElement)}
                      onMouseLeave={handleMouseLeave}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleMcpToolSelect(tool);
                      }}
                    >
                      <div className="flex-shrink-0 w-11 h-11 flex items-center justify-center">
                        {iconSlug ? (
                          <Image
                            src={`/icons/services/${iconSlug}.svg`}
                            alt="Tool"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                            onError={(e) => {
                              const target = e.target as HTMLImageElement;
                              target.src = isDark ? "/mcp.png" : "/mcp_black.png";
                              target.className = "w-7 h-7";
                            }}
                          />
                        ) : (
                          <Image
                            src={isDark ? "/mcp.png" : "/mcp_black.png"}
                            alt="Tool"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                          />
                        )}
                      </div>
                      <div className="flex-1 flex items-center justify-between gap-4">
                        <div className="flex-1 flex flex-col justify-center">
                          <div className="text-sm text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                            {tool.name}
                          </div>
                        </div>
                        <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
                      </div>
                    </div>
                  );
                })}
              {mcpApiTools.length === 0 && !mcpLoadingTools && (
                <div className="text-center py-8 text-slate-500 dark:text-slate-400">
                  No tool found for this API
                </div>
              )}
            </>
          )}
          {/* Lazy loading indicator for Tools */}
          {toolHasMore && !toolInitialLoading && (
            <div ref={toolLoadMoreRef} className="py-4 flex justify-center">
              {mcpLoadingTools && (
                <LoadingSpinner size="sm" />
              )}
            </div>
          )}
        </div>
      )}

      {/* Tool Credentials - Show at the top when credentials are required */}
      {isToolNode && toolCredentials && toolCredentials.length > 0 && (
        <div>
          <CredentialSection
            toolCredentials={toolCredentials}
            selectedCredentialId={(data as any)?.toolData?.selectedCredentialId}
            onCredentialSelect={(credentialId, credentialName) => {
              if (onUpdate && node?.data) {
                const updatedData = {
                  ...node.data,
                  toolData: {
                    ...(node.data as any)?.toolData,
                    selectedCredentialId: credentialId,
                    selectedCredentialName: credentialName,
                  },
                };
                onUpdate(updatedData);
              }
            }}
            integration={(data as any)?.toolData?.iconSlug || (data as any)?.toolData?.apiName || (data as any)?.toolData?.apiSlug}
            // Legacy nodes saved before apiToolId was tracked fall back to the
            // catalog tool-details response (which now carries the UUID). New
            // nodes write apiToolId directly in toolData on tool selection.
            apiToolId={
              (data as any)?.toolData?.apiToolId
              ?? (toolDetails as any)?.toolId
              ?? null
            }
            isRunMode={isRunMode}
            onCredentialStatusChange={setAllRequiredCredentialsConfigured}
            // V166: surface per-endpoint OAuth scope requirements so the
            // MissingScopesBanner inside CredentialSection can warn when the
            // bound credential lacks them.
            requiredScopes={(toolDetails as any)?.requiredScopes}
            credentialSource={(data as any)?.toolData?.credentialSource ?? 'user'}
            platformCredentialId={(data as any)?.toolData?.platformCredentialId ?? null}
            onCredentialSourceChange={(source, platformCredentialId) => {
              if (!onUpdate || !node?.data) return;
              const existingToolData = (node.data as any)?.toolData ?? {};
              const nextToolData = {
                ...existingToolData,
                credentialSource: source,
                platformCredentialId: source === 'platform' ? platformCredentialId : null,
              };
              // When switching to platform, the user's personal credential id is
              // intentionally preserved in the node (so toggling back restores it)
              // but ignored by the backend - `credentialSource` is the source of truth.
              onUpdate({ ...node.data, toolData: nextToolData });
            }}
          />
        </div>
      )}

      {/* Hidden CredentialSection to notify parent when there are no credentials */}
      {isToolNode && (!toolCredentials || toolCredentials.length === 0) && (
        <div className="hidden">
          <CredentialSection
            toolCredentials={[]}
            selectedCredentialId={null}
            onCredentialSelect={() => { }}
            integration={(data as any)?.toolData?.iconSlug || (data as any)?.toolData?.apiName || (data as any)?.toolData?.apiSlug}
            isRunMode={isRunMode}
            onCredentialStatusChange={setAllRequiredCredentialsConfigured}
          />
        </div>
      )}

      {/* Tool Parameters - Show below credentials */}
      {isToolNode && (
        <div className="space-y-4 pt-2">
          {loadingToolDetails ? (
            <ToolDetailsSkeleton />
          ) : toolParameters.length > 0 ? (
            (() => {
              // Filter out parameters that share a name with a credential to avoid duplicate display
              const credentialNames = new Set(
                (toolCredentials || []).map((c: any) => (c.credentialName || c.name || '').toLowerCase())
              );
              const filteredParams = toolParameters.filter((param: any) => {
                const paramName = (param.name || param.id || '').toLowerCase();
                return !credentialNames.has(paramName);
              });
              const requiredParams = filteredParams.filter((param: any) => param.isRequired === true || param.required === true);
              const optionalParams = filteredParams.filter((param: any) => param.isRequired !== true && param.required !== true);

              const renderParam = (param: any, isRequired: boolean) => {
                const paramName = param.name || param.id;
                const paramDescription = param.description || '';
                const paramType = param.dataType || param.type || 'string';

                return (
                  <ExpressionField
                    key={param.id || paramName}
                    label={paramName}
                    value={(data?.paramExpressions as Record<string, string> | undefined)?.[paramName] || ''}
                    onChange={(value) => {
                      if (isRunMode) return;
                      const currentExpressions = (data?.paramExpressions as Record<string, string> | undefined) || {};
                      onUpdate({
                        ...node.data,
                        paramExpressions: {
                          ...currentExpressions,
                          [paramName]: value,
                        },
                      });
                    }}
                    nodeId={node.id}
                    fieldName={`param-${paramName}`}
                    placeholder={paramDescription || `Enter ${paramName}...`}
                    isRequired={isRequired}
                    isRunMode={isRunMode}
                    typeHint={paramType}
                    findUnknownVariables={findUnknownVariables}
                    connectionProps={connectionProps}
                    defaultValue={param.defaultValue ?? null}
                    allowedValues={param.allowedValues ?? null}
                    picker={param.extras?.picker ?? null}
                  />
                );
              };

              return (
                <>
                  {requiredParams.map((param: any) => renderParam(param, true))}
                  {optionalParams.length > 0 && (
                    <OptionalSection
                      isOpen={effectiveShowOptionalParams}
                      onToggle={() => effectiveSetShowOptionalParams(!effectiveShowOptionalParams)}
                      count={optionalParams.length}
                    >
                      {optionalParams.map((param: any) => renderParam(param, false))}
                    </OptionalSection>
                  )}
                </>
              );
            })()
          ) : (
            <div className="text-center py-8 text-slate-500 dark:text-slate-400 text-sm">
              This tool has no configured parameters
            </div>
          )}
        </div>
      )}
    </div>
  );
}
