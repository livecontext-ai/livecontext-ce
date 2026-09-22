'use client';

import clsx from 'clsx';
import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Search, X, Layers, Plus, Route, Boxes, Bot, Cpu } from 'lucide-react';
import { ApiListSkeleton, ToolListSkeleton } from './SkeletonLoaders';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useMcpApis, useMcpApiTools, usePopularApis, fetchCatalogTool, ApiSystem, ApiTool } from '../hooks/useMcpData';
import { useDataSources, useDataSourceTables, DataSource } from '../hooks/useDataSourceData';
import { useWorkflows } from '../hooks/useWorkflowsData';
import { useInterfaces } from '../hooks/useInterfaces';
import { useSharedNavigation, useNavigationBack } from '../hooks/useSharedNavigation';
import { TRIGGER_TYPES } from './inspector/nodeTypes';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { CreateInterfaceModal } from '@/components/chat/CreateInterfaceModal';
import { CreateAgentModal } from '@/components/chat/CreateAgentModal';
import { CreateDataSourceModal } from '@/components/chat/CreateDataSourceModal';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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
// Generic, so it lives with the other hooks: the data table's media cells defer their bytes
// with the same latch.
import { useOnVisibleOnce } from '@/hooks/useOnVisibleOnce';
import { usePlanFeatureGate, type PlanLock } from '@/hooks/usePlanFeatureGate';
import { nodeFeatureKey, catalogFeatureKeys } from '../nodes/planFeatureKeys';
import {
  buildTriggerShortcutSelection,
  TRIGGER_SHORTCUTS,
  type TriggerShortcutDefinition,
} from '../data/triggerShortcuts';

type NodeCreatorPanelProps = {
  isOpen: boolean;
  /** Omitted when embedded in the side panel - the tab bar owns closing. */
  onClose?: () => void;
  onSelectNode?: (nodeId: string | any) => void;
  currentWorkflowId?: string;
  /**
   * Render as a side-panel tab body instead of a floating card: fills its
   * container, drops the rounded/translucent card chrome and the close button
   * (the panel's own tab bar handles that). The palette content itself is
   * unchanged - same rows, same breadcrumb, same search.
   */
  embedded?: boolean;
};

export { getPaletteItemDataFromId };

