'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ArrowRight, Lock } from 'lucide-react';
import Image from 'next/image';
import { Input } from '@/components/ui/input';
import { ApiListSkeleton, ToolListSkeleton } from '../../SkeletonLoaders';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTheme } from '@/components/ThemeProvider';
import { useHoverPopover } from '../../../hooks/useHoverPopover';
import { HoverPopover } from '../../shared/HoverPopover';
import { useCredentialCheck } from '@/hooks/useCredentialCheck';
import { useTranslations } from 'next-intl';

interface InspectorMcpNodeProps {
  // Navigation state
  mcpNavigationLevel: 'apis' | 'tools';
  mcpSearchQuery: string;
  setMcpSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  mcpSelectedApiSlug: string | null;
  
  // Node detection
  isMcpNode: boolean;
  isApiNode: boolean;
  isToolNode: boolean;
  
  // Data
  mcpApis: any[];
  mcpApiTools: any[];
  apiInitialLoading: boolean;
  toolInitialLoading: boolean;
  mcpLoadingApis: boolean;
  mcpLoadingTools: boolean;
  shouldLoadApis: boolean;
  apiHasMore: boolean;
  toolHasMore: boolean;
  apiLoadMoreRef: React.RefObject<HTMLDivElement>;
  toolLoadMoreRef: React.RefObject<HTMLDivElement>;
  
  // Handlers
  handleMcpApiClick: (api: any) => void;
  handleMcpToolSelect: (tool: any) => void;
  
  // Node
  node: any;
}

export function InspectorMcpNode({
  mcpNavigationLevel,
  mcpSearchQuery,
  setMcpSearchQuery,
  mcpSelectedApiSlug,
  isMcpNode,
  isApiNode,
  isToolNode,
  mcpApis,
  mcpApiTools,
  apiInitialLoading,
  toolInitialLoading,
  mcpLoadingApis,
  mcpLoadingTools,
  shouldLoadApis,
  apiHasMore,
  toolHasMore,
  apiLoadMoreRef,
  toolLoadMoreRef,
  handleMcpApiClick,
  handleMcpToolSelect,
  node,
}: InspectorMcpNodeProps) {
  const { theme } = useTheme();
  const isDark = theme === 'dark';

  // V166: lock-badge state - list user's OAuth2 credentials so we can flag tools
  // whose required scopes are not yet granted by the bound credential.
  const { credentials } = useCredentialCheck();
  const tLock = useTranslations('actionPicker.locked');

  /**
   * For a tool, return the missing OAuth scopes if the user has an OAuth2
   * credential for this API but its granted scopes don't cover the required ones.
   * Returns null when no lock should be shown.
   */
  const computeMissingScopes = React.useCallback((tool: any): string[] | null => {
    const required: string[] = tool?.requiredScopes || [];
    if (!required.length) return null;
    const integrationName: string | undefined = tool?.integrationName;
    if (!integrationName || !credentials) return null;
    // Match the credential by unique-per-API integration name (NOT iconSlug,
    // which is brand-shared across multiple APIs).
    const cred: any = (credentials as any[]).find(
      (c) => c?.integration === integrationName,
    );
    if (!cred) return null;
    if (cred.type !== 'OAuth2') return null;
    const granted = new Set<string>(cred.scopes ?? []);
    const missing = required.filter((s) => !granted.has(s));
    return missing.length > 0 ? missing : null;
  }, [credentials]);

  // Hook pour gérer le popover au survol
  const {
    hoveredItem,
    isDesktop,
    containerRef: inspectorRef,
    popoverRef,
    handleMouseEnter,
    handleMouseLeave,
    isHoveringPopoverRef,
  } = useHoverPopover({
    position: 'left',
    gap: 16,
  });

  if (!isMcpNode) {
    return null;
  }

  return (
    <>
      {/* Popover au survol - Desktop uniquement */}
      <HoverPopover
        hoveredItem={hoveredItem}
        isDesktop={isDesktop}
        popoverRef={popoverRef}
        isHoveringPopoverRef={isHoveringPopoverRef}
        onMouseLeave={handleMouseLeave}
      />
      <div ref={inspectorRef} className="space-y-4 pt-2" onMouseLeave={handleMouseLeave}>
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
                .filter(api => 
                  !mcpSearchQuery.trim() ||
                  api.apiName.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  api.description?.toLowerCase().includes(mcpSearchQuery.toLowerCase())
                )
                .map((api) => {
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
                      <div className="flex-shrink-0 w-9 h-9 flex items-center justify-center rounded-2xl bg-orange-50 text-orange-600">
                        {api.iconSlug ? (
                          <Image
                            src={`/icons/services/${api.iconSlug}.svg`}
                            alt={api.apiName}
                            width={28}
                            height={28}
                            className="w-7 h-7"
                            onError={(e) => {
                              const target = e.target as HTMLImageElement;
                              target.src = "/mcp_black.png";
                              target.className = "w-7 h-7";
                            }}
                          />
                        ) : (
                          <Image
                            src="/mcp_black.png"
                            alt="API"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                          />
                        )}
                      </div>
                      <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                        <div className="flex-1 min-w-0 flex flex-col justify-center">
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
                .filter(tool => 
                  !mcpSearchQuery.trim() ||
                  tool.name.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  tool.description?.toLowerCase().includes(mcpSearchQuery.toLowerCase()) ||
                  tool.method?.toLowerCase().includes(mcpSearchQuery.toLowerCase())
                )
                .map((tool) => {
                  // Use iconSlug from node data (same as header)
                  const iconSlug = (node?.data as any)?.apiData?.iconSlug || (node?.data as any)?.toolData?.iconSlug;
                  // V166: compute missing OAuth scopes for the lock-badge.
                  const missingScopes = computeMissingScopes(tool);
                  const lockTooltip = missingScopes
                    ? tLock('tooltip', { count: missingScopes.length })
                    : '';
                  return (
                    <div
                      key={tool.slug}
                      className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                      onMouseEnter={(e) => handleMouseEnter(tool.name, tool.description || '', e.currentTarget as HTMLElement)}
                      onMouseLeave={handleMouseLeave}
                      onClick={() => handleMcpToolSelect(tool)}
                    >
                      <div className="flex-shrink-0 w-9 h-9 flex items-center justify-center rounded-2xl bg-orange-50 text-orange-600">
                        {iconSlug ? (
                          <Image
                            src={`/icons/services/${iconSlug}.svg`}
                            alt="Tool"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                            onError={(e) => {
                              const target = e.target as HTMLImageElement;
                              target.src = "/mcp_black.png";
                              target.className = "w-7 h-7";
                            }}
                          />
                        ) : (
                          <Image
                            src="/mcp_black.png"
                            alt="Tool"
                            width={28}
                            height={28}
                            className="w-7 h-7"
                          />
                        )}
                      </div>
                      <div className="flex-1 flex items-center justify-between gap-4">
                        <div className="flex-1 flex flex-col justify-center">
                          <div className="text-sm text-slate-900 dark:text-slate-100 text-left mb-1 truncate flex items-center gap-1.5">
                            <span className="truncate">{tool.name}</span>
                            {missingScopes && (
                              <Lock
                                className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400 flex-shrink-0"
                                aria-label={lockTooltip}
                              />
                            )}
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
      </div>
    </>
  );
}

