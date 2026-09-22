'use client';

import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Plus, Search, AppWindow, Monitor, Table, Workflow, ChevronLeft, ChevronRight, Bot, FolderOpen, MessageSquare, Briefcase, FileText, CalendarClock } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Input } from '@/components/ui/input';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useLocale, useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { interfaceService } from '@/lib/api/orchestrator/interface.service';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { AgentPanelContent } from '@/components/app/AgentPanelContent';
import { StorageExplorerTab } from '@/app/workflows/builder/components/inspector/StorageExplorerTab';
import { ConversationPanelContent } from '@/components/app/ConversationPanelContent';
import { FileDetailView } from '@/components/app/FileDetailView';
import { getProjectIcon } from '@/components/project/ProjectMultiStepModal';
import type { ProjectResources } from '@/lib/api/orchestrator/project.types';
import { conversationApi } from '@/lib/api/conversationApi';
import { projectService } from '@/lib/api/orchestrator/project.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { AGENDA_PANEL_TAB_ID, applicationPanelTabId, parseTabResource, workflowPanelTabId } from '@/lib/sidePanel/tabResource';
import { openAgendaPanel } from '@/lib/sidePanel/openAgendaPanel';
import { S3_FILES_FILTER, storageApi } from '@/lib/api/storage-api';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import type { WorkflowRun } from '@/lib/api/orchestrator';
import { useWorkflowLogsSidePanel } from '@/components/workflow/useWorkflowLogsSidePanel';
import { RunHistoryList } from '@/components/workflow/run-panel/RunHistoryList';

interface PickerItem {
  id: string;
  label: string;
  category: 'agenda' | 'application' | 'interface' | 'table' | 'workflow' | 'logs' | 'agent' | 'conversation' | 'project' | 'files';
  publicationId?: string;
  avatarUrl?: string;
  iconKey?: string;
  color?: string;
}

type CategoryType = PickerItem['category'];
type CategoryCounts = Record<CategoryType, number>;

interface AddTabPickerProps {
  variant?: 'tab-bar' | 'header';
}

const ITEMS_PER_PAGE = 10;
const PICKER_API_PAGE_SIZE = 100;
const CACHE_TTL_MS = 30_000; // Reuse fetched data for 30 seconds

const CATEGORY_ORDER: CategoryType[] = ['agenda', 'application', 'interface', 'table', 'workflow', 'logs', 'agent', 'conversation', 'project', 'files'];
const EMPTY_CATEGORY_COUNTS: CategoryCounts = {
  agenda: 0,
  application: 0,
  interface: 0,
  table: 0,
  workflow: 0,
  logs: 0,
  agent: 0,
  conversation: 0,
  project: 0,
  files: 0,
};

type ServerPagedCategory = 'interface' | 'workflow' | 'conversation';
const SERVER_PAGED_CATEGORIES = new Set<CategoryType>(['interface', 'workflow', 'conversation']);
const INITIAL_LOADED_PAGES: Record<ServerPagedCategory, number> = {
  interface: 0,
  workflow: 0,
  conversation: 0,
};

