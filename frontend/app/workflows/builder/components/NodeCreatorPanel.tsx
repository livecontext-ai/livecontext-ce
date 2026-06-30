'use client';

import clsx from 'clsx';
import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Search, X, Layers, Plus, Route, Boxes, Bot, Cpu } from 'lucide-react';
import { ApiListSkeleton, ToolListSkeleton } from './SkeletonLoaders';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useMcpApis, useMcpApiTools, ApiSystem, ApiTool } from '../hooks/useMcpData';
import { useDataSources, useDataSourceTables, DataSource } from '../hooks/useDataSourceData';
import { useWorkflows } from '../hooks/useWorkflowsData';
import { useInterfaces } from '../hooks/useInterfaces';
import { useSharedNavigation, useNavigationBack } from '../hooks/useSharedNavigation';
import { TRIGGER_TYPES } from './inspector/nodeTypes';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { CreateInterfaceModal } from '@/components/chat/CreateInterfaceModal';
import { CreateAgentModal } from '@/components/chat/CreateAgentModal';
import { CreateDataSourceModal } from '@/components/chat/CreateDataSourceModal';
import { useQueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { Agent } from '@/lib/api/orchestrator/types';
import {
  findNodeClassById,
  getPaletteCategoryTree,
  getPaletteItemDataFromId,
  type NodeFamily,
  type PaletteCategoryNode,
} from '../nodes/nodeClasses';
import type { BuilderNodeKind } from '../types';
import { TooltipProvider } from '@/components/ui/tooltip';
import { NodeIcon } from './nodes/shared';
import { DraggableNodeItem, useBreadcrumbs, useLazyLoadObserver } from './palette/index';

type NodeCreatorPanelProps = {
  isOpen: boolean;
  onClose: () => void;
  onSelectNode?: (nodeId: string | any) => void;
  currentWorkflowId?: string;
};

export { getPaletteItemDataFromId };

export function NodeCreatorPanel({ isOpen, onClose, onSelectNode, currentWorkflowId }: NodeCreatorPanelProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const { isRunMode } = useWorkflowMode();
  const queryClient = useQueryClient();
  const [showCreateInterfaceModal, setShowCreateInterfaceModal] = React.useState(false);
  const [showCreateDataSourceModal, setShowCreateDataSourceModal] = React.useState(false);
  const [showCreateAgentModal, setShowCreateAgentModal] = React.useState(false);
  // Standalone trigger resources (webhook/schedule/chat/form) are created
  // from a single place: the form's auto-create effect on mount. Creating
  // them here on palette click burns the user's quota on every refresh -
  // the palette-click sourceNodeId (random per click) cannot be reproduced
  // after a page reload, so the backend dedup `(tenant_id, source_node_id)`
  // never fires. See `utils/standaloneSourceNodeId.ts`.

  // Navigation state
  const navigation = useSharedNavigation({ initialLevel: 'categories', resetOnClose: true, isOpen });
  const {
    navigationLevel, setNavigationLevel, searchQuery, setSearchQuery,
    selectedCategoryId, setSelectedCategoryId, selectedApiId, setSelectedApiId,
    selectedDataSourceId, setSelectedDataSourceId, selectedType, setSelectedType,
    toolPage, setToolPage,
  } = navigation;

  const handleBack = useNavigationBack(navigation);
  const paletteTree = React.useMemo(() => getPaletteCategoryTree(), []);
  // Frequently used nodes - hardcoded list with navigation items
  const FEATURED_NAV_IDS = new Set(['ai-agent', 'table', 'interface']);
  const FEATURED_IDS = ['ai-agent', 'if-else', 'table', 'interface', 'split', 'task', 'classify', 'note'] as const;
  const featuredNodes = React.useMemo(() =>
    FEATURED_IDS.map(id => {
      const isNav = FEATURED_NAV_IDS.has(id);
      const klass = findNodeClassById(id);
      if (klass) return { id: klass.id, label: klass.label, description: klass.description, kind: klass.kind, family: klass.family as NodeFamily, isNav };
      // Navigation-only items not in NODE_CLASSES
      if (id === 'table') return { id: 'table', label: 'Tables', description: 'Search or visualize your data', kind: 'action' as const, family: 'core' as NodeFamily, isNav: true };
      return null;
    }).filter(Boolean) as { id: string; label: string; description: string; kind: BuilderNodeKind; family: NodeFamily; isNav: boolean }[],
  []);

  const findCategoryNode = React.useCallback((id: string | null): PaletteCategoryNode | null => {
    if (!id) return null;
    const walk = (nodes: PaletteCategoryNode[]): PaletteCategoryNode | null => {
      for (const node of nodes) {
        if (node.id === id) return node;
        if (node.children) {
          const found = walk(node.children);
          if (found) return found;
        }
      }
      return null;
    };
    return walk(paletteTree);
  }, [paletteTree]);

  // Breadcrumbs
  const breadcrumbItems = useBreadcrumbs({
    navigationLevel, selectedCategoryId, selectedApiId, selectedDataSourceId, selectedType,
    apis: [], dataSources: [], findCategoryNode,
    setNavigationLevel, setSelectedCategoryId, setSelectedApiId, setSelectedDataSourceId,
    setSelectedType, setSearchQuery, setToolPage,
  });

  // Debounced search for APIs
  const [debouncedApiSearchQuery, setDebouncedApiSearchQuery] = React.useState('');
  React.useEffect(() => {
    const shouldSearchApis = navigationLevel === 'apis' || (navigationLevel === 'categories' && searchQuery.trim().length > 0);
    if (!shouldSearchApis) { setDebouncedApiSearchQuery(''); return; }
    const trimmedQuery = searchQuery.trim();
    if (trimmedQuery.length === 0) { setDebouncedApiSearchQuery(''); return; }
    if (trimmedQuery.length === 1) return;
    const timer = setTimeout(() => setDebouncedApiSearchQuery(trimmedQuery), 500);
    return () => clearTimeout(timer);
  }, [searchQuery, navigationLevel]);

  // Data hooks
  const shouldLoadApis = navigationLevel === 'apis' || (navigationLevel === 'categories' && searchQuery.trim().length > 0);
  const { data: apisData, fetchNextPage, hasNextPage, isFetching: isFetchingApis, isLoading: isLoadingApisInitial } = useMcpApis(shouldLoadApis, debouncedApiSearchQuery);
  const { data: allTools, isLoading: isLoadingTools, isFetching: isFetchingTools } = useMcpApiTools(selectedApiId);
  const { data: dataSources = [], isLoading: isLoadingDataSources } = useDataSources(navigationLevel === 'datasources' || navigationLevel === 'tables');
  const { data: dataSourceTables = [], isLoading: isLoadingTables } = useDataSourceTables(selectedDataSourceId);
  const { data: workflows = [], isLoading: isLoadingWorkflows } = useWorkflows(navigationLevel === 'workflows');
  const { data: interfaces = [], isLoading: isLoadingInterfaces } = useInterfaces(navigationLevel === 'interfaces', true);

  // Agents data
  const [agents, setAgents] = React.useState<Agent[]>([]);
  const [isLoadingAgents, setIsLoadingAgents] = React.useState(false);
  const [agentsFetchKey, setAgentsFetchKey] = React.useState(0);
  React.useEffect(() => {
    if (navigationLevel === 'agents') {
      setIsLoadingAgents(true);
      orchestratorApi.getAgents().then(setAgents).catch(() => setAgents([])).finally(() => setIsLoadingAgents(false));
    }
  }, [navigationLevel, agentsFetchKey]);

  // Phase 6c (2026-05-19) - drop the agents palette list on workspace
  // switch and refetch if currently in agents view. NodeCreatorPanel is
  // mounted permanently inside the workflow builder; without this reset
  // the previous workspace's agents stay draggable into the canvas (and
  // would resolve to a phantom UUID at runtime).
  useOrgScopedReset(() => {
    setAgents([]);
    setAgentsFetchKey((k) => k + 1);
  });

  const apis = React.useMemo(() => {
    const allApis = apisData?.pages.flatMap(page => page.content ?? []) || [];
    const uniqueApis = new Map<string, typeof allApis[0]>();
    allApis.forEach(api => { if (api?.slug && !uniqueApis.has(api.slug)) uniqueApis.set(api.slug, api); });
    return Array.from(uniqueApis.values());
  }, [apisData]);

  // Pagination
  const PAGE_SIZE = 20;
  const apiTools = React.useMemo(() => {
    if (!allTools) return [];
    return allTools.slice(0, (toolPage + 1) * PAGE_SIZE);
  }, [allTools, toolPage]);

  // Lazy load observers
  const apiLoadMoreRef = useLazyLoadObserver({
    enabled: isOpen && navigationLevel === 'apis',
    hasMore: !!hasNextPage,
    isLoading: isFetchingApis,
    isInitialLoading: isLoadingApisInitial,
    dataLength: apis.length,
    onLoadMore: () => fetchNextPage(),
  });

  const toolLoadMoreRef = useLazyLoadObserver({
    enabled: isOpen && navigationLevel === 'tools' && !!selectedApiId,
    hasMore: allTools ? (toolPage + 1) * PAGE_SIZE < allTools.length : false,
    isLoading: isFetchingTools,
    isInitialLoading: isLoadingTools,
    dataLength: apiTools.length,
    onLoadMore: () => setToolPage(prev => prev + 1),
  });

  // Filtered items
  const currentItems = React.useMemo(() => {
    if (selectedCategoryId) {
      const node = findCategoryNode(selectedCategoryId);
      return node?.children || [];
    }
    return paletteTree;
  }, [selectedCategoryId, paletteTree, findCategoryNode]);

  const filteredCategories = React.useMemo(() => {
    if (!searchQuery.trim()) return currentItems;
    const query = searchQuery.toLowerCase();
    return currentItems.filter(cat => cat.name.toLowerCase().includes(query) || cat.description?.toLowerCase().includes(query));
  }, [currentItems, searchQuery]);

  const filteredApis = React.useMemo(() => {
    if (navigationLevel !== 'categories' || !searchQuery.trim()) return [];
    const query = searchQuery.toLowerCase();
    return apis.filter(api => api.apiName.toLowerCase().includes(query) || api.description?.toLowerCase().includes(query) || api.slug.toLowerCase().includes(query));
  }, [apis, searchQuery, navigationLevel]);

  // Handlers
  const handleItemClick = (item: PaletteCategoryNode) => {
    const navMap: Record<string, () => void> = {
      'mcp': () => { setNavigationLevel('apis'); setSearchQuery(''); },
      'triggers': () => { setNavigationLevel('types'); setSelectedType('triggers'); setSelectedCategoryId(null); setSearchQuery(''); },
      'tables-trigger': () => { setNavigationLevel('datasources'); setSearchQuery(''); setSelectedDataSourceId(null); setSelectedType('triggers'); },
      'table': () => { setNavigationLevel('categories'); setSelectedCategoryId('data'); setSelectedDataSourceId(null); setSelectedType(null); setSearchQuery(''); },
      'interface': () => { setNavigationLevel('interfaces'); setSearchQuery(''); },
      'ai-agent': () => { setNavigationLevel('agents'); setSearchQuery(''); },
      'sub_workflow': () => { setNavigationLevel('workflows'); setSearchQuery(''); setSelectedCategoryId(null); setSelectedType('sub_workflow'); },
    };

    if (navMap[item.id]) { navMap[item.id](); return; }

    const crudOps = ['create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row'];
    if (crudOps.includes(item.id)) {
      setNavigationLevel('datasources'); setSearchQuery(''); setSelectedDataSourceId(null); setSelectedType(item.id);
      return;
    }

    if ((item.type === 'category' || item.type === 'subcategory') && item.children?.length) {
      setSelectedCategoryId(item.id); setSearchQuery('');
    } else {
      onSelectNode?.(item.id);
    }
  };

  const handleApiClick = (api: ApiSystem) => {
    setSelectedApiId(api.slug); setNavigationLevel('tools'); setSearchQuery(''); setToolPage(0);
  };

  const handleToolClick = (tool: ApiTool) => {
    const toolApiSlug = tool.apiSlug || selectedApiId;
    const api = apis.find(a => a.slug === toolApiSlug);
    const iconSlug = tool.iconSlug || api?.iconSlug;
    const iconUrl = tool.iconUrl || api?.iconUrl;
    onSelectNode?.({
      id: `tool-${tool.slug}`,
      label: tool.name,
      description: tool.description,
      kind: 'tool' as const,
      nodeType: 'flowNode' as const,
      toolData: { toolSlug: tool.slug, toolName: tool.name, apiSlug: toolApiSlug, apiName: api?.apiName || '', iconSlug, iconUrl, method: tool.method },
      apiData: { apiSlug: toolApiSlug, apiName: api?.apiName || '', iconSlug, iconUrl },
    });
  };

  // Helpers
  const getTileBg = (nodeId: string, family: NodeFamily, paletteBg?: string) => {
    if (paletteBg) return paletteBg;
    const familyColors: Record<string, string> = {
      ai: 'bg-blue-100 dark:bg-blue-900/30',
      loop: 'bg-violet-100 dark:bg-violet-900/30',
      condition: 'bg-violet-100 dark:bg-violet-900/30',
      data: 'bg-yellow-100 dark:bg-yellow-900/30',
      output: 'bg-green-100 dark:bg-green-900/30',
    };
    if (nodeId === 'interface') return 'bg-yellow-100 dark:bg-yellow-900/30';
    return familyColors[family] || 'bg-gray-100 dark:bg-gray-800';
  };

  const getCategoryBgColor = (id: string) => {
    if (id === 'triggers' || id.endsWith('trigger')) return 'bg-orange-100 dark:bg-orange-900/30';
    if (id === 'mcp' || id.startsWith('mcp-')) return 'bg-gray-100 dark:bg-gray-800';
    if (id === 'ai' || id === 'ai-agent' || id.startsWith('ai-')) return 'bg-blue-100 dark:bg-blue-900/30 text-blue-600';
    if (id === 'flow' || id === 'logic' || id === 'if-else' || id === 'user-approval' || id === 'while' || id === 'transform') return 'bg-violet-100 dark:bg-violet-900/30';
    if (id === 'setState') return 'bg-yellow-100 dark:bg-yellow-900/30';
    return 'bg-gray-100 dark:bg-gray-800';
  };

  const buildDataSourceData = (selectedType: string | null, dataSource: DataSource) => {
    const base: Record<string, any> = { dataSourceId: dataSource.id, dataSourceName: dataSource.name, crudOperation: selectedType };
    if (selectedType === 'create-row') base.rows = [{ id: 'row1', name: 'row1', columns: {} }];
    if (selectedType === 'create-column') base.newColumns = [{ id: 'col1', name: 'column1', type: 'text', defaultValue: '' }];
    if (selectedType === 'update-row') { base.whereCondition = { column: 'id', operator: '==', value: '' }; base.setColumns = []; }
    if (selectedType === 'delete-row') base.whereCondition = { column: 'id', operator: '==', value: '' };
    if (selectedType === 'read-row') { base.whereCondition = { column: 'id', operator: '==', value: '' }; base.limit = 50; }
    if (selectedType === 'find-row') { base.whereCondition = { column: 'id', operator: '==', value: '' }; base.limit = 100; }
    return base;
  };

  if (!isOpen) return null;

  return (
    <TooltipProvider delayDuration={1000}>
      <Button onClick={onClose} variant="secondary" size="sm" className="hidden sm:flex absolute top-0 -left-10 h-8 w-8 p-0 rounded-full z-[100] bg-[var(--bg-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none">
        <X className="h-4 w-4" />
      </Button>

      <div data-node-creator-panel className="w-[min(340px,calc(100vw-48px))] max-h-[90vh] rounded-[32px] bg-white/80 dark:bg-gray-800/80 backdrop-blur flex flex-col pointer-events-auto overflow-hidden relative z-[100]"
        onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); e.dataTransfer.dropEffect = 'none'; }}
        onDrop={(e) => { e.preventDefault(); e.stopPropagation(); }}>

        {/* Breadcrumb */}
        {(selectedCategoryId || navigationLevel !== 'categories') && (
          <div className="px-5 pt-3 flex-shrink-0">
            <nav className="flex items-center gap-1.5 text-sm" aria-label="Breadcrumb">
              {breadcrumbItems.map((item, index) => {
                const isLast = index === breadcrumbItems.length - 1;
                const isClickable = item.onClick && !isLast;
                return (
                  <React.Fragment key={index}>
                    {index > 0 && <span className="text-gray-400 dark:text-gray-500 flex-shrink-0">/</span>}
                    {index === 0 ? (
                      <button onClick={item.onClick} className="inline-flex items-center justify-center h-6 w-6 rounded hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors flex-shrink-0" title={t('backToHome')}>
                        <Layers className="h-4 w-4 text-gray-600 dark:text-gray-400" />
                      </button>
                    ) : (
                      <button onClick={item.onClick} className={clsx("px-1.5 py-0.5 rounded text-sm transition-colors truncate", item.isActive ? "text-gray-900 dark:text-gray-100 cursor-default" : isClickable ? "text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100" : "text-gray-500 dark:text-gray-400")} disabled={item.isActive || !isClickable}>
                        {item.label}
                      </button>
                    )}
                  </React.Fragment>
                );
              })}
            </nav>
          </div>
        )}

        {/* Mobile close button - inside panel for small screens where -left-10 clips */}
        <div className="sm:hidden flex justify-end px-3 pt-3 pb-0 flex-shrink-0">
          <Button onClick={onClose} variant="ghost" size="sm" className="h-7 w-7 p-0 rounded-full">
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Search */}
        <div className="px-5 pt-4 sm:pt-4 flex-shrink-0">
          <div className="relative flex items-center">
            <div className="absolute left-3 pointer-events-none z-10"><Search className="h-4 w-4 text-gray-400" /></div>
            <Input type="text" placeholder={t('searchPlaceholder', { context: navigationLevel === 'apis' ? 'mcp' : navigationLevel === 'tools' ? 'tools' : 'nodes' })} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="pl-9 pr-9" autoFocus />
            {searchQuery && <button onClick={() => setSearchQuery('')} className="absolute right-3 z-10 text-gray-400 hover:text-gray-600"><X className="h-4 w-4" /></button>}
          </div>
        </div>

        {/* Frequently Used */}
        {navigationLevel === 'categories' && !selectedCategoryId && (
          <div className="px-2 pb-4 pt-2 flex-shrink-0 border-b border-gray-200 dark:border-gray-800">
            <div className="space-y-2">
              <div className="px-3 pt-3 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('frequentlyUsed')}</div>
              <div className="grid grid-cols-2 gap-2">
                {featuredNodes.map((node) => {
                  const isNavItem = node.isNav || node.id === 'ai-agent';
                  const paletteData = isNavItem ? undefined : getPaletteItemDataFromId(node.id, node.label, node.description);
                  return (
                    <DraggableNodeItem
                      key={node.id}
                      id={node.id}
                      label={node.label}
                      description={node.description}
                      onClick={() => {
                        if (node.isNav) {
                          handleItemClick({ id: node.id, name: node.label, description: node.description } as PaletteCategoryNode);
                        } else {
                          onSelectNode?.(node.id);
                        }
                      }}
                      dragData={paletteData}
                      disableDrag={isNavItem}
                      showArrow={isNavItem}
                      nodeId={node.id}
                      nodeKind={node.kind}
                      nodeFamily={node.family}
                      iconSize="sm"
                    />
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* Content */}
        <div className="flex-1 overflow-y-auto min-h-0 pr-2">
          {/* Categories */}
          {navigationLevel === 'categories' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {(() => {
                // When in flow category, display all nodes in single column
                if (selectedCategoryId === 'flow') {
                  const flowNavIds = new Set(['sub_workflow']);
                  return (
                    <div className="space-y-1">
                      {filteredCategories.map((category) => {
                        const nodeClass = findNodeClassById(category.nodeClassId || category.id);
                        const paletteData = getPaletteItemDataFromId(category.nodeClassId || category.id, category.name, category.description);
                        const isNavItem = flowNavIds.has(category.nodeClassId || category.id);
                        return (
                          <DraggableNodeItem key={category.id} id={category.nodeClassId || category.id} label={category.name} description={category.description}
                            onClick={() => isNavItem ? handleItemClick(category) : onSelectNode?.(category.nodeClassId || category.id)}
                            dragData={paletteData} disableDrag={isNavItem} showArrow={isNavItem}
                            nodeId={category.nodeClassId || category.id} nodeKind={nodeClass?.kind} nodeFamily={nodeClass?.family as NodeFamily}
                            iconSize="sm" />
                        );
                      })}
                    </div>
                  );
                }

                // Default rendering for other categories
                return filteredCategories.map((category) => {
                  const hasChildren = (category.type === 'category' || category.type === 'subcategory') && (category.children?.length ?? 0) > 0;
                  const isNav = ['triggers', 'tables-trigger', 'table', 'interface', 'ai-agent', 'sub_workflow', 'create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row'].includes(category.id) || category.id.startsWith('table-');
                  const showArrow = hasChildren || isNav;
                  const nodeClass = findNodeClassById(category.nodeClassId || category.id);
                  const paletteData = getPaletteItemDataFromId(category.nodeClassId || category.id, category.name, category.description);
                  const isMcp = category.id === 'mcp' || category.id.startsWith('mcp-');
                  // Top-level group categories (triggers, ai, flow, core) use getCategoryBgColor
                  // Navigation items that are actual nodes (table, interface) use the registry (no bgClassName)
                  const isGroupCategory = ['triggers', 'ai', 'flow', 'core'].includes(category.id) || category.id.endsWith('trigger');
                  const fallbackBg = isGroupCategory ? getCategoryBgColor(category.id) : (nodeClass ? getTileBg(category.id, nodeClass.family as NodeFamily, nodeClass.palette?.quickBg) : getCategoryBgColor(category.id));
                  const needsFallbackBg = isGroupCategory || isMcp;

                  const isAiCategory = category.id === 'ai';
                  const isFlowCategory = category.id === 'flow';
                  const isCoreCategory = category.id === 'core';
                  const categoryIcon = isAiCategory
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-full bg-blue-100 dark:bg-blue-900/30"><Bot className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : isFlowCategory
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-full bg-violet-100 dark:bg-violet-900/30"><Route className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : isCoreCategory
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-full bg-yellow-100 dark:bg-yellow-900/30"><Cpu className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : undefined;

                  return (
                    <DraggableNodeItem key={category.id} id={category.nodeClassId || category.id} label={category.name} description={category.description}
                      onClick={() => handleItemClick(category)} dragData={paletteData} disableDrag={isNav}
                      showArrow={showArrow} nodeId={category.nodeClassId || category.id} nodeKind={nodeClass?.kind} nodeFamily={nodeClass?.family as NodeFamily}
                      bgClassName={isMcp ? 'bg-gray-100 dark:bg-gray-800' : needsFallbackBg ? fallbackBg : undefined} isMcp={isMcp} iconSize="sm"
                      iconOverride={categoryIcon} />
                  );
                });
              })()}

              {/* Search results for APIs */}
              {searchQuery.trim().length > 0 && filteredApis.length > 0 && (
                <>
                  <div className="px-3 pt-4 pb-2 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide border-t border-gray-200 dark:border-gray-800 mt-2">{t('mcp')}</div>
                  {filteredApis.map((api) => (
                    <DraggableNodeItem key={api.slug} id={`api-${api.slug}`} label={api.apiName} description={api.description}
                      secondaryInfo={api.toolsCount ? `${api.toolsCount} tool${api.toolsCount > 1 ? 's' : ''}` : undefined}
                      onClick={() => handleApiClick(api)} showArrow arrowType="arrow"
                      dragData={{ id: `api-${api.slug}`, label: api.apiName, description: api.description, kind: 'tool', nodeType: 'flowNode', apiData: { apiSlug: api.slug, apiName: api.apiName, iconSlug: api.iconSlug } }}
                      iconSlug={api.iconSlug} isMcp />
                  ))}
                </>
              )}
            </div>
          )}

          {/* APIs List */}
          {navigationLevel === 'apis' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {isLoadingApisInitial ? <ApiListSkeleton count={5} /> : (
                <>
                  {apis.map((api) => (
                    <DraggableNodeItem key={api.slug} id={`api-${api.slug}`} label={api.apiName} description={api.description}
                      secondaryInfo={api.toolsCount ? `${api.toolsCount} tool${api.toolsCount > 1 ? 's' : ''}` : undefined}
                      onClick={() => handleApiClick(api)} showArrow arrowType="arrow"
                      dragData={{ id: `api-${api.slug}`, label: api.apiName, description: api.description, kind: 'tool', nodeType: 'flowNode', apiData: { apiSlug: api.slug, apiName: api.apiName, iconSlug: api.iconSlug } }}
                      iconSlug={api.iconSlug} isMcp />
                  ))}
                  {apis.length === 0 && !isFetchingApis && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noApiFound')}</div>}
                </>
              )}
              {hasNextPage && !isLoadingApisInitial && <div ref={apiLoadMoreRef} className="py-4 flex justify-center">{isFetchingApis && <LoadingSpinner size="sm" />}</div>}
            </div>
          )}

          {/* Tools List */}
          {navigationLevel === 'tools' && (
            <div className="py-2 pl-2 pr-3 space-y-1">
              {isLoadingTools ? <ToolListSkeleton count={5} /> : (
                <>
                  {apiTools.filter(t => !searchQuery.trim() || t.name.toLowerCase().includes(searchQuery.toLowerCase()) || t.description?.toLowerCase().includes(searchQuery.toLowerCase())).map((tool) => {
                    const toolApiSlug = tool.apiSlug || selectedApiId;
                    const parentApi = apis.find(a => a.slug === toolApiSlug);
                    const iconSlug = tool.iconSlug || parentApi?.iconSlug;
                    const iconUrl = tool.iconUrl || parentApi?.iconUrl;
                    return (
                      <DraggableNodeItem key={tool.slug} id={`tool-${tool.slug}`} label={tool.name} description={tool.description}
                        onClick={() => handleToolClick(tool)}
                        dragData={{ id: `tool-${tool.slug}`, label: tool.name, description: tool.description, kind: 'tool', nodeType: 'flowNode', toolData: { toolSlug: tool.slug, apiSlug: toolApiSlug, apiName: parentApi?.apiName || '', method: tool.method, iconSlug, iconUrl } }}
                        iconSlug={iconSlug} isMcp />
                    );
                  })}
                  {apiTools.length === 0 && !isFetchingTools && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noToolFound')}</div>}
                </>
              )}
              {allTools && (toolPage + 1) * PAGE_SIZE < allTools.length && !isLoadingTools && <div ref={toolLoadMoreRef} className="py-4 flex justify-center">{isFetchingTools && <LoadingSpinner size="sm" />}</div>}
            </div>
          )}

          {/* DataSources List */}
          {navigationLevel === 'datasources' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              <div className="pl-3 mb-2">
                <Button type="button" variant="default" size="sm" className="w-full h-8 text-xs" onClick={() => setShowCreateDataSourceModal(true)} disabled={isRunMode}>
                  <Plus className="h-3 w-3 mr-1" />{t('createTable')}
                </Button>
              </div>
              {isLoadingDataSources ? <ApiListSkeleton count={5} /> : (
                <>
                  {dataSources.filter(ds => !searchQuery.trim() || ds.name.toLowerCase().includes(searchQuery.toLowerCase())).map((ds) => {
                    const isCrud = ['create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row'].includes(selectedType || '');
                    const tableNodeId = isCrud ? selectedType! : selectedType?.startsWith('data') ? `table-${selectedType.replace('data-', '')}` : 'tables-trigger';
                    const crudLabels: Record<string, string> = { 'create-row': 'Create Row', 'create-column': 'Create Column', 'read-row': 'Get Row', 'update-row': 'Update Row', 'delete-row': 'Delete Row', 'find-row': 'Find Rows' };
                    const operationLabel = isCrud ? (crudLabels[selectedType!] || 'Row') : selectedType === 'triggers' ? '' : (selectedType?.replace('data-', '') || '');
                    const nodeLabel = selectedType === 'triggers' ? ds.name : `${operationLabel} ${ds.name}`.trim();
                    const paletteData = getPaletteItemDataFromId(tableNodeId, nodeLabel, ds.description);

                    return (
                      <DraggableNodeItem key={ds.id} id={`${tableNodeId}-${ds.id}`} label={nodeLabel} description={ds.description}
                        onClick={() => onSelectNode?.({ ...paletteData, id: `${tableNodeId}-${ds.id}`, label: nodeLabel, dataSourceData: buildDataSourceData(selectedType, ds) })}
                        dragData={{ ...paletteData, id: `${tableNodeId}-${ds.id}`, label: nodeLabel, dataSourceData: buildDataSourceData(selectedType, ds) }}
                        nodeId={tableNodeId} />
                    );
                  })}
                  {dataSources.length === 0 && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noTablesFound')}</div>}
                </>
              )}
            </div>
          )}

          {/* Workflows List */}
          {navigationLevel === 'workflows' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {isLoadingWorkflows ? <ApiListSkeleton count={5} /> : (
                <>
                  {workflows
                    .filter(w =>
                      w.id !== currentWorkflowId &&
                      (!searchQuery.trim() || w.name.toLowerCase().includes(searchQuery.toLowerCase()))
                    )
                    .map((wf) => {
                      const isSubWf = selectedType === 'sub_workflow';
                      const isErrorTrigger = selectedType === 'error_trigger';
                      // Three picker modes share the workflow list: sub_workflow tool, workflows trigger, error trigger.
                      // Each maps to its own node-id prefix so the rest of the builder treats them distinctly.
                      const nodeId = isSubWf
                        ? `sub_workflow-${wf.id}`
                        : isErrorTrigger
                          ? `error-trigger-${wf.id}`
                          : `workflows-trigger-${wf.id}`;
                      const nodeKind = isSubWf ? 'sub_workflow' : 'entry';
                      // Custom labels - for error trigger, the canvas label should describe the *handler*'s
                      // intent ("On <Workflow> failure"), not the parent workflow's bare name. The user can rename later.
                      const nodeLabel = isErrorTrigger ? `On ${wf.name} failure` : wf.name;
                      const payload = {
                        id: nodeId,
                        label: nodeLabel,
                        description: isErrorTrigger
                          ? `Fires when "${wf.name}" run ends in FAILED or PARTIAL_SUCCESS.`
                          : wf.description,
                        kind: nodeKind as any,
                        nodeType: 'flowNode' as any,
                        workflowData: { workflowId: wf.id, workflowName: wf.name },
                        ...(isSubWf ? { subWorkflowId: wf.id } : {}),
                      };
                      const paletteNodeId = isSubWf ? 'sub_workflow' : isErrorTrigger ? 'error-trigger' : 'workflows-trigger';
                      return (
                        <DraggableNodeItem key={wf.id} id={nodeId} label={nodeLabel} description={payload.description}
                          onClick={() => onSelectNode?.(payload)}
                          dragData={payload}
                          nodeId={paletteNodeId} nodeKind={nodeKind} />
                      );
                    })}
                  {workflows.filter(w => w.id !== currentWorkflowId).length === 0 && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noWorkflowsFound')}</div>}
                </>
              )}
            </div>
          )}

          {/* Interfaces List */}
          {navigationLevel === 'interfaces' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              <div className="pl-3 mb-2">
                <Button type="button" variant="default" size="sm" className="w-full h-8 text-xs" onClick={() => setShowCreateInterfaceModal(true)} disabled={isRunMode}>
                  <Plus className="h-3 w-3 mr-1" />{t('createInterface')}
                </Button>
              </div>
              {isLoadingInterfaces ? <ApiListSkeleton count={5} /> : (
                <>
                  {interfaces.filter(i => (!i.interfaceType || i.interfaceType !== 'web_search') && (!searchQuery.trim() || i.name.toLowerCase().includes(searchQuery.toLowerCase()))).map((iface) => {
                    const paletteData = getPaletteItemDataFromId('interface', iface.name, iface.description);
                    return (
                      <DraggableNodeItem key={iface.id} id={`interface-${iface.id}`} label={iface.name} description={iface.description}
                        onClick={async () => {
                          let htmlTemplate = '';
                          let dataSourceId: number | null = null;
                          try {
                            const data = await orchestratorApi.getInterface(iface.id);
                            htmlTemplate = (data as any).htmlTemplate || (data as any).editorExpression || '';
                            dataSourceId = (data as any).dataSourceId ?? null;
                          } catch (err) { console.error('Error fetching interface:', err); }
                          onSelectNode?.({ ...paletteData, id: `interface-${iface.id}`, interfaceData: { interfaceId: iface.id, interfaceName: iface.name, editorExpression: htmlTemplate, dataSourceId } });
                        }}
                        dragData={{ ...paletteData, id: `interface-${iface.id}`, interfaceData: { interfaceId: iface.id, interfaceName: iface.name, editorExpression: '' } }}
                        nodeId="interface" nodeKind="interface" />
                    );
                  })}
                  {interfaces.length === 0 && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noInterfaceFound')}</div>}
                </>
              )}
            </div>
          )}

          {/* Agents List */}
          {navigationLevel === 'agents' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              <div className="pl-3 mb-2">
                <Button type="button" variant="default" size="sm" className="w-full h-8 text-xs" onClick={() => setShowCreateAgentModal(true)} disabled={isRunMode}>
                  <Plus className="h-3 w-3 mr-1" />{t('createAgent')}
                </Button>
              </div>
              {isLoadingAgents ? <ApiListSkeleton count={5} /> : (
                <>
                  {agents.filter(a => !searchQuery.trim() || a.name.toLowerCase().includes(searchQuery.toLowerCase())).map((agent) => (
                    <DraggableNodeItem key={agent.id} id={`ai-agent-${agent.id}`}
                      label={agent.name}
                      description={`${agent.modelProvider || ''} · ${agent.modelName || ''}`.replace(/^ · | · $/g, '').trim()}
                      avatarUrl={agent.avatarUrl}
                      onClick={() => onSelectNode?.({
                        id: 'ai-agent',
                        label: agent.name,
                        kind: 'reasoning' as const,
                        nodeType: 'flowNode' as const,
                        agentConfigId: agent.id,
                        agentConfigName: agent.name,
                        agentAvatarUrl: agent.avatarUrl,
                        withMemory: true,
                      })}
                      dragData={{
                        id: 'ai-agent',
                        label: agent.name,
                        kind: 'reasoning' as const,
                        nodeType: 'flowNode' as const,
                        agentConfigId: agent.id,
                        agentConfigName: agent.name,
                        agentAvatarUrl: agent.avatarUrl,
                        withMemory: true,
                      }}
                      nodeId="ai-agent" nodeKind="reasoning" nodeFamily="ai"
                      bgClassName="bg-blue-100 dark:bg-blue-900/30" />
                  ))}
                  {agents.length === 0 && (
                    <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noAgentsFound')}</div>
                  )}
                </>
              )}
            </div>
          )}

          {/* Tables List (for triggers) */}
          {navigationLevel === 'tables' && selectedDataSourceId && selectedType === 'triggers' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {isLoadingTables ? <ToolListSkeleton count={5} /> : (
                <>
                  <div className="px-3 py-2 mb-2 text-sm text-gray-700 dark:text-gray-300">{dataSources.find(ds => ds.id === selectedDataSourceId)?.name || 'Tables'}</div>
                  {dataSourceTables.filter(t => !searchQuery.trim() || t.name.toLowerCase().includes(searchQuery.toLowerCase())).map((table) => {
                    const dataSource = dataSources.find(ds => ds.id === selectedDataSourceId);
                    const paletteData = getPaletteItemDataFromId('tables-trigger', table.name, `Table from ${dataSource?.name || 'data source'}`);
                    return (
                      <DraggableNodeItem key={`${table.name}-${table.schema || ''}`} id={`tables-trigger-${selectedDataSourceId}-${table.name}`} label={table.name} description={table.schema}
                        onClick={() => onSelectNode?.({ ...paletteData, id: `tables-trigger-${selectedDataSourceId}-${table.name}`, dataSourceData: { dataSourceId: selectedDataSourceId, dataSourceName: dataSource?.name || '', tableName: table.name, schema: table.schema } })}
                        dragData={{ ...paletteData, id: `tables-trigger-${selectedDataSourceId}-${table.name}`, dataSourceData: { dataSourceId: selectedDataSourceId, dataSourceName: dataSource?.name || '', tableName: table.name, schema: table.schema } }}
                        nodeId={`tables-trigger-${selectedDataSourceId}-${table.name}`} nodeKind="entry" />
                    );
                  })}
                  {dataSourceTables.length === 0 && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noTablesFound')}</div>}
                </>
              )}
            </div>
          )}

          {/* Trigger Types */}
          {navigationLevel === 'types' && selectedType === 'triggers' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {TRIGGER_TYPES.filter(t => !searchQuery.trim() || t.name.toLowerCase().includes(searchQuery.toLowerCase())).map((trigger) => {
                const isNavTrigger = trigger.id === 'tables-trigger' || trigger.id === 'workflows-trigger' || trigger.id === 'error-trigger';
                const paletteData = getPaletteItemDataFromId(trigger.id, trigger.name, trigger.description);
                return (
                  <DraggableNodeItem key={trigger.id} id={trigger.id} label={trigger.name} description={trigger.description}
                    onClick={async () => {
                      if (trigger.id === 'tables-trigger') { setNavigationLevel('datasources'); setSearchQuery(''); setSelectedDataSourceId(null); setSelectedCategoryId(null); }
                      else if (trigger.id === 'workflows-trigger') { setNavigationLevel('workflows'); setSearchQuery(''); setSelectedCategoryId(null); setSelectedType('triggers'); }
                      else if (trigger.id === 'error-trigger') { setNavigationLevel('workflows'); setSearchQuery(''); setSelectedCategoryId(null); setSelectedType('error_trigger'); }
                      // Standalone trigger resources (webhook/schedule/chat/form):
                      // create the React-Flow node only. The resource is created
                      // once by the form's auto-create effect on inspect, using a
                      // stable `sourceNodeId = ${kind}-${node.id}` so refreshes and
                      // remounts hit the backend dedup instead of burning quota.
                      else onSelectNode?.(paletteData);
                    }}
                    dragData={isNavTrigger ? undefined : paletteData} disableDrag={isNavTrigger}
                    showArrow={isNavTrigger} arrowType="arrow" nodeId={trigger.id} nodeKind="entry" iconSize="sm" />
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Modals */}
      {showCreateInterfaceModal && (
        <CreateInterfaceModal onClose={() => setShowCreateInterfaceModal(false)} onInterfaceCreated={() => { queryClient.invalidateQueries({ predicate: (q) => Array.isArray(q.queryKey) && q.queryKey.includes('interfaces') }); setShowCreateInterfaceModal(false); }} />
      )}
      {showCreateDataSourceModal && (
        <CreateDataSourceModal onClose={() => setShowCreateDataSourceModal(false)} onDataSourceCreated={() => { queryClient.invalidateQueries({ predicate: (q) => Array.isArray(q.queryKey) && q.queryKey.includes('data-sources') }); setShowCreateDataSourceModal(false); }} />
      )}
      {showCreateAgentModal && (
        <CreateAgentModal onClose={() => setShowCreateAgentModal(false)} onAgentCreated={() => { setIsLoadingAgents(true); orchestratorApi.getAgents().then(setAgents).catch(() => setAgents([])).finally(() => setIsLoadingAgents(false)); setShowCreateAgentModal(false); }} />
      )}
    </TooltipProvider>
  );
}