export function NodeCreatorPanel({ isOpen, onClose, onSelectNode, currentWorkflowId, embedded = false }: NodeCreatorPanelProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const { isRunMode } = useWorkflowMode();
  const queryClient = useQueryClient();
  const shortcutCreation = useMutation({
    mutationFn: async (shortcut: TriggerShortcutDefinition) => {
      const catalog = await queryClient.fetchQuery({
        queryKey: ['trigger-shortcut-tool', shortcut.apiName, shortcut.toolSlug],
        queryFn: () => fetchCatalogTool(shortcut.apiName, shortcut.toolSlug),
        staleTime: 5 * 60 * 1000,
      });
      return buildTriggerShortcutSelection(shortcut, {
        description: t(`triggerShortcuts.${shortcut.id}.description`),
        triggerLabel: t(`triggerShortcuts.${shortcut.id}.triggerLabel`),
        actionLabel: t(`triggerShortcuts.${shortcut.id}.actionLabel`),
      }, catalog);
    },
    onSuccess: (selection) => onSelectNode?.(selection),
  });
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

  // Per-plan availability. Asked ONCE for the whole panel (the hook holds a single
  // shared query) and answered per row from the map already in hand, so a palette
  // of two hundred integrations still makes one request.
  const { lockFor } = usePlanFeatureGate();

  const nodeLock = React.useCallback(
    (paletteId: string): PlanLock => lockFor([nodeFeatureKey(paletteId)]),
    [lockFor],
  );
  const catalogLock = React.useCallback(
    (apiSlug: string | null | undefined, toolSlug?: string | null): PlanLock =>
      lockFor(catalogFeatureKeys(apiSlug, toolSlug)),
    [lockFor],
  );

  /**
   * The plan marker is INFORMATION, not a gate: the row still adds its node.
   * A user may legitimately build the workflow now and subscribe before running
   * it, and the node carries the restriction visibly on the canvas from the
   * moment it lands there.
   */
  const guardClick = React.useCallback(
    (_lock: PlanLock, _label: string, action: () => void) => action,
    [],
  );
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

  /**
   * Palette rows that open a picker instead of dropping a node.
   *
   * Kept as one list because the search results and the category list must agree on
   * it: a row that navigates in one place and drops a half-configured node in the
   * other is the same bug twice. Mirrors the branches of `handleItemClick` and
   * `handleTriggerClick` below - add an id to both when you add a picker.
   */
  /**
   * The rows that are a FOLDER rather than a thing: the five tiles the palette
   * opens on, plus the integrations tile.
   *
   * It exists because no rule over the props can tell a folder from a row that
   * drills down - "Triggers" and "Tables trigger" are both `nodeKind: 'entry'`
   * with a chevron. Declaring it is therefore the ONLY way, and declaring it by
   * ID is what keeps the three call sites agreeing: the same row reached from
   * the root, from inside a category, or from a search hit must say the same
   * thing about itself. It was `paletteRole="category"` hardcoded on the whole
   * list before, which badged all 29 Core nodes "Category" and withheld the
   * drag hint from rows that visibly offer a grip.
   */
  const GROUP_TILE_IDS = React.useMemo(() => new Set([
    'triggers', 'ai', 'flow', 'core', 'mcp',
  ]), []);
  const paletteRoleFor = React.useCallback(
    (id: string) => (GROUP_TILE_IDS.has(id) ? ('category' as const) : undefined),
    [GROUP_TILE_IDS],
  );

  const NAV_ONLY_IDS = React.useMemo(() => new Set([
    'mcp', 'triggers', 'tables-trigger', 'table', 'interface', 'ai-agent', 'sub_workflow',
    'workflows-trigger', 'error-trigger',
    'create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row',
  ]), []);

  /**
   * Every node the palette can create, flattened once.
   *
   * Search used to see only what the CURRENT screen listed, which at the root is the
   * five category cards: typing "webhook", "split" or "delete row" found nothing,
   * because those live one or two levels down. This index is what the search box
   * filters instead, so a node is reachable by its name from anywhere.
   *
   * Triggers come from TRIGGER_TYPES rather than the tree because that list is what
   * the Triggers screen actually renders (it carries `workflows-trigger`, which has no
   * node class), and claiming them first means a duplicate in the tree is skipped and
   * every trigger routes through the one handler that knows which ones open a picker.
   */
  const searchableNodes = React.useMemo(() => {
    const topLevelIds = new Set(paletteTree.map((category) => category.id));
    const seen = new Set<string>();
    const result: { id: string; name: string; description: string; isTrigger: boolean }[] = [];

    TRIGGER_TYPES.forEach((trigger) => {
      seen.add(trigger.id);
      result.push({ id: trigger.id, name: trigger.name, description: trigger.description, isTrigger: true });
    });

    const walk = (nodes: PaletteCategoryNode[]) => {
      nodes.forEach((node) => {
        if (node.children?.length) { walk(node.children); return; }
        if (node.type !== 'node') return;
        const id = node.nodeClassId || node.id;
        // The tree nests a node of the same id under its own category ("triggers"
        // inside Triggers, "mcp" inside MCPs) - those ARE the category cards, already
        // listed above the results. The other mcp-* classes exist only to match
        // existing canvas nodes and are never rendered as palette rows.
        if (topLevelIds.has(id) || id.startsWith('mcp')) return;
        if (seen.has(id)) return;
        seen.add(id);
        result.push({ id, name: node.name, description: node.description, isTrigger: false });
      });
    };
    walk(paletteTree);
    return result;
  }, [paletteTree]);

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

  /**
   * Every navigation lands at the TOP of the list.
   *
   * Now that the whole palette scrolls as one, drilling into a category unmounts
   * the Frequently Used block from inside the scroller: the content shrinks, the
   * browser clamps the retained scrollTop, and the child list opens part-way down
   * instead of at its first item. Unobservable before - the bottom-dock scroller
   * had no scrollable content to retain a position in.
   */
  const scrollRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, [navigationLevel, selectedCategoryId, selectedApiId, selectedDataSourceId, selectedType, searchQuery]);

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

  /**
   * Nodes matching the search box, at the root screen only.
   *
   * Inside a category the existing child filter already answers "what here matches",
   * and re-listing the whole catalogue underneath it would bury the answer.
   *
   * Ordering puts a name hit before a description hit, and a name that STARTS with the
   * query first of all: typing "split" must offer the Split node before every node
   * whose description happens to mention splitting.
   */
  const filteredNodes = React.useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query || navigationLevel !== 'categories' || selectedCategoryId) return [];
    const score = (node: { id: string; name: string; description: string }) => {
      const name = node.name.toLowerCase();
      if (name.startsWith(query)) return 0;
      if (name.includes(query)) return 1;
      if (node.id.toLowerCase().includes(query)) return 2;
      return 3;
    };
    return searchableNodes
      .map((node) => ({ node, rank: score(node) }))
      .filter(({ node, rank }) => rank < 3 || (node.description || '').toLowerCase().includes(query))
      .sort((a, b) => a.rank - b.rank)
      .map(({ node }) => node);
  }, [searchableNodes, searchQuery, navigationLevel, selectedCategoryId]);

  // Ranked integrations section - lazy in two steps. `popularSectionRef` latches the
  // first time the section scrolls into view, which is what enables the query at all;
  // `popularLoadMoreRef` then pages it. Opening the palette on the categories fetches
  // nothing.
  const isRootScreen = navigationLevel === 'categories' && !selectedCategoryId && !searchQuery.trim();
  const [popularSectionRef, popularSectionSeen] = useOnVisibleOnce(isOpen && isRootScreen);
  const {
    data: popularApisData,
    fetchNextPage: fetchNextPopularPage,
    hasNextPage: hasNextPopularPage,
    isFetching: isFetchingPopular,
    isLoading: isLoadingPopularInitial,
  } = usePopularApis(isOpen && isRootScreen && popularSectionSeen);

  const popularApis = React.useMemo(() => {
    const all = popularApisData?.pages.flatMap((page) => page.content ?? []) || [];
    // The pages come from a total order over a fixed catalogue, so a repeat means two
    // fetches straddled a flush that reordered the ranking. Keep the first sighting:
    // dropping the row entirely would make an integration vanish mid-scroll.
    const unique = new Map<string, typeof all[0]>();
    all.forEach((api) => { if (api?.slug && !unique.has(api.slug)) unique.set(api.slug, api); });
    return Array.from(unique.values());
  }, [popularApisData]);

  const popularLoadMoreRef = useLazyLoadObserver({
    enabled: isOpen && isRootScreen && popularSectionSeen,
    hasMore: !!hasNextPopularPage,
    isLoading: isFetchingPopular,
    isInitialLoading: isLoadingPopularInitial,
    dataLength: popularApis.length,
    onLoadMore: () => fetchNextPopularPage(),
  });

  /**
   * Where the tools screen looks up its parent API.
   *
   * It used to read the `apis` list alone, which is loaded only by the MCP screen and
   * by a search. Reached from the ranked integrations section that list is EMPTY, so a
   * tool picked there produced a node with a blank `apiName` and no integration icon -
   * silently, because every one of those fields is optional. Both entry points feed the
   * same lookup, so both produce the same node.
   */
  const apiLookup = React.useMemo(() => {
    const bySlug = new Map<string, ApiSystem>();
    popularApis.forEach((api) => { if (api?.slug) bySlug.set(api.slug, api); });
    // The searched/browsed list wins on a collision: it is the fresher of the two.
    apis.forEach((api) => { if (api?.slug) bySlug.set(api.slug, api); });
    return bySlug;
  }, [apis, popularApis]);

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

  /**
   * Click a trigger, from the Triggers screen or from a search result.
   *
   * Three of them open a picker rather than dropping a node, and the picker needs
   * `selectedType` set: reached from the Triggers screen it already was, reached from a
   * search result it is null, which is exactly how a Tables trigger would have been
   * built as an ordinary table node. Setting it here makes both routes identical.
   */
  const handleTriggerClick = (triggerId: string, name?: string, description?: string) => {
    if (triggerId === 'tables-trigger') {
      setNavigationLevel('datasources'); setSearchQuery('');
      setSelectedDataSourceId(null); setSelectedCategoryId(null); setSelectedType('triggers');
      return;
    }
    if (triggerId === 'workflows-trigger' || triggerId === 'error-trigger') {
      setNavigationLevel('workflows'); setSearchQuery(''); setSelectedCategoryId(null);
      setSelectedType(triggerId === 'error-trigger' ? 'error_trigger' : 'triggers');
      return;
    }
    // Standalone trigger resources (webhook/schedule/chat/form): create the React-Flow
    // node only. The resource itself is created once by the form's auto-create effect
    // on inspect, using a stable sourceNodeId so refreshes hit the backend dedup
    // instead of burning quota.
    onSelectNode?.(getPaletteItemDataFromId(triggerId, name, description));
  };

  const handleToolClick = (tool: ApiTool) => {
    const toolApiSlug = tool.apiSlug || selectedApiId;
    const api = toolApiSlug ? apiLookup.get(toolApiSlug) : undefined;
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

  const hasBreadcrumb = selectedCategoryId || navigationLevel !== 'categories';

  return (
    // 150ms, the same delay the Run tab's step list uses. The palette row's hover
    // card carries the untruncated description and what a click and a drag each
    // do, so it is something the user waits for rather than something that gets
    // in the way; at 1s it read as broken on the rows that need it most.
    <TooltipProvider delayDuration={150}>
      <div data-node-creator-panel className={clsx(
          'flex flex-col overflow-hidden',
          embedded
            ? 'h-full w-full min-h-0'
            // Floating (not embedded in the side panel): the same chrome surface
            // as the toolbar and the settings panel it shares the canvas with.
            : `w-[min(340px,calc(100vw-48px))] max-h-[90vh] pointer-events-auto relative z-[100] ${canvasChromeSurfaceClass}`,
        )}
        onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); e.dataTransfer.dropEffect = 'none'; }}
        onDrop={(e) => { e.preventDefault(); e.stopPropagation(); }}>

        {/* Top bar: breadcrumb (left, when present) + a single in-flow close button
            (right). One close for every screen size - no floating offset that clips.
            Embedded in the side panel the tab bar owns closing, so the row only
            renders when there is a breadcrumb to show. */}
        <div className={clsx(
          'flex items-center justify-between gap-2 px-5 flex-shrink-0',
          embedded ? (hasBreadcrumb ? 'pt-2' : 'hidden') : 'pt-3',
        )}>
          <nav className="flex min-w-0 flex-1 items-center gap-1.5 text-sm" aria-label="Breadcrumb">
            {hasBreadcrumb && breadcrumbItems.map((item, index) => {
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
          {!embedded && onClose && (
            <Button onClick={onClose} variant="ghost" size="icon" className="h-7 w-7 flex-shrink-0" title={t('close')}>
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>

        {/* Search */}
        <div className="px-5 pt-4 sm:pt-4 flex-shrink-0">
          <div className="relative flex items-center">
            <div className="absolute left-3 pointer-events-none z-10"><Search className="h-4 w-4 text-gray-400" /></div>
            <Input type="text" placeholder={t('searchPlaceholder', { context: navigationLevel === 'apis' ? 'mcp' : navigationLevel === 'tools' ? 'tools' : 'nodes' })} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="pl-9 pr-9" autoFocus />
            {searchQuery && <button onClick={() => setSearchQuery('')} className="absolute right-3 z-10 text-gray-400 hover:text-gray-600"><X className="h-4 w-4" /></button>}
          </div>
        </div>

        {/* Content
            Frequently Used scrolls WITH the categories rather than being pinned
            above them. Pinned, it took a fixed slice of the height, and in the
            bottom dock - where the panel is a few hundred pixels tall - that
            slice was the whole panel: the categories were squeezed to nothing and
            no scrollbar could reach them, because the only scrollable region was
            the sliver underneath. The palette's content is one list; it scrolls as
            one. The search and breadcrumb stay pinned - they are controls over the
            list, not part of it. */}
        <div ref={scrollRef} className="flex-1 overflow-y-auto min-h-0 pr-2">
          {/* Frequently Used
              `pr-0` cancels the scroller's gutter for this block only: it used to
              be a direct child of the panel with symmetric padding, and inheriting
              the gutter would leave the grid off-centre and its separator rule
              stopping short of the right edge. Its `pl-3` matches every other
              section because the hover cards are placed by adding that inset
              back (`PALETTE_LIST_ROW_INSET_PX`), so a section indented
              differently would open its card at a different distance. */}
          {navigationLevel === 'categories' && !selectedCategoryId && (
            <div className="pl-3 pr-0 pb-4 pt-2 border-b border-gray-200 dark:border-gray-800">
              <div className="space-y-2">
                <div className="px-3 pt-3 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('frequentlyUsed')}</div>
                <div className="grid grid-cols-2 gap-2">
                  {featuredNodes.map((node) => {
                    const isNavItem = node.isNav || node.id === 'ai-agent';
                    const paletteData = isNavItem ? undefined : getPaletteItemDataFromId(node.id, node.label, node.description);
                    const lock = nodeLock(node.id);
                    return (
                      <DraggableNodeItem
                        key={node.id}
                        id={node.id}
                        label={node.label}
                        description={node.description}
                        lockedPlan={lock.locked ? lock.requiredPlan : null}
                        onClick={guardClick(lock, node.label, () => {
                          if (node.isNav) {
                            handleItemClick({ id: node.id, name: node.label, description: node.description } as PaletteCategoryNode);
                          } else {
                            onSelectNode?.(node.id);
                          }
                        })}
                        dragData={paletteData}
                        disableDrag={isNavItem}
                        showArrow={isNavItem}
                        // A nav tile here is a GROUP, not the node its class
                        // describes: 'triggers' is `kind: 'entry'`, so without
                        // this the folder is badged "Trigger".
                        paletteRole={isNavItem ? 'category' : undefined}
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

          {/* Categories */}
          {navigationLevel === 'categories' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {/* Named like Frequently Used above it. Unlabelled, the five category
                  cards read as the whole palette, which is exactly what stops people
                  scrolling to the ranked integrations underneath. Only at the root:
                  one level down the breadcrumb already names where you are, and only
                  when there is something under it - a search that matches no category
                  would otherwise leave a heading standing over nothing. */}
              {!selectedCategoryId && (!searchQuery.trim() || filteredCategories.length > 0) && (
                <div className="px-3 pt-1 pb-1 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('paletteCategories')}</div>
              )}
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
                        const lock = nodeLock(category.nodeClassId || category.id);
                        return (
                          <DraggableNodeItem key={category.id} id={category.nodeClassId || category.id} label={category.name} description={category.description}
                            lockedPlan={lock.locked ? lock.requiredPlan : null}
                            onClick={guardClick(lock, category.name, () => isNavItem ? handleItemClick(category) : onSelectNode?.(category.nodeClassId || category.id))}
                            paletteRole={paletteRoleFor(category.nodeClassId || category.id)}
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
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-xl bg-blue-100 dark:bg-blue-900/30"><Bot className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : isFlowCategory
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-xl bg-violet-100 dark:bg-violet-900/30"><Route className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : isCoreCategory
                    ? <span className="flex items-center justify-center flex-shrink-0 h-8 w-8 rounded-xl bg-yellow-100 dark:bg-yellow-900/30"><Cpu className="h-5 w-5 text-slate-900 dark:text-slate-100" strokeWidth={1.7} /></span>
                    : undefined;

                  const lock = nodeLock(category.nodeClassId || category.id);
                  return (
                    <DraggableNodeItem key={category.id} id={category.nodeClassId || category.id} label={category.name} description={category.description}
                      lockedPlan={lock.locked ? lock.requiredPlan : null}
                      onClick={guardClick(lock, category.name, () => handleItemClick(category))} dragData={paletteData} disableDrag={isNav}
                      // This list renders the root tiles AND the children of a
                      // category that has been opened, so only the tiles may
                      // declare themselves folders; every node inside Core, AI
                      // or Data resolves from its own props.
                      paletteRole={paletteRoleFor(category.id)}
                      showArrow={showArrow} nodeId={category.nodeClassId || category.id} nodeKind={nodeClass?.kind} nodeFamily={nodeClass?.family as NodeFamily}
                      bgClassName={isMcp ? 'bg-gray-100 dark:bg-gray-800' : needsFallbackBg ? fallbackBg : undefined} isMcp={isMcp} iconSize="sm"
                      iconOverride={categoryIcon} />
                  );
                });
              })()}

              {/* Search results for NODES.
                  The palette's own nodes, matched across every category rather than
                  only the screen in front of you - see `searchableNodes`. Rendered
                  before the integrations because they need no network round trip and
                  are the answer most of the time. */}
              {searchQuery.trim().length > 0 && filteredNodes.length > 0 && (
                <>
                  <div className="px-3 pt-4 pb-2 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide border-t border-gray-200 dark:border-gray-800 mt-2">{t('paletteNodes')}</div>
                  {filteredNodes.map((node) => {
                    const nodeClass = findNodeClassById(node.id);
                    const isNav = NAV_ONLY_IDS.has(node.id);
                    const paletteData = getPaletteItemDataFromId(node.id, node.name, node.description);
                    const lock = nodeLock(node.id);
                    return (
                      <DraggableNodeItem key={`node-${node.id}`} id={node.id} label={node.name} description={node.description}
                        lockedPlan={lock.locked ? lock.requiredPlan : null}
                        onClick={guardClick(lock, node.name, () => (
                          node.isTrigger
                            ? handleTriggerClick(node.id, node.name, node.description)
                            : handleItemClick({ id: node.id, name: node.name, description: node.description } as PaletteCategoryNode)
                        ))}
                        dragData={isNav ? undefined : paletteData} disableDrag={isNav}
                        showArrow={isNav} arrowType="arrow"
                        // Only a folder hit is a folder. A NAV_ONLY hit that is a
                        // TRIGGER opening a picker (tables, workflows, error) is a
                        // trigger, and said so under the Triggers group - a row
                        // must not answer differently for having been searched.
                        paletteRole={paletteRoleFor(node.id)}
                        nodeId={node.id} nodeKind={node.isTrigger ? 'entry' : nodeClass?.kind} nodeFamily={nodeClass?.family as NodeFamily}
                        iconSize="sm" />
                    );
                  })}
                </>
              )}

              {/* Search results for APIs */}
              {searchQuery.trim().length > 0 && filteredApis.length > 0 && (
                <>
                  <div className="px-3 pt-4 pb-2 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide border-t border-gray-200 dark:border-gray-800 mt-2">{t('mcp')}</div>
                  {filteredApis.map((api) => (
                    <ApiPaletteRow key={api.slug} api={api} lock={catalogLock(api.slug)}
                      onOpen={() => handleApiClick(api)} />
                  ))}
                </>
              )}
            </div>
          )}

          {/* Ranked integrations.
              The catalogue in PLATFORM-USAGE order, under its own rule and title, so
              the palette offers what people actually build with instead of asking
              everyone to guess a name into the search box first. Only at the root and
              only with the search box empty: while searching, the MCP results above
              already answer the question being asked.

              No count is shown anywhere - the ORDER is the whole message, and a number
              next to an integration would read as a recommendation the platform has
              not earned and would put a cross-tenant volume on a builder's screen.

              Lazy twice over: `popularSectionRef` latches the first time this scrolls
              into view (nothing is fetched for someone who only used the categories),
              and `popularLoadMoreRef` pages the rest. */}
          {isRootScreen && (
            <div ref={popularSectionRef} className="py-2 pl-3 pr-3 space-y-1 border-t border-gray-200 dark:border-gray-800 mt-2">
              <div className="px-3 pt-3 pb-1 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('paletteIntegrations')}</div>
              {isLoadingPopularInitial ? <ApiListSkeleton count={5} /> : (
                <>
                  {popularApis.map((api) => (
                    <ApiPaletteRow key={`popular-${api.slug}`} api={api} lock={catalogLock(api.slug)}
                      onOpen={() => handleApiClick(api)} />
                  ))}
                  {popularSectionSeen && popularApis.length === 0 && !isFetchingPopular && (
                    <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noApiFound')}</div>
                  )}
                </>
              )}
              {hasNextPopularPage && !isLoadingPopularInitial && (
                <div ref={popularLoadMoreRef} className="py-4 flex justify-center">{isFetchingPopular && <LoadingSpinner size="sm" />}</div>
              )}
            </div>
          )}

          {/* APIs List */}
          {navigationLevel === 'apis' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {isLoadingApisInitial ? <ApiListSkeleton count={5} /> : (
                <>
                  {apis.map((api) => (
                    <ApiPaletteRow key={api.slug} api={api} lock={catalogLock(api.slug)}
                      onOpen={() => handleApiClick(api)} />
                  ))}
                  {apis.length === 0 && !isFetchingApis && <div className="text-center py-8 text-gray-500 dark:text-gray-400">{t('noApiFound')}</div>}
                </>
              )}
              {hasNextPage && !isLoadingApisInitial && <div ref={apiLoadMoreRef} className="py-4 flex justify-center">{isFetchingApis && <LoadingSpinner size="sm" />}</div>}
            </div>
          )}

          {/* Tools List */}
          {navigationLevel === 'tools' && (
            <div className="py-2 pl-3 pr-3 space-y-1">
              {isLoadingTools ? <ToolListSkeleton count={5} /> : (
                <>
                  {apiTools.filter(t => !searchQuery.trim() || t.name.toLowerCase().includes(searchQuery.toLowerCase()) || t.description?.toLowerCase().includes(searchQuery.toLowerCase())).map((tool) => {
                    const toolApiSlug = tool.apiSlug || selectedApiId;
                    const parentApi = toolApiSlug ? apiLookup.get(toolApiSlug) : undefined;
                    const iconSlug = tool.iconSlug || parentApi?.iconSlug;
                    const iconUrl = tool.iconUrl || parentApi?.iconUrl;
                    const lock = catalogLock(toolApiSlug, tool.slug);
                    return (
                      <DraggableNodeItem key={tool.slug} id={`tool-${tool.slug}`} label={tool.name} description={tool.description}
                        lockedPlan={lock.locked ? lock.requiredPlan : null}
                        onClick={guardClick(lock, tool.name, () => handleToolClick(tool))}
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
                        // Same row, one level up from the table list below, which
                        // already says `entry`: under 'triggers' this adds a
                        // trigger, and the gap showed once the kind became visible.
                        nodeKind={selectedType === 'triggers' ? 'entry' : undefined}
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
              <div className="px-2 pb-1 pt-1 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">
                {t('coreTriggers')}
              </div>
              {TRIGGER_TYPES.filter(t => !searchQuery.trim() || t.name.toLowerCase().includes(searchQuery.toLowerCase())).map((trigger) => {
                const isNavTrigger = trigger.id === 'tables-trigger' || trigger.id === 'workflows-trigger' || trigger.id === 'error-trigger';
                const paletteData = getPaletteItemDataFromId(trigger.id, trigger.name, trigger.description);
                const lock = nodeLock(trigger.id);
                return (
                  <DraggableNodeItem key={trigger.id} id={trigger.id} label={trigger.name} description={trigger.description}
                    lockedPlan={lock.locked ? lock.requiredPlan : null}
                    onClick={guardClick(lock, trigger.name, () => handleTriggerClick(trigger.id, trigger.name, trigger.description))}
                    dragData={isNavTrigger ? undefined : paletteData} disableDrag={isNavTrigger}
                    showArrow={isNavTrigger} arrowType="arrow" nodeId={trigger.id} nodeKind="entry" iconSize="sm" />
                );
              })}

              {TRIGGER_SHORTCUTS.some((shortcut) => {
                const query = searchQuery.trim().toLowerCase();
                return !query
                  || t(`triggerShortcuts.${shortcut.id}.label`).toLowerCase().includes(query)
                  || t(`triggerShortcuts.${shortcut.id}.description`).toLowerCase().includes(query);
              }) && (
                <div className="mt-3 border-t border-gray-200 pt-3 dark:border-gray-700">
                  <div className="px-2 pb-1 text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wide">
                    {t('appTriggerShortcuts')}
                  </div>
                  <p className="px-2 pb-2 text-xs text-gray-400 dark:text-gray-500">
                    {t('appTriggerShortcutsDescription')}
                  </p>
                  <div className="space-y-1">
                    {TRIGGER_SHORTCUTS.filter((shortcut) => {
                      const query = searchQuery.trim().toLowerCase();
                      return !query
                        || t(`triggerShortcuts.${shortcut.id}.label`).toLowerCase().includes(query)
                        || t(`triggerShortcuts.${shortcut.id}.description`).toLowerCase().includes(query);
                    }).map((shortcut) => {
                      const label = t(`triggerShortcuts.${shortcut.id}.label`);
                      const description = t(`triggerShortcuts.${shortcut.id}.description`);
                      const triggerNodeId = shortcut.mode === 'polling' ? 'schedule-trigger' : 'webhook-trigger';
                      const lock = shortcut.mode === 'polling'
                        ? lockFor([nodeFeatureKey(triggerNodeId), ...catalogFeatureKeys(shortcut.apiSlug, shortcut.toolSlug)])
                        : nodeLock(triggerNodeId);
                      return (
                        <DraggableNodeItem
                          key={shortcut.id}
                          id={`trigger-shortcut-${shortcut.id}`}
                          label={label}
                          description={description}
                          lockedPlan={lock.locked ? lock.requiredPlan : null}
                          onClick={guardClick(lock, label, () => {
                            if (!shortcutCreation.isPending) shortcutCreation.mutate(shortcut);
                          })}
                          disableDrag
                          nodeId={triggerNodeId}
                          nodeKind="entry"
                          nodeFamily="trigger"
                          iconSlug={shortcut.iconSlug}
                          iconSize="sm"
                        />
                      );
                    })}
                    {shortcutCreation.isPending && <LoadingSpinner size="sm" />}
                    {shortcutCreation.isError && (
                      <p role="alert" className="px-2 text-sm text-red-600">{t('appTriggerLoadError')}</p>
                    )}
                  </div>
                </div>
              )}
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

/**
 * One integration row.
 *
 * <p>Its own component only because the two places that render it are `map`
 * expressions with no block body, so there is nowhere to put the `const lock`
 * the other rows declare inline. Behaviour is identical to them.
 */
function ApiPaletteRow({
  api,
  lock,
  onOpen,
}: {
  api: ApiSystem;
  lock: PlanLock;
  onOpen: () => void;
}) {
  return (
    <DraggableNodeItem
      id={`api-${api.slug}`}
      label={api.apiName}
      description={api.description}
      secondaryInfo={api.toolsCount ? `${api.toolsCount} tool${api.toolsCount > 1 ? 's' : ''}` : undefined}
      lockedPlan={lock.locked ? lock.requiredPlan : null}
      onClick={onOpen}
      showArrow
      arrowType="arrow"
      dragData={{ id: `api-${api.slug}`, label: api.apiName, description: api.description, kind: 'tool', nodeType: 'flowNode', apiData: { apiSlug: api.slug, apiName: api.apiName, iconSlug: api.iconSlug } }}
      iconSlug={api.iconSlug}
      isMcp
    />
  );
}
