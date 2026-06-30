'use client';

import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Plus, Search, AppWindow, Monitor, Table, Workflow, ChevronLeft, ChevronRight, Bot, FolderOpen, MessageSquare, Briefcase, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Input } from '@/components/ui/input';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { interfaceService } from '@/lib/api/orchestrator/interface.service';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';
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

interface PickerItem {
  id: string;
  label: string;
  category: 'application' | 'interface' | 'table' | 'workflow' | 'agent' | 'conversation' | 'project' | 'files';
  publicationId?: string;
  avatarUrl?: string;
  iconKey?: string;
  color?: string;
}

type CategoryType = PickerItem['category'];

interface AddTabPickerProps {
  variant?: 'tab-bar' | 'header';
}

const ITEMS_PER_PAGE = 10;
const CACHE_TTL_MS = 30_000; // Reuse fetched data for 30 seconds

const CATEGORY_ORDER: CategoryType[] = ['application', 'interface', 'table', 'workflow', 'agent', 'conversation', 'project', 'files'];

export function AddTabPicker({ variant = 'tab-bar' }: AddTabPickerProps) {
  const sidePanel = useSidePanelSafe();
  const t = useTranslations('sidePanel');

  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [items, setItems] = useState<PickerItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Navigation: categories → items | projectCategories → projectItems
  const [view, setView] = useState<'categories' | 'items' | 'projectCategories' | 'projectItems'>('categories');
  const [selectedCategory, setSelectedCategory] = useState<CategoryType | null>(null);
  const [categorySearch, setCategorySearch] = useState('');
  const [visibleCount, setVisibleCount] = useState(ITEMS_PER_PAGE);

  // Project drill-down state
  const [selectedProject, setSelectedProject] = useState<PickerItem | null>(null);
  const [projectResources, setProjectResources] = useState<PickerItem[]>([]);
  const [projectResourcesLoading, setProjectResourcesLoading] = useState(false);
  const [selectedProjectCategory, setSelectedProjectCategory] = useState<CategoryType | null>(null);
  const [projectCategorySearch, setProjectCategorySearch] = useState('');

  // Cache: avoid re-fetching all 8 APIs on every popover open.
  // Trade-off: resources created in the last 30s won't appear until TTL expires.
  const cacheRef = useRef<{ items: PickerItem[]; ts: number } | null>(null);
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
      setSearch('');
      setCategorySearch('');
      setVisibleCount(ITEMS_PER_PAGE);

      // Single TTL check - useEffect #2 reads skipFetchRef to stay in sync
      const cache = cacheRef.current;
      const isCacheFresh = !!(cache && Date.now() - cache.ts < CACHE_TTL_MS);
      skipFetchRef.current = isCacheFresh;

      if (isCacheFresh) {
        setItems(cache!.items);
        setIsLoading(false);
        setError(null);
      } else {
        setItems([]);
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
      interfaceService.getInterfacesPage({ size: 100, includeTemplates: false }).then(result => result.items).catch(() => []),
      orchestratorApi.getDataSources().catch(() => []),
      orchestratorApi.getWorkflows({ size: 100 }).catch(() => []),
      orchestratorApi.getAgents().catch(() => []),
      conversationApi.getConversations(0, 100).catch(() => ({ content: [] })),
      projectService.getProjects().catch(() => []),
    ]).then(([myPubs, acquired, interfaces, dataSources, workflows, agents, conversationsData, projects]) => {
      if (cancelled) return;

      const pickerItems: PickerItem[] = [];

      // Merge my publications + acquired, deduplicate by publicationId
      const seenPubIds = new Set<string>();
      for (const pub of myPubs.publications) {
        if (!seenPubIds.has(pub.id)) {
          seenPubIds.add(pub.id);
          pickerItems.push({
            id: `application-${pub.id}`,
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
            id: `application-${app.sourcePublicationId}`,
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
            id: `workflow-${wf.id}`,
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

      // Conversations: handle paginated response or plain array
      const conversations: any[] = Array.isArray(conversationsData)
        ? conversationsData
        : (conversationsData as any)?.content || [];
      for (const conv of conversations) {
        pickerItems.push({
          id: `conversation-${conv.id}`,
          label: conv.title || 'Untitled',
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

      cacheRef.current = { items: pickerItems, ts: Date.now() };
      setItems(pickerItems);
      setIsLoading(false);
    }).catch(() => {
      if (!cancelled) {
        setError(t('errorLoading'));
        setIsLoading(false);
      }
    });

    return () => { cancelled = true; };
  }, [open, sidePanel, t]);

  // Items grouped by category (unfiltered, for counts)
  const itemsByCategory = useMemo(() => {
    const map: Record<CategoryType, PickerItem[]> = {
      application: [],
      interface: [],
      table: [],
      workflow: [],
      agent: [],
      conversation: [],
      project: [],
      files: [],
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
    return items.filter(item => item.label.toLowerCase().includes(q));
  }, [items, search]);

  const searchApplications = useMemo(() => searchFiltered.filter(i => i.category === 'application'), [searchFiltered]);
  const searchInterfaces = useMemo(() => searchFiltered.filter(i => i.category === 'interface'), [searchFiltered]);
  const searchTables = useMemo(() => searchFiltered.filter(i => i.category === 'table'), [searchFiltered]);
  const searchWorkflows = useMemo(() => searchFiltered.filter(i => i.category === 'workflow'), [searchFiltered]);
  const searchAgents = useMemo(() => searchFiltered.filter(i => i.category === 'agent'), [searchFiltered]);
  const searchConversations = useMemo(() => searchFiltered.filter(i => i.category === 'conversation'), [searchFiltered]);
  const searchProjects = useMemo(() => searchFiltered.filter(i => i.category === 'project'), [searchFiltered]);
  const searchFiles = useMemo(() => searchFiltered.filter(i => i.category === 'files'), [searchFiltered]);

  // Items for the selected category (filtered by category search)
  const categoryItems = useMemo(() => {
    if (!selectedCategory) return [];
    const catItems = itemsByCategory[selectedCategory];
    if (!categorySearch.trim()) return catItems;
    const q = categorySearch.toLowerCase();
    return catItems.filter(item => item.label.toLowerCase().includes(q));
  }, [selectedCategory, itemsByCategory, categorySearch]);

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
          id: `workflow-${wf.id}`,
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
          id: `application-${pub.id}`,
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

  const handleSelect = useCallback((item: PickerItem) => {
    if (!sidePanel) return;

    // Projects drill down - don't close popover
    if (item.category === 'project') {
      handleProjectDrillDown(item);
      return;
    }

    setOpen(false);

    switch (item.category) {
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
          content: <InterfacePanelContent interfaceId={item.id.replace('interface-', '')} />,
        });
        break;
      case 'table':
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <Table className="w-4 h-4" />,
          content: <DataSourcePanelContent dataSourceId={item.id.replace('datasource-', '')} />,
          preferredWidth: 0.35,
        });
        break;
      case 'workflow':
        sidePanel.openTab({
          id: item.id,
          label: item.label,
          icon: <Workflow className="w-4 h-4" />,
          content: <WorkflowBuilderPanelContent workflowId={item.id.replace('workflow-', '')} />,
          preferredWidth: 0.35,
        });
        break;
      case 'agent': {
        const rawAgentId = item.id.replace('agent-', '');
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
    setSelectedCategory(category);
    setView('items');
    setCategorySearch('');
    setVisibleCount(ITEMS_PER_PAGE);
  }, [sidePanel, t]);

  const handleBack = useCallback(() => {
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
    setVisibleCount(ITEMS_PER_PAGE);
  }, [view]);


  if (!sidePanel) return null;

  const categoryIcon: Record<CategoryType, React.ReactNode> = {
    application: <AppWindow className="h-3.5 w-3.5 text-theme-secondary" />,
    interface: <Monitor className="h-3.5 w-3.5 text-theme-secondary" />,
    table: <Table className="h-3.5 w-3.5 text-theme-secondary" />,
    workflow: <Workflow className="h-3.5 w-3.5 text-theme-secondary" />,
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
    application: t('applications'),
    interface: t('interfaces'),
    table: t('tables'),
    workflow: t('workflows'),
    agent: t('agents'),
    conversation: t('conversations'),
    project: t('projects'),
    files: t('files'),
  };

  const renderSearchResults = () => {
    const groups = [
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

  // Categories that open a tab directly (not item-based lists)
  const directOpenCategories = new Set<CategoryType>(['files']);

  const renderCategoryButtons = () => (
    <div className="space-y-1">
      {CATEGORY_ORDER.map(cat => {
        const count = itemsByCategory[cat].length;
        const isDirect = directOpenCategories.has(cat);
        return (
          <div
            key={cat}
            onClick={() => handleCategoryClick(cat)}
            className="group flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {categoryIcon[cat]}
            <span className="flex-1 text-sm text-left text-theme-secondary group-hover:text-theme-primary transition-colors">{categoryLabel[cat]}</span>
            {!isDirect && <span className="text-xs text-theme-tertiary tabular-nums">{count}</span>}
            <ChevronRight className="h-3.5 w-3.5 text-theme-tertiary group-hover:text-theme-primary transition-colors" />
          </div>
        );
      })}
    </div>
  );

  const renderItemsView = () => {
    const visibleItems = categoryItems.slice(0, visibleCount);
    const remaining = categoryItems.length - visibleCount;

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
                onClick={() => setVisibleCount(prev => prev + ITEMS_PER_PAGE)}
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
              <span className="text-xs text-theme-tertiary tabular-nums">{count}</span>
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

    if (items.length === 0) {
      return (
        <div className="p-3 text-sm text-theme-secondary text-center">{t('noItems')}</div>
      );
    }

    if (view === 'categories') {
      if (search.trim()) {
        return renderSearchResults();
      }
      return renderCategoryButtons();
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
                onChange={e => setSearch(e.target.value)}
                placeholder={t('searchPlaceholder')}
                className="h-8 pl-7 text-sm bg-transparent border-theme"
                autoFocus
              />
            </div>
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
                  onChange={e => setCategorySearch(e.target.value)}
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