export function AddTabPicker({ variant = 'tab-bar' }: AddTabPickerProps) {
  const sidePanel = useSidePanelSafe();
  const t = useTranslations('sidePanel');
  const tSidebarNav = useTranslations('sidebar.nav');
  const locale = useLocale();
  const countFormatter = useMemo(() => new Intl.NumberFormat(locale), [locale]);
  const { openWorkflowLogs } = useWorkflowLogsSidePanel();

  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebouncedValue(search, 250);
  const [items, setItems] = useState<PickerItem[]>([]);
  const [categoryCounts, setCategoryCounts] = useState<CategoryCounts>(EMPTY_CATEGORY_COUNTS);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Navigation: categories → items | projectCategories → projectItems
  const [view, setView] = useState<'categories' | 'items' | 'projectCategories' | 'projectItems' | 'logWorkflows' | 'logRuns'>('categories');
  const [selectedCategory, setSelectedCategory] = useState<CategoryType | null>(null);
  const [categorySearch, setCategorySearch] = useState('');
  const debouncedCategorySearch = useDebouncedValue(categorySearch, 250);
  const [visibleCount, setVisibleCount] = useState(ITEMS_PER_PAGE);
  const [loadedPages, setLoadedPages] = useState(INITIAL_LOADED_PAGES);
  const [loadingMoreCategory, setLoadingMoreCategory] = useState(false);
  const [serverSearchItems, setServerSearchItems] = useState<PickerItem[]>([]);
  const searchRequestIdRef = useRef(0);

  // Project drill-down state
  const [selectedProject, setSelectedProject] = useState<PickerItem | null>(null);
  const [projectResources, setProjectResources] = useState<PickerItem[]>([]);
  const [projectResourcesLoading, setProjectResourcesLoading] = useState(false);
  const [selectedProjectCategory, setSelectedProjectCategory] = useState<CategoryType | null>(null);
  const [projectCategorySearch, setProjectCategorySearch] = useState('');

  // Logs drill-down: workflow -> run -> logs child view.
  const [selectedLogWorkflow, setSelectedLogWorkflow] = useState<PickerItem | null>(null);

  // Cache: avoid re-fetching all 8 APIs on every popover open.
  // Trade-off: resources created in the last 30s won't appear until TTL expires.
  const cacheRef = useRef<{ items: PickerItem[]; counts: CategoryCounts; ts: number } | null>(null);
  const skipFetchRef = useRef(false);

  // Reset navigation state on popover open/close
  useEffect(() => {
    if (open) {
      setView('categories');
      setSelectedCategory(null);
      setSelectedProject(null);
      setProjectResources([]);
      setSelectedProjectCategory(null);
      setProjectCategorySearch('');
      setSelectedLogWorkflow(null);
      setSearch('');
      setCategorySearch('');
      setVisibleCount(ITEMS_PER_PAGE);

      // Single TTL check - useEffect #2 reads skipFetchRef to stay in sync
      const cache = cacheRef.current;
      const isCacheFresh = !!(cache && Date.now() - cache.ts < CACHE_TTL_MS);
      skipFetchRef.current = isCacheFresh;

      if (isCacheFresh) {
        setItems(cache!.items);
        setCategoryCounts(cache!.counts);
        setIsLoading(false);
        setError(null);
      } else {
        setItems([]);
        setCategoryCounts(EMPTY_CATEGORY_COUNTS);
        setLoadedPages(INITIAL_LOADED_PAGES);
        setIsLoading(true);
      }
    }
  }, [open]);

  // Fetch data when the popover opens (skipped if cache is fresh)
  useEffect(() => {
    if (!open || !sidePanel) return;
    if (skipFetchRef.current) return;

    let cancelled = false;
    setError(null);

    Promise.all([
      publicationService.getMyPublications(true).catch(() => ({ publications: [] as WorkflowPublication[] })),
      publicationService.getAcquiredApplications().catch(() => ({ applications: [] as { sourcePublicationId: string; name: string }[] })),
      interfaceService.getInterfacesPage({
        page: 0,
        size: PICKER_API_PAGE_SIZE,
        includeTemplates: false,
        excludeType: 'web_search',
      }).catch(() => ({ items: [], totalCount: 0 })),
      orchestratorApi.getDataSources().catch(() => []),
      orchestratorApi.getWorkflowsPage({ page: 0, size: PICKER_API_PAGE_SIZE })
        .catch(() => ({ workflows: [], totalCount: 0 })),
      orchestratorApi.getAgents().catch(() => []),
      conversationApi.getConversations(0, PICKER_API_PAGE_SIZE)
        .catch(() => ({ content: [], totalElements: 0 })),
      projectService.getProjects().catch(() => []),
      storageApi.getExplorerEntries({ page: 0, size: 1, ...S3_FILES_FILTER })
        .catch(() => ({ totalElements: 0 })),
    ]).then(([myPubs, acquired, interfacesPage, dataSources, workflowsPage, agents, conversationsData, projects, filesPage]) => {
      if (cancelled) return;

      const pickerItems: PickerItem[] = [];
      const interfaces = interfacesPage.items;
      const workflows = workflowsPage.workflows;

      // Merge my publications + acquired, deduplicate by publicationId
      const seenPubIds = new Set<string>();
      for (const pub of myPubs.publications) {
        if (!seenPubIds.has(pub.id)) {
          seenPubIds.add(pub.id);
          pickerItems.push({
            id: applicationPanelTabId(pub.id),
            label: pub.title,
            category: 'application',
            publicationId: pub.id,
          });
        }
      }
      for (const app of acquired.applications) {
        if (!seenPubIds.has(app.sourcePublicationId)) {
          seenPubIds.add(app.sourcePublicationId);
          pickerItems.push({
            id: applicationPanelTabId(app.sourcePublicationId),
            label: app.name,
            category: 'application',
            publicationId: app.sourcePublicationId,
          });
        }
      }

      for (const iface of interfaces) {
        if (iface.interfaceType === 'web_search') continue;
        pickerItems.push({
          id: `interface-${iface.id}`,
          label: iface.name,
          category: 'interface',
        });
      }

      for (const ds of dataSources) {
        pickerItems.push({
          id: `datasource-${ds.id}`,
          label: ds.name,
          category: 'table',
        });
      }

      if (Array.isArray(workflows)) {
        for (const wf of workflows) {
          pickerItems.push({
            id: workflowPanelTabId(wf.id),
            label: wf.name,
            category: 'workflow',
          });
        }
      }

      if (Array.isArray(agents)) {
        for (const agent of agents) {
          pickerItems.push({
            id: `agent-${agent.id}`,
            label: agent.name,
            category: 'agent',
            avatarUrl: agent.avatarUrl || undefined,
          });
        }
      }

      const conversations = conversationsData.content;
      for (const conv of conversations) {
        pickerItems.push({
          id: `conversation-${conv.id}`,
          label: conv.title || t('untitled'),
          category: 'conversation',
        });
      }

      if (Array.isArray(projects)) {
        for (const project of projects) {
          pickerItems.push({
            id: `project-${project.id}`,
            label: project.name,
            category: 'project',
            iconKey: project.icon,
            color: project.color,
          });
        }
      }

      const counts: CategoryCounts = {
        agenda: 0,
        application: seenPubIds.size,
        interface: interfacesPage.totalCount ?? interfaces.length,
        table: dataSources.length,
        workflow: workflowsPage.totalCount ?? workflows.length,
        logs: workflowsPage.totalCount ?? workflows.length,
        agent: agents.length,
        conversation: conversationsData.totalElements,
        project: projects.length,
        files: filesPage.totalElements ?? 0,
      };

      cacheRef.current = { items: pickerItems, counts, ts: Date.now() };
      setItems(pickerItems);
      setCategoryCounts(counts);
      setIsLoading(false);
    }).catch(() => {
      if (!cancelled) {
        setError(t('errorLoading'));
        setIsLoading(false);
      }
    });

    return () => { cancelled = true; };
  }, [open, sidePanel, t]);

  // Search the server-backed families over the whole workspace. The picker still filters the
  // already-loaded families locally, while these queries make resources beyond page 1 findable.
  useEffect(() => {
    const requestId = ++searchRequestIdRef.current;
    if (!open) return;
    const isGlobalSearch = view === 'categories';
    const isLogWorkflowSearch = view === 'logWorkflows';
    const query = (isGlobalSearch ? debouncedSearch : debouncedCategorySearch).trim();
    const searchableCategory = isLogWorkflowSearch
      ? 'workflow'
      : selectedCategory && SERVER_PAGED_CATEGORIES.has(selectedCategory)
      ? selectedCategory as ServerPagedCategory
      : null;

    if (!query || (!isGlobalSearch && !searchableCategory)) {
      setServerSearchItems([]);
      return;
    }

    const categories: ServerPagedCategory[] = isGlobalSearch
      ? ['interface', 'workflow', 'conversation']
      : [searchableCategory!];

    const requests = categories.map(async category => {
      if (category === 'interface') {
        const page = await interfaceService.getInterfacesPage({
          page: 0,
          size: PICKER_API_PAGE_SIZE,
          q: query,
          includeTemplates: false,
          excludeType: 'web_search',
        });
        return page.items.map(iface => ({
          id: `interface-${iface.id}`,
          label: iface.name,
          category: 'interface' as const,
        }));
      }
      if (category === 'workflow') {
        const page = await orchestratorApi.getWorkflowsPage({
          page: 0,
          size: PICKER_API_PAGE_SIZE,
          q: query,
        });
        return page.workflows.map(workflow => ({
          id: workflowPanelTabId(workflow.id),
          label: workflow.name,
          category: 'workflow' as const,
        }));
      }
      const page = await conversationApi.searchConversations(
        query,
        'title',
        0,
        PICKER_API_PAGE_SIZE,
      );
      return page.content.map(conversation => ({
        id: `conversation-${conversation.id}`,
        label: conversation.title || t('untitled'),
        category: 'conversation' as const,
      }));
    });

    Promise.all(requests.map(request => request.catch(() => [])))
      .then(results => {
        if (requestId !== searchRequestIdRef.current) return;
        setServerSearchItems(results.flat());
      });
  }, [debouncedCategorySearch, debouncedSearch, open, selectedCategory, view]);

  // Items grouped by category (unfiltered, for counts)
  const itemsByCategory = useMemo(() => {
    const map: Record<CategoryType, PickerItem[]> = {
      agenda: [],
      application: [],
      interface: [],
      table: [],
      workflow: [],
      agent: [],
      conversation: [],
      project: [],
      files: [],
      logs: [],
    };
    for (const item of items) {
      map[item.category].push(item);
    }
    return map;
  }, [items]);

  // Cross-resource search results (for category view search)
  const searchFiltered = useMemo(() => {
    if (!search.trim()) return items;
    const q = search.toLowerCase();
    const localMatches = items.filter(item => item.label.toLowerCase().includes(q));
    return Array.from(new Map(
      [...localMatches, ...serverSearchItems].map(item => [item.id, item]),
    ).values());
  }, [items, search, serverSearchItems]);

  const searchApplications = useMemo(() => searchFiltered.filter(i => i.category === 'application'), [searchFiltered]);
  const searchInterfaces = useMemo(() => searchFiltered.filter(i => i.category === 'interface'), [searchFiltered]);
  const searchTables = useMemo(() => searchFiltered.filter(i => i.category === 'table'), [searchFiltered]);
  const searchWorkflows = useMemo(() => searchFiltered.filter(i => i.category === 'workflow'), [searchFiltered]);
  const searchAgents = useMemo(() => searchFiltered.filter(i => i.category === 'agent'), [searchFiltered]);
  const searchConversations = useMemo(() => searchFiltered.filter(i => i.category === 'conversation'), [searchFiltered]);
  const searchProjects = useMemo(() => searchFiltered.filter(i => i.category === 'project'), [searchFiltered]);
  const searchFiles = useMemo(() => searchFiltered.filter(i => i.category === 'files'), [searchFiltered]);
  const searchAgenda = useMemo<PickerItem[]>(() => {
    const label = tSidebarNav('agenda');
    return search.trim() && label.toLowerCase().includes(search.trim().toLowerCase())
      ? [{ id: AGENDA_PANEL_TAB_ID, label, category: 'agenda' }]
      : [];
  }, [search, tSidebarNav]);

  // Items for the selected category (filtered by category search)
  const categoryItems = useMemo(() => {
    if (!selectedCategory) return [];
    const remoteCategoryItems = serverSearchItems.filter(item => item.category === selectedCategory);
    const localCategoryItems = itemsByCategory[selectedCategory];
    if (!categorySearch.trim()) return localCategoryItems;
    const q = categorySearch.toLowerCase();
    const localMatches = localCategoryItems.filter(item => item.label.toLowerCase().includes(q));
    return Array.from(new Map(
      [...localMatches, ...remoteCategoryItems].map(item => [item.id, item]),
    ).values());
  }, [selectedCategory, itemsByCategory, categorySearch, serverSearchItems]);

  const loadNextCategoryPage = useCallback(async (category: ServerPagedCategory): Promise<boolean> => {
    if (loadingMoreCategory) return false;
    setLoadingMoreCategory(true);

    try {
      const nextPage = loadedPages[category] + 1;
      let pageItems: PickerItem[] = [];
      let totalCount = categoryCounts[category];

      if (category === 'interface') {
        const page = await interfaceService.getInterfacesPage({
          page: nextPage,
          size: PICKER_API_PAGE_SIZE,
          includeTemplates: false,
          excludeType: 'web_search',
        });
        totalCount = page.totalCount;
        pageItems = page.items.map(iface => ({
          id: `interface-${iface.id}`,
          label: iface.name,
          category: 'interface',
        }));
      } else if (category === 'workflow') {
        const page = await orchestratorApi.getWorkflowsPage({
          page: nextPage,
          size: PICKER_API_PAGE_SIZE,
        });
        totalCount = page.totalCount;
        pageItems = page.workflows.map(workflow => ({
          id: workflowPanelTabId(workflow.id),
          label: workflow.name,
          category: 'workflow',
        }));
      } else {
        const page = await conversationApi.getConversations(nextPage, PICKER_API_PAGE_SIZE);
        totalCount = page.totalElements;
        pageItems = page.content.map(conversation => ({
          id: `conversation-${conversation.id}`,
          label: conversation.title || t('untitled'),
          category: 'conversation',
        }));
      }

      const currentIds = new Set(items.map(item => item.id));
      const uniquePageItems = pageItems.filter(item => {
        if (currentIds.has(item.id)) return false;
        currentIds.add(item.id);
        return true;
      });
      const nextItems = [...items, ...uniquePageItems];
      const nextCounts = {
        ...categoryCounts,
        [category]: totalCount,
        ...(category === 'workflow' ? { logs: totalCount } : {}),
      };

      setItems(nextItems);
      setCategoryCounts(nextCounts);
      setLoadedPages(previous => ({ ...previous, [category]: nextPage }));
      cacheRef.current = { items: nextItems, counts: nextCounts, ts: Date.now() };
      return true;
    } catch {
      return false;
    } finally {
      setLoadingMoreCategory(false);
    }
  }, [categoryCounts, items, loadedPages, loadingMoreCategory]);

  const handleShowMore = useCallback(async () => {
    if (!selectedCategory) return;
    const nextVisibleCount = visibleCount + ITEMS_PER_PAGE;
    const needsAnotherServerPage = !categorySearch.trim()
      && SERVER_PAGED_CATEGORIES.has(selectedCategory)
      && nextVisibleCount > itemsByCategory[selectedCategory].length
      && itemsByCategory[selectedCategory].length < categoryCounts[selectedCategory];

    if (needsAnotherServerPage) {
      const loaded = await loadNextCategoryPage(selectedCategory as ServerPagedCategory);
      if (!loaded) return;
    }
    setVisibleCount(nextVisibleCount);
  }, [categoryCounts, categorySearch, itemsByCategory, loadNextCategoryPage, selectedCategory, visibleCount]);

  const logWorkflowItems = useMemo(() => {
    const workflows = itemsByCategory.workflow;
    if (!categorySearch.trim()) return workflows;
    const query = categorySearch.toLowerCase();
    const localMatches = workflows.filter((item) => item.label.toLowerCase().includes(query));
    const remoteMatches = serverSearchItems.filter((item) => item.category === 'workflow');
    return Array.from(new Map(
      [...localMatches, ...remoteMatches].map((item) => [item.id, item]),
    ).values());
  }, [categorySearch, itemsByCategory.workflow, serverSearchItems]);

  const handleShowMoreLogWorkflows = useCallback(async () => {
    const nextVisibleCount = visibleCount + ITEMS_PER_PAGE;
    const needsAnotherServerPage = !categorySearch.trim()
      && nextVisibleCount > itemsByCategory.workflow.length
      && itemsByCategory.workflow.length < categoryCounts.workflow;

    if (needsAnotherServerPage) {
      const loaded = await loadNextCategoryPage('workflow');
      if (!loaded) return;
    }
    setVisibleCount(nextVisibleCount);
  }, [categoryCounts.workflow, categorySearch, itemsByCategory.workflow.length, loadNextCategoryPage, visibleCount]);

  const handleProjectDrillDown = useCallback((item: PickerItem) => {
    const projId = item.id.replace('project-', '');
    setSelectedProject(item);
    setView('projectCategories');
    setProjectResourcesLoading(true);
    setSelectedProjectCategory(null);
    setProjectCategorySearch('');

    projectService.getProjectResources(projId).then((res: ProjectResources) => {
      const resourceItems: PickerItem[] = [];

      for (const agent of (res.agents || [])) {
        resourceItems.push({
          id: `agent-${agent.id}`,
          label: agent.name,
          category: 'agent',
          avatarUrl: agent.avatarUrl || undefined,
        });
      }
      for (const wf of (res.workflows || [])) {
        resourceItems.push({
          id: workflowPanelTabId(wf.id),
          label: wf.name,
          category: 'workflow',
        });
      }
      for (const iface of (res.interfaces || [])) {
        if ((iface as any).interfaceType === 'web_search') continue;
        resourceItems.push({
          id: `interface-${iface.id}`,
          label: iface.name,
          category: 'interface',
        });
      }
      for (const ds of (res.datasources || [])) {
        resourceItems.push({
          id: `datasource-${ds.id}`,
          label: ds.name,
          category: 'table',
        });
      }
      for (const pub of (res.applications || [])) {
        resourceItems.push({
          id: applicationPanelTabId(pub.id),
          label: pub.title || pub.name,
          category: 'application',
          publicationId: pub.id,
        });
      }
      for (const file of ((res as any).files || [])) {
        resourceItems.push({
          id: `file-${file.id}`,
          label: file.fileName || file.name || file.id,
          category: 'files',
        });
      }

      setProjectResources(resourceItems);
      setProjectResourcesLoading(false);
    }).catch(() => {
      setProjectResources([]);
      setProjectResourcesLoading(false);
    });
  }, []);

  const handleLogWorkflowSelect = useCallback((item: PickerItem) => {
    const workflowId = parseTabResource(item.id)?.id ?? '';
    if (!workflowId) return;

    setSelectedLogWorkflow(item);
    setView('logRuns');
    setCategorySearch('');
  }, []);

  const handleLogRunSelect = useCallback((run: WorkflowRun) => {
    if (!selectedLogWorkflow) return;
    const workflowId = parseTabResource(selectedLogWorkflow.id)?.id ?? '';
    if (!workflowId) return;

    setOpen(false);
    openWorkflowLogs({
      workflowId,
      runId: run.runId || run.id,
      workflowName: selectedLogWorkflow.label,
    });
  }, [openWorkflowLogs, selectedLogWorkflow]);

  const handleSelect = useCallback((item: PickerItem) => {
    if (!sidePanel) return;

    // Projects drill down - don't close popover
    if (item.category === 'project') {
      handleProjectDrillDown(item);
      return;
    }

    setOpen(false);

    switch (item.category) {
      case 'agenda':
        openAgendaPanel(sidePanel, item.label);
        break;
      case 'application':
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <AppWindow className="w-4 h-4" />,
          content: <ApplicationPanelContent publicationId={item.publicationId!} />,
          preferredWidth: 0.35,
        });
        break;
      case 'interface':
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <Monitor className="w-4 h-4" />,
          content: <InterfacePanelContent interfaceId={parseTabResource(item.id)?.id ?? ''} />,
        });
        break;
      case 'table':
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <Table className="w-4 h-4" />,
          content: <DataSourcePanelContent dataSourceId={parseTabResource(item.id)?.id ?? ''} />,
          preferredWidth: 0.35,
        });
        break;
      case 'workflow': {
        // Lazy: the builder panel pulls in the whole workflow canvas, and the app
        // shell must not carry it statically (same rule as openWorkflowBuilderTab).
        const workflowId = parseTabResource(item.id)?.id ?? '';
        void import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
          sidePanel.openTab({
            id: item.id,
            label: item.label,
            icon: <Workflow className="w-4 h-4" />,
            content: <WorkflowBuilderPanelContent workflowId={workflowId} />,
            preferredWidth: 0.35,
          });
        });
        break;
      }
      case 'agent': {
        const rawAgentId = parseTabResource(item.id)?.id ?? '';
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <AvatarDisplay avatarUrl={item.avatarUrl} name={item.label} size="sm" className="!w-4 !h-4" />,
          content: <AgentPanelContent agentId={rawAgentId} />,
          preferredWidth: 0.35,
        });
        break;
      }
      case 'conversation': {
        const convId = item.id.replace('conversation-', '');
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <MessageSquare className="w-4 h-4" />,
          content: <ConversationPanelContent conversationId={convId} />,
          preferredWidth: 0.35,
        });
        break;
      }
      case 'files': {
        const fileId = item.id.replace('file-', '');
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <FileText className="w-4 h-4" />,
          content: (
            <FileDetailView
              entryId={fileId}
              fileName={item.label}
              onBack={() => sidePanel.removeTab(item.id)}
            />
          ),
          preferredWidth: 0.35,
        });
        break;
      }
    }
  }, [sidePanel, t]);

  const handleCategoryClick = useCallback((category: CategoryType) => {
    // Activity category removed 2026-05-08 - right-side-panel ActivityFeed +
    // ActivityLog backend stack deleted with the bell Activity-tab cleanup.
    // Files category opens the tab directly (global storage explorer)
    if (category === 'agenda' && sidePanel) {
      setOpen(false);
      openAgendaPanel(sidePanel, tSidebarNav('agenda'));
      return;
    }
    if (category === 'files' && sidePanel) {
      setOpen(false);
      sidePanel.openTab({
        id: 'files-panel',
        label: t('files'),
        icon: <FolderOpen className="w-4 h-4" />,
        content: <StorageExplorerTab />,
        preferredWidth: 0.35,
      });
      return;
    }
    if (category === 'logs') {
      setSelectedCategory(category);
      setView('logWorkflows');
      setCategorySearch('');
      setVisibleCount(ITEMS_PER_PAGE);
      return;
    }
    setSelectedCategory(category);
    setView('items');
    setCategorySearch('');
    setServerSearchItems([]);
    setVisibleCount(ITEMS_PER_PAGE);
  }, [sidePanel, t, tSidebarNav]);

  const handleBack = useCallback(() => {
    if (view === 'logRuns') {
      setView('logWorkflows');
      setSelectedLogWorkflow(null);
      setCategorySearch('');
      return;
    }
    if (view === 'logWorkflows') {
      setView('categories');
      setSelectedCategory(null);
      setCategorySearch('');
      return;
    }
    if (view === 'projectItems') {
      // Back to project resource categories
      setView('projectCategories');
      setSelectedProjectCategory(null);
      setProjectCategorySearch('');
      return;
    }
    if (view === 'projectCategories') {
      // Back to project list
      setView('items');
      setSelectedProject(null);
      setProjectResources([]);
      return;
    }
    setView('categories');
    setSelectedCategory(null);
    setCategorySearch('');
    setServerSearchItems([]);
    setVisibleCount(ITEMS_PER_PAGE);
  }, [view]);


  if (!sidePanel) return null;

  const categoryIcon: Record<CategoryType, React.ReactNode> = {
    agenda: <CalendarClock className="h-3.5 w-3.5 text-theme-secondary" />,
    application: <AppWindow className="h-3.5 w-3.5 text-theme-secondary" />,
    interface: <Monitor className="h-3.5 w-3.5 text-theme-secondary" />,
    table: <Table className="h-3.5 w-3.5 text-theme-secondary" />,
    workflow: <Workflow className="h-3.5 w-3.5 text-theme-secondary" />,
    logs: <FileText className="h-3.5 w-3.5 text-theme-secondary" />,
    agent: <Bot className="h-3.5 w-3.5 text-theme-secondary" />,
    conversation: <MessageSquare className="h-3.5 w-3.5 text-theme-secondary" />,
    project: <Briefcase className="h-3.5 w-3.5 text-theme-secondary" />,
    files: <FolderOpen className="h-3.5 w-3.5 text-theme-secondary" />,
  };

  // Per-item icon: use agent avatar for agents, project icon/color for projects, fallback to category icon
  const getItemIcon = (item: PickerItem): React.ReactNode => {
    if (item.category === 'agent') {
      return <AvatarDisplay avatarUrl={item.avatarUrl} name={item.label} size="sm" className="!w-3.5 !h-3.5" />;
    }
    if (item.category === 'project') {
      const IconComp = getProjectIcon(item.iconKey);
      return <IconComp className="h-3.5 w-3.5 shrink-0" style={{ color: item.color || undefined }} />;
    }
    return categoryIcon[item.category];
  };

  const categoryLabel: Record<CategoryType, string> = {
    agenda: tSidebarNav('agenda'),
    application: t('applications'),
    interface: t('interfaces'),
    table: t('tables'),
    workflow: t('workflows'),
    logs: t('logs'),
    agent: t('agents'),
    conversation: t('conversations'),
    project: t('projects'),
    files: t('files'),
  };

  const renderSearchResults = () => {
    const groups = [
      { label: tSidebarNav('agenda'), items: searchAgenda },
      { label: t('applications'), items: searchApplications },
      { label: t('interfaces'), items: searchInterfaces },
      { label: t('tables'), items: searchTables },
      { label: t('workflows'), items: searchWorkflows },
      { label: t('agents'), items: searchAgents },
      { label: t('conversations'), items: searchConversations },
      { label: t('projects'), items: searchProjects },
      { label: t('files'), items: searchFiles },
    ];

    const hasResults = groups.some(g => g.items.length > 0);
    if (!hasResults) {
      return (
        <div className="p-3 text-sm text-theme-secondary text-center">{t('noResults')}</div>
      );
    }

    return (
      <div className="space-y-1">
        {groups.map(({ label, items: groupItems }) => {
          if (groupItems.length === 0) return null;
          return (
            <div key={label}>
              <div className="px-2 py-1.5 text-xs font-medium text-theme-secondary uppercase tracking-wider">
                {label}
              </div>
              {groupItems.map(item => (
                <div
                  key={item.id}
                  onClick={() => handleSelect(item)}
                  className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
                >
                  {getItemIcon(item)}
                  <span className="text-sm text-theme-secondary group-hover:text-theme-primary transition-colors truncate">{item.label}</span>
                </div>
              ))}
            </div>
          );
        })}
      </div>
    );
  };

  const renderCategoryButtons = () => (
    <div className="space-y-1">
      {CATEGORY_ORDER.map(cat => {
        const count = categoryCounts[cat];
        return (
          <div
            key={cat}
            onClick={() => handleCategoryClick(cat)}
            className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {categoryIcon[cat]}
            <span className="flex-1 text-sm text-left text-theme-secondary group-hover:text-theme-primary transition-colors">{categoryLabel[cat]}</span>
            {cat !== 'agenda' && (
              <>
                <span className="text-xs text-theme-tertiary tabular-nums text-right">{countFormatter.format(count)}</span>
                <ChevronRight className="h-3.5 w-3.5 text-theme-tertiary group-hover:text-theme-primary transition-colors" />
              </>
            )}
          </div>
        );
      })}
    </div>
  );

  const renderItemsView = () => {
    const visibleItems = categoryItems.slice(0, visibleCount);
    const totalForView = categorySearch.trim() || !selectedCategory
      ? categoryItems.length
      : categoryCounts[selectedCategory];
    const displayedCount = Math.min(visibleCount, categoryItems.length);
    const remaining = Math.max(0, totalForView - displayedCount);

    return (
      <div className="space-y-1">
        {categoryItems.length === 0 ? (
          <div className="p-3 text-sm text-theme-secondary text-center">
            {categorySearch.trim() ? t('noResults') : t('noItems')}
          </div>
        ) : (
          <>
            {visibleItems.map(item => (
              <div
                key={item.id}
                onClick={() => handleSelect(item)}
                className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
              >
                {getItemIcon(item)}
                <span className="flex-1 text-sm text-left text-theme-secondary group-hover:text-theme-primary transition-colors truncate">{item.label}</span>
                {item.category === 'project' && (
                  <ChevronRight className="h-3.5 w-3.5 text-theme-tertiary group-hover:text-theme-primary transition-colors" />
                )}
              </div>
            ))}
            {remaining > 0 && (
              <button
                type="button"
                onClick={() => void handleShowMore()}
                disabled={loadingMoreCategory}
                className="w-full px-2 py-1.5 text-sm text-theme-secondary hover:text-theme-primary text-center transition-colors"
              >
                {t('showMore', { count: remaining })}
              </button>
            )}
          </>
        )}
      </div>
    );
  };

  const renderLogWorkflowsView = () => {
    const visibleItems = logWorkflowItems.slice(0, visibleCount);
    const totalForView = categorySearch.trim() ? logWorkflowItems.length : categoryCounts.workflow;
    const displayedCount = Math.min(visibleCount, logWorkflowItems.length);
    const remaining = Math.max(0, totalForView - displayedCount);

    if (logWorkflowItems.length === 0) {
      return (
        <div className="p-3 text-center text-sm text-theme-secondary">
          {categorySearch.trim() ? t('noResults') : t('noItems')}
        </div>
      );
    }

    return (
      <div className="space-y-1">
        {visibleItems.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => handleLogWorkflowSelect(item)}
            className="group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            <Workflow className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />
            <span className="min-w-0 flex-1 truncate text-sm text-theme-secondary transition-colors group-hover:text-theme-primary">
              {item.label}
            </span>
            <ChevronRight className="h-3.5 w-3.5 flex-shrink-0 text-theme-tertiary transition-colors group-hover:text-theme-primary" />
          </button>
        ))}
        {remaining > 0 && (
          <button
            type="button"
            onClick={() => void handleShowMoreLogWorkflows()}
            disabled={loadingMoreCategory}
            className="w-full px-2 py-1.5 text-center text-sm text-theme-secondary transition-colors hover:text-theme-primary"
          >
            {t('showMore', { count: remaining })}
          </button>
        )}
      </div>
    );
  };

  const renderLogRunsView = () => {
    if (!selectedLogWorkflow) return null;
    const workflowId = parseTabResource(selectedLogWorkflow.id)?.id ?? '';
    if (!workflowId) return null;

    return (
      <div className="h-80">
        <RunHistoryList workflowId={workflowId} onSelectRun={handleLogRunSelect} />
      </div>
    );
  };

  // Resource types to show inside a project
  const PROJECT_RESOURCE_TYPES: { key: CategoryType; icon: React.ElementType; labelKey: string }[] = [
    { key: 'agent', icon: Bot, labelKey: 'agents' },
    { key: 'application', icon: AppWindow, labelKey: 'applications' },
    { key: 'workflow', icon: Workflow, labelKey: 'workflows' },
    { key: 'interface', icon: Monitor, labelKey: 'interfaces' },
    { key: 'table', icon: Table, labelKey: 'tables' },
    { key: 'files', icon: FileText, labelKey: 'files' },
  ];

  const projectResourcesByCategory = useMemo(() => {
    const map: Record<string, PickerItem[]> = {};
    for (const rt of PROJECT_RESOURCE_TYPES) {
      map[rt.key] = projectResources.filter(r => r.category === rt.key);
    }
    return map;
  }, [projectResources]);

  const filteredProjectCategoryItems = useMemo(() => {
    if (!selectedProjectCategory) return [];
    const items = projectResourcesByCategory[selectedProjectCategory] || [];
    if (!projectCategorySearch.trim()) return items;
    const q = projectCategorySearch.toLowerCase();
    return items.filter(item => item.label.toLowerCase().includes(q));
  }, [selectedProjectCategory, projectResourcesByCategory, projectCategorySearch]);

  const renderProjectCategoriesView = () => {
    if (projectResourcesLoading) {
      return (
        <div className="space-y-1 p-1">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-10 bg-gray-100 dark:bg-gray-800/50 rounded-xl animate-pulse" />
          ))}
        </div>
      );
    }

    return (
      <div className="space-y-1">
        {PROJECT_RESOURCE_TYPES.map(rt => {
          const count = (projectResourcesByCategory[rt.key] || []).length;
          const Icon = rt.icon;
          return (
            <div
              key={rt.key}
              onClick={() => {
                setSelectedProjectCategory(rt.key);
                setView('projectItems');
                setProjectCategorySearch('');
              }}
              className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Icon className="h-3.5 w-3.5 text-theme-secondary" />
              <span className="flex-1 text-sm text-left text-theme-secondary group-hover:text-theme-primary transition-colors">{t(rt.labelKey)}</span>
              <span className="text-xs text-theme-tertiary tabular-nums text-right">{countFormatter.format(count)}</span>
              <ChevronRight className="h-3.5 w-3.5 text-theme-tertiary group-hover:text-theme-primary transition-colors" />
            </div>
          );
        })}
      </div>
    );
  };

  const renderProjectItemsView = () => {
    if (filteredProjectCategoryItems.length === 0) {
      return (
        <div className="p-3 text-sm text-theme-secondary text-center">
          {projectCategorySearch.trim() ? t('noResults') : t('noItems')}
        </div>
      );
    }

    return (
      <div className="space-y-1">
        {filteredProjectCategoryItems.map(item => (
          <div
            key={item.id}
            onClick={() => handleSelect(item)}
            className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {getItemIcon(item)}
            <span className="text-sm text-theme-secondary group-hover:text-theme-primary transition-colors truncate">{item.label}</span>
          </div>
        ))}
      </div>
    );
  };

  const renderContent = () => {
    if (isLoading) {
      return (
        <div className="space-y-1 p-1">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="h-10 bg-gray-100 dark:bg-gray-800/50 rounded-xl animate-pulse" />
          ))}
        </div>
      );
    }

    if (error) {
      return (
        <div className="p-3 text-sm text-destructive text-center">{error}</div>
      );
    }

    if (view === 'categories') {
      if (search.trim()) {
        return renderSearchResults();
      }
      return renderCategoryButtons();
    }

    if (view === 'logWorkflows') {
      return renderLogWorkflowsView();
    }

    if (view === 'logRuns') {
      return renderLogRunsView();
    }

    if (items.length === 0) {
      return (
        <div className="p-3 text-sm text-theme-secondary text-center">{t('noItems')}</div>
      );
    }

    if (view === 'projectCategories') {
      return renderProjectCategoriesView();
    }

    if (view === 'projectItems') {
      return renderProjectItemsView();
    }

    return renderItemsView();
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {variant === 'header' ? (
          <button
            type="button"
            title={t('addTab')}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-theme-tertiary transition-colors"
          >
            <Plus className="w-4 h-4" />
          </button>
        ) : (
          <Button
            variant="ghost"
            size="icon"
            title={t('addTab')}
            className="w-7 h-7 self-center"
          >
            <Plus className="h-3.5 w-3.5" />
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent
        align={variant === 'header' ? 'end' : 'start'}
        sideOffset={4}
        className="w-64 p-0 bg-theme-primary border border-theme rounded-2xl shadow-lg"
      >
        {/* Header: search (categories) or back + filter (drill-down views) */}
        {view === 'categories' ? (
          <div className="p-2 border-b border-theme">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-theme-secondary" />
              <Input
                value={search}
                onChange={e => {
                  searchRequestIdRef.current += 1;
                  setSearch(e.target.value);
                  setServerSearchItems([]);
                }}
                placeholder={t('searchPlaceholder')}
                className="h-8 pl-7 text-sm bg-transparent border-theme"
                autoFocus
              />
            </div>
          </div>
        ) : view === 'logRuns' && selectedLogWorkflow ? (
          <div className="border-b border-theme">
            <button
              type="button"
              onClick={handleBack}
              className="flex w-full items-center gap-2 px-3 py-2 transition-colors hover:bg-theme-secondary/30"
            >
              <ChevronLeft className="h-3.5 w-3.5 text-theme-secondary" />
              <Workflow className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />
              <span className="truncate text-sm font-medium text-theme-primary">
                {selectedLogWorkflow.label}
              </span>
            </button>
          </div>
        ) : view === 'projectCategories' && selectedProject ? (
          <div className="border-b border-theme">
            <button
              type="button"
              onClick={handleBack}
              className="flex items-center gap-2 px-3 py-2 w-full hover:bg-theme-secondary/30 transition-colors"
            >
              <ChevronLeft className="h-3.5 w-3.5 text-theme-secondary" />
              {(() => {
                const IconComp = getProjectIcon(selectedProject.iconKey);
                return <IconComp className="h-3.5 w-3.5 shrink-0" style={{ color: selectedProject.color || undefined }} />;
              })()}
              <span className="text-sm font-medium text-theme-primary truncate">
                {selectedProject.label}
              </span>
            </button>
          </div>
        ) : view === 'projectItems' && selectedProject && selectedProjectCategory ? (
          <div className="border-b border-theme">
            <button
              type="button"
              onClick={handleBack}
              className="flex items-center gap-2 px-3 py-2 w-full hover:bg-theme-secondary/30 transition-colors"
            >
              <ChevronLeft className="h-3.5 w-3.5 text-theme-secondary" />
              {categoryIcon[selectedProjectCategory]}
              <span className="text-sm font-medium text-theme-primary">
                {categoryLabel[selectedProjectCategory]}
              </span>
            </button>
            <div className="px-2 pb-2">
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-theme-secondary" />
                <Input
                  value={projectCategorySearch}
                  onChange={e => setProjectCategorySearch(e.target.value)}
                  placeholder={t('filterPlaceholder')}
                  className="h-8 pl-7 text-sm bg-transparent border-theme"
                  autoFocus
                />
              </div>
            </div>
          </div>
        ) : (
          <div className="border-b border-theme">
            <button
              type="button"
              onClick={handleBack}
              className="flex items-center gap-2 px-3 py-2 w-full hover:bg-theme-secondary/30 transition-colors"
            >
              <ChevronLeft className="h-3.5 w-3.5 text-theme-secondary" />
              {selectedCategory && categoryIcon[selectedCategory]}
              <span className="text-sm font-medium text-theme-primary">
                {selectedCategory && categoryLabel[selectedCategory]}
              </span>
            </button>
            <div className="px-2 pb-2">
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-theme-secondary" />
                <Input
                  value={categorySearch}
                  onChange={e => {
                    searchRequestIdRef.current += 1;
                    setCategorySearch(e.target.value);
                    setServerSearchItems([]);
                  }}
                  placeholder={t('filterPlaceholder')}
                  className="h-8 pl-7 text-sm bg-transparent border-theme"
                  autoFocus
                />
              </div>
            </div>
          </div>
        )}

        {/* Content */}
        <div className="max-h-64 overflow-y-auto p-1">
          {renderContent()}
        </div>
      </PopoverContent>
    </Popover>
  );
}
