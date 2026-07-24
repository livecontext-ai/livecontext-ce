'use client';

import { useMemo, useState, useEffect, useCallback } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { Home, Table as TableIcon, Workflow, LayoutPanelTop, Zap, Bot } from 'lucide-react';
import { useCurrentView } from '@/hooks/useCurrentView';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { resolveApplicationPublication } from '@/app/[locale]/app/applications/[publicationId]/resolvePublication';
import { projectService } from '@/lib/api/orchestrator/project.service';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { useBreadcrumbFavorite } from '@/hooks/useBreadcrumbFavorite';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { recallWorkflowName, forgetWorkflowName } from '@/lib/workflows/recentWorkflowNames';
import {
  type FilesDetailState,
  onFilesDetailState,
  emitFilesDetailCommand,
  emitFilesFolderNavigate,
  FILES_DETAIL_BACK,
} from '@/lib/files/filesHeaderBus';

// Shared locale parsing (covers ALL routing locales - the old local copy
// hardcoded en|fr|es and broke breadcrumbs for de/pt/zh users).
import { parseLocalePath } from '@/lib/utils/locale';

// Helper to build path with locale prefix. Kept local (not the shared
// buildLocalePath): this one preserves the CURRENT prefix verbatim - when the
// user is on /en/..., navigation stays on /en/... instead of dropping it.
const buildLocalePath = (locale: string, path: string): string => {
  if (!locale) return path;
  return `/${locale}${path}`;
};

export interface BreadcrumbItem {
  label: string;
  onClick?: () => void;
  icon?: React.ComponentType<{ className?: string }>;
  isLoading?: boolean;
  /** If true, the item gets truncated when it doesn't fit (intelligent truncation for last item, fallback to maxLength for others) */
  truncate?: boolean;
  /** If true, shows edit icon on hover and allows inline editing */
  editable?: boolean;
  /** Callback when editing is complete */
  onEditComplete?: (newValue: string) => void;
  /** When set, renders a favorite-toggle star on this segment (see breadcrumb.tsx). */
  favorite?: { isFavorite: boolean; onToggle: () => void };
}

interface UseBreadcrumbsOptions {
  // Legacy option - no longer used, kept for backwards compatibility
  orchestratorUrl?: string;
}

interface UseBreadcrumbsReturn {
  breadcrumbItems: BreadcrumbItem[];
  shouldShowBreadcrumb: boolean;
  isDataViewWithDataSource: boolean;
  isWorkflowViewWithWorkflow: boolean;
  isInterfacePage: boolean;
  interfaceId: string | null;
  interfaceType: string | null;
  isRunMode: boolean;
  isApplicationPage: boolean;
  isMarketplacePreview: boolean;
  previewPublicationId: string | null;
  isAgentMarketplacePreview: boolean;
  agentPreviewPublicationId: string | null;
  isAgentFleet: boolean;
  isFilesView: boolean;
  filesDetail: FilesDetailState | null;
}

const SETTINGS_LABELS: Record<string, string> = {
  'overview': 'Overview',
  'subscription': 'Subscription',
  'api': 'MCPs',
  'apis': 'MCPs',
  'mcp': 'MCPs',

  'credentials': 'Credentials',
  'usage': 'Usage',
  'pricing': 'Pricing',
  'storage': 'Storage',
  'developers': 'Developers',
  'preferences': 'Preferences',
};

const MARKETPLACE_LABELS: Record<string, string> = {
  'workflows': 'Workflows',
  'interfaces': 'Interfaces',
  'data': 'Tables',
  'agents': 'Agents',
};

import { unifiedApiService } from '@/lib/api/unified-api-service';

export function useBreadcrumbs(_options: UseBreadcrumbsOptions = {}): UseBreadcrumbsReturn {
  const safeNavigate = useSafeNavigate();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { locale, normalized: normalizedPathname } = parseLocalePath(pathname);
  const { isAuthenticated, isLoading: authLoading } = useAuthGuard();

  // Helper to navigate with locale preserved (uses safe navigate for unsaved changes guard)
  const navigateTo = useCallback((path: string) => safeNavigate(buildLocalePath(locale, path)), [safeNavigate, locale]);

  // Use native Next.js routing via useCurrentView
  const { view: currentView, workflowId, dataSourceId, interfaceId: interfaceIdFromUrl, publicationId } = useCurrentView();

  // State for fetched names (null = loading, string = loaded)
  const [dataSourceName, setDataSourceName] = useState<string | null>(null);
  const [dataSourceDescription, setDataSourceDescription] = useState<string>('');
  const [workflowName, setWorkflowName] = useState<string | null>(null);
  const [workflowDescription, setWorkflowDescription] = useState<string>('');
  const [interfaceName, setInterfaceName] = useState<string | null>(null);
  const [interfaceDescription, setInterfaceDescription] = useState<string>('');
  const [interfaceType, setInterfaceType] = useState<string | null>(null);
  const [mcpInfo, setMcpInfo] = useState<{ title: string | null; toolName: string | null }>({ title: null, toolName: null });
  const [projectName, setProjectName] = useState<string | null>(null);

  // Determine view states from currentView
  const isWorkflowView = currentView === 'workflow';
  const isDataView = currentView === 'data';
  const isMarketplaceView = currentView === 'marketplace';
  const isAgentView = currentView === 'agent';
  const isInterfaceView = currentView === 'interface';
  const isSettingsView = currentView === 'settings';
  const isFilesView = currentView === 'files';

  // Focused-viewer state for the full-page Files browser. The detail view is
  // local state inside FileBrowser (not a route), so it can't be derived from
  // the URL - FileBrowser broadcasts it over the shared bus and we mirror it
  // into the breadcrumb tail. Reset whenever we leave the Files view.
  const [filesDetail, setFilesDetail] = useState<FilesDetailState | null>(null);
  useEffect(() => {
    if (!isFilesView) {
      setFilesDetail(null);
      return;
    }
    return onFilesDetailState(setFilesDetail);
  }, [isFilesView]);

  const isDataViewWithDataSource = isDataView && !!dataSourceId && dataSourceId !== 'new';
  const isWorkflowViewWithWorkflow = isWorkflowView && !!workflowId && workflowId !== 'new';

  const interfaceIdMatch = normalizedPathname?.match(/\/interface\/([^\/]+)/);
  const isInterfacePage = !!interfaceIdMatch;
  const interfaceId = interfaceIdMatch ? interfaceIdMatch[1] : null;

  const isRunMode = normalizedPathname?.includes('/run/') || false;

  // Application detail page detection: /app/applications/{publicationId}
  const isApplicationView = currentView === 'applications';
  const isApplicationPage = isApplicationView && !!normalizedPathname?.match(/\/app\/applications\/[^\/]+$/);

  // Marketplace preview page detection: /app/marketplace/{publicationId}/preview
  const marketplacePreviewMatch = normalizedPathname?.match(/\/app\/marketplace\/([^\/]+)\/preview/);
  const isMarketplacePreview = !!marketplacePreviewMatch;
  const previewPublicationId = marketplacePreviewMatch ? marketplacePreviewMatch[1] : null;

  // Agent marketplace preview detection: /app/marketplace/agents/{publicationId}
  const agentPreviewMatch = normalizedPathname?.match(/\/app\/marketplace\/agents\/([^\/]+)$/);
  const isAgentMarketplacePreview = !!agentPreviewMatch;
  const agentPreviewPublicationId = agentPreviewMatch ? agentPreviewMatch[1] : null;

  const isProjectView = currentView === 'project';
  const projectIdMatch = normalizedPathname?.match(/\/app\/project\/([^\/]+)/);
  const projectId = projectIdMatch ? projectIdMatch[1] : null;

  // Public profile page detection: /app/u/{handle}. The handle is taken from the
  // URL itself (the route segment IS the handle), so no fetch is needed. Decode is
  // guarded: a malformed percent sequence falls back to the raw segment instead of
  // throwing during render.
  const profileMatch = normalizedPathname?.match(/\/app\/u\/([^\/]+)$/);
  const isProfileView = !!profileMatch;
  let profileHandle: string | null = null;
  if (profileMatch) {
    try {
      profileHandle = decodeURIComponent(profileMatch[1]);
    } catch {
      profileHandle = profileMatch[1];
    }
  }

  const shouldShowBreadcrumb = isWorkflowView || isDataView || isMarketplaceView || isAgentView || isInterfaceView || isSettingsView || isApplicationView || isProjectView || isFilesView || isProfileView;

  // Extract settings path from pathname
  const settingsPath = useMemo(() => {
    if (!isSettingsView || !normalizedPathname) return null;
    // Convert /app/settings/section to section
    const match = normalizedPathname.match(/\/app\/settings\/?(.*)/);
    return match ? match[1] || 'overview' : 'overview';
  }, [isSettingsView, normalizedPathname]);

  // Extract MCP IDs
  const mcpMatch = useMemo(() => {
    if (!isSettingsView || !settingsPath?.startsWith('mcp/')) return null;
    const parts = settingsPath.split('/');
    return {
      slug: parts.length > 1 ? parts[1] : null,
      toolId: parts.length > 2 ? parts[2] : null
    };
  }, [isSettingsView, settingsPath]);

  // Fetch MCP Info
  useEffect(() => {
    if (!mcpMatch?.slug) {
      setMcpInfo(prev => ({ ...prev, title: null }));
      return;
    }

    let isMounted = true;
    unifiedApiService.getApiById(mcpMatch.slug)
      .then((response: any) => {
        const api = response.data || response;
        if (isMounted) {
          setMcpInfo(prev => ({ ...prev, title: api.title || api.name || api.apiName || api.summary || mcpMatch.slug }));
        }
      })
      .catch(() => {
        if (isMounted) setMcpInfo(prev => ({ ...prev, title: mcpMatch.slug }));
      });

    return () => { isMounted = false; };
  }, [mcpMatch?.slug]);

  // Fetch Tool Info
  useEffect(() => {
    if (!mcpMatch?.slug || !mcpMatch?.toolId) {
      setMcpInfo(prev => ({ ...prev, toolName: null }));
      return;
    }

    let isMounted = true;
    unifiedApiService.getToolById(mcpMatch.toolId)
      .then((tool: any) => {
        if (isMounted) {
          setMcpInfo(prev => ({ ...prev, toolName: tool?.name || tool?.toolName || mcpMatch.toolId }));
        }
      })
      .catch(() => {
        if (isMounted) setMcpInfo(prev => ({ ...prev, toolName: mcpMatch.toolId }));
      });

    return () => { isMounted = false; };
  }, [mcpMatch?.slug, mcpMatch?.toolId]);


  // Fetch data source name
  useEffect(() => {
    if (!dataSourceId || dataSourceId === 'new') {
      setDataSourceName('');
      return;
    }
    if (authLoading || !isAuthenticated) {
      setDataSourceName(null);
      return;
    }

    const dsId = Number(dataSourceId);
    if (isNaN(dsId)) {
      setDataSourceName('');
      return;
    }

    setDataSourceName(null);
    orchestratorApi.getDataSources()
      .then(dataSources => {
        const current = dataSources.find((ds: { id: string; name?: string; description?: string }) => String(ds.id) === String(dsId));
        setDataSourceName(current?.name || 'Table');
        setDataSourceDescription(current?.description || '');
      })
      .catch(() => setDataSourceName('Table'));
  }, [dataSourceId, authLoading, isAuthenticated]);

  // Fetch workflow name
  useEffect(() => {
    if (!workflowId || workflowId === 'new') {
      setWorkflowName('');
      return;
    }
    if (authLoading || !isAuthenticated) {
      setWorkflowName(null);
      return;
    }

    // Seed from the just-created cache (if any) so the title is correct
    // immediately after the create-and-redirect flow. Falls back to the loading
    // skeleton (null) for normal navigation where nothing was primed.
    const primedName = recallWorkflowName(workflowId);
    setWorkflowName(primedName ?? null);
    orchestratorApi.getWorkflow(workflowId)
      .then((workflow: { name?: string; description?: string }) => {
        setWorkflowName(workflow.name || primedName || `Workflow ${workflowId}`);
        setWorkflowDescription(workflow.description || '');
        // The server is reachable and has an authoritative name - the prime has
        // done its job; drop it so it can never resurface as a stale name later
        // (e.g. after a rename + re-navigation in the same session).
        if (workflow.name) forgetWorkflowName(workflowId);
      })
      .catch(() => {
        // The post-create getWorkflow round-trip can transiently fail; prefer the
        // primed name over the bare "Workflow {uuid}" fallback so the title stays correct.
        setWorkflowName(primedName || `Workflow ${workflowId}`);
      });
  }, [workflowId, authLoading, isAuthenticated]);

  // Update local state when a metadata edit is saved
  useEffect(() => {
    const handler = (event: CustomEvent) => {
      const detail = event.detail as { resourceType: string; id: string; name: string; description: string };
      if (!detail) return;
      if (detail.resourceType === 'workflow' && detail.id === workflowId) {
        setWorkflowName(detail.name);
        setWorkflowDescription(detail.description);
        // A rename makes any primed creation-time name stale - drop it.
        forgetWorkflowName(detail.id);
      } else if (detail.resourceType === 'interface' && detail.id === interfaceId) {
        setInterfaceName(detail.name);
        setInterfaceDescription(detail.description);
      } else if (detail.resourceType === 'datasource' && String(detail.id) === String(dataSourceId)) {
        setDataSourceName(detail.name);
        setDataSourceDescription(detail.description);
      }
    };
    window.addEventListener('metadataEditSaved', handler as EventListener);
    return () => window.removeEventListener('metadataEditSaved', handler as EventListener);
  }, [workflowId, interfaceId, dataSourceId]);

  // Fetch interface name
  useEffect(() => {
    if (!isInterfacePage || !interfaceId) {
      setInterfaceName('');
      return;
    }
    if (authLoading || !isAuthenticated) {
      setInterfaceName(null);
      return;
    }

    setInterfaceName(null);
    orchestratorApi.getInterface(interfaceId)
      .then((data: { name?: string; description?: string; interfaceType?: string }) => {
        setInterfaceName(data.name || `Interface ${interfaceId}`);
        setInterfaceDescription(data.description || '');
        setInterfaceType(data.interfaceType || null);
      })
      .catch(() => setInterfaceName(`Interface ${interfaceId}`));
  }, [isInterfacePage, interfaceId, authLoading, isAuthenticated]);

  // Fetch publication title (for marketplace preview, agent preview, or application detail page).
  //
  // Marketplace + agent-preview pages are public: publicationService.getPublicationById
  // hits /api/publications/by-id which is allowlisted on the gateway (skipAuth: true).
  // So we skip the isAuthenticated gate for those paths - otherwise the breadcrumb
  // stays in the "loading…" state forever for anonymous visitors.
  //
  // The application-detail branch (/app/applications/{id}) IS auth-gated on purpose
  // - anonymous users have nothing to see there - so we keep the auth wait for it.
  const [publicationTitle, setPublicationTitle] = useState<string | null>(null);
  const [publicationType, setPublicationType] = useState<string | null>(null);
  const pubIdToFetch = previewPublicationId || agentPreviewPublicationId || publicationId;
  useEffect(() => {
    if ((!isMarketplacePreview && !isAgentMarketplacePreview && !isApplicationPage) || !pubIdToFetch) {
      setPublicationTitle(null);
      setPublicationType(null);
      return;
    }
    // Only the authenticated application-detail route should wait for auth -
    // the two marketplace routes resolve via the public /by-id endpoint.
    const requiresAuth = isApplicationPage && !isMarketplacePreview && !isAgentMarketplacePreview;
    if (requiresAuth && (authLoading || !isAuthenticated)) {
      setPublicationTitle(null);
      setPublicationType(null);
      return;
    }

    setPublicationTitle(null);
    setPublicationType(null);
    // The installed-application route resolves an ACQUIRED cloud app's metadata the SAME way
    // the page does (resolveApplicationPublication): a plain getPublicationById 404s for a cloud
    // publication id on a cloud-linked CE, so without the remote by-id fallback the breadcrumb
    // leaf dropped to the generic "Application" instead of the app's name. The two marketplace
    // preview routes keep the public by-id read they already used.
    const pubPromise = (isApplicationPage && !isMarketplacePreview && !isAgentMarketplacePreview)
      ? resolveApplicationPublication(pubIdToFetch)
      : publicationService.getPublicationById(pubIdToFetch);
    pubPromise
      .then(pub => {
        setPublicationTitle(pub.title || (isAgentMarketplacePreview ? 'Agent' : 'Application'));
        setPublicationType(pub.publicationType || (isAgentMarketplacePreview ? 'AGENT' : 'WORKFLOW'));
      })
      .catch(() => {
        setPublicationTitle(isAgentMarketplacePreview ? 'Agent' : 'Application');
        setPublicationType(isAgentMarketplacePreview ? 'AGENT' : 'WORKFLOW');
      });
  }, [isMarketplacePreview, isAgentMarketplacePreview, isApplicationPage, pubIdToFetch, authLoading, isAuthenticated]);

  // Favorite state for the current application detail page. null = unknown / not
  // applicable (loading or not on an app page); true/false once resolved. Only the
  // authenticated /app/applications/{id} route carries a favorite toggle.
  // Favorites are workspace-scoped, so re-check on active-org change too (parity
  // with the applications page and the Home favorites row).
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const [appFavorited, setAppFavorited] = useState<boolean | null>(null);
  useEffect(() => {
    if (!isApplicationPage || !publicationId || authLoading || !isAuthenticated) {
      setAppFavorited(null);
      return;
    }
    let cancelled = false;
    publicationService.getFavoriteIds()
      .then(ids => { if (!cancelled) setAppFavorited(ids.includes(publicationId)); })
      .catch(() => { if (!cancelled) setAppFavorited(false); });
    return () => { cancelled = true; };
  }, [isApplicationPage, publicationId, authLoading, isAuthenticated, currentOrgId]);

  const toggleAppFavorite = useCallback(() => {
    if (!publicationId) return;
    const wasFavorite = appFavorited === true;
    setAppFavorited(!wasFavorite); // optimistic
    const op = wasFavorite
      ? publicationService.removeFavorite(publicationId)
      : publicationService.addFavorite(publicationId);
    op.catch(() => setAppFavorited(wasFavorite)); // revert on failure
  }, [publicationId, appFavorited]);

  // Native-resource favorite state for the workflow / table / interface detail
  // breadcrumbs (the counterpart to the application breadcrumb favorite above,
  // but backed by the per-resource favorites store). Each hook is inert unless its
  // own detail page is the active view, so only one ever fetches at a time.
  const workflowFavorite = useBreadcrumbFavorite(
    'WORKFLOW',
    workflowId && workflowId !== 'new' ? workflowId : null,
    isWorkflowView && isAuthenticated && !authLoading,
  );
  const tableFavorite = useBreadcrumbFavorite(
    'TABLE',
    dataSourceId && dataSourceId !== 'new' ? String(dataSourceId) : null,
    isDataView && isAuthenticated && !authLoading,
  );
  const interfaceFavorite = useBreadcrumbFavorite(
    'INTERFACE',
    isInterfacePage ? interfaceId : null,
    isInterfacePage && isAuthenticated && !authLoading,
  );

  const iconForPublicationType = useCallback((type: string | null) => {
    switch (type) {
      case 'TABLE': return TableIcon;
      case 'INTERFACE': return LayoutPanelTop;
      case 'SKILL': return Zap;
      case 'AGENT': return Bot;
      case 'WORKFLOW':
      default: return Workflow;
    }
  }, []);

  // Fetch project name
  useEffect(() => {
    if (!isProjectView || !projectId) {
      setProjectName(null);
      return;
    }
    if (authLoading || !isAuthenticated) {
      setProjectName(null);
      return;
    }

    setProjectName(null);
    projectService.getProject(projectId)
      .then(p => setProjectName(p.name || `Project`))
      .catch(() => setProjectName('Project'));
  }, [isProjectView, projectId, authLoading, isAuthenticated]);

  // Home breadcrumb item
  const homeItem: BreadcrumbItem = useMemo(() => ({
    label: '',
    icon: Home,
    onClick: () => navigateTo('/app'),
  }), [navigateTo]);

  // Build breadcrumb items based on current view
  const breadcrumbItems = useMemo((): BreadcrumbItem[] => {
    // Settings breadcrumbs
    if (isSettingsView && settingsPath) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Settings', onClick: () => navigateTo('/app/settings') },
      ];

      const segments = settingsPath.split('/').filter(Boolean);
      if (segments.length === 0) {
        items.push({ label: 'Overview' });
      } else {
        segments.forEach((segment, index) => {
          // Determine label
          let label = SETTINGS_LABELS[segment] || segment;
          let isLoading = false;

          if (mcpMatch?.slug && segment === mcpMatch.slug) {
            label = mcpInfo.title || segment;
            isLoading = !mcpInfo.title;
          } else if (mcpMatch?.toolId && segment === mcpMatch.toolId) {
            label = mcpInfo.toolName || segment;
            isLoading = !mcpInfo.toolName;
          }

          items.push({
            label,
            isLoading,
            onClick: (index < segments.length - 1 && !isLoading)
              ? () => navigateTo(`/app/settings/${segments.slice(0, index + 1).join('/')}`)
              : undefined,
          });
        });
      }

      return items;
    }

    // Workflow breadcrumbs
    if (isWorkflowView) {
      const openWorkflowEditModal = () => {
        if (!workflowId || workflowId === 'new') return;
        window.dispatchEvent(new CustomEvent('openMetadataEditModal', {
          detail: {
            resourceType: 'workflow',
            id: workflowId,
            name: workflowName || '',
            description: workflowDescription || '',
          },
        }));
      };

      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Workflows', onClick: () => navigateTo('/app/workflow') },
      ];

      if (workflowId && workflowId !== 'new') {
        const isLoading = workflowName === null;
        items.push({
          label: workflowName || '',
          isLoading,
          truncate: true,
          editable: !isLoading,
          onClick: openWorkflowEditModal,
          favorite: (!isLoading && isAuthenticated && workflowFavorite.isFavorite !== null) ? {
            isFavorite: workflowFavorite.isFavorite,
            onToggle: workflowFavorite.toggle,
          } : undefined,
        });

        // Check for run page (only show if loaded)
        if (!isLoading) {
          const runMatch = normalizedPathname?.match(/\/workflow\/[^\/]+\/run\/([^\/]+)/);
          if (runMatch?.[1]) {
            const runId = runMatch[1];
            items.push({
              label: runId.substring(0, 8) + (runId.length > 8 ? '...' : ''),
            });
          }
        }
      }

      return items;
    }

    // Data breadcrumbs
    if (isDataView) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Tables', onClick: () => navigateTo('/app/tables') },
      ];

      if (dataSourceId && dataSourceId !== 'new') {
        const isLoading = dataSourceName === null;
        const dataPathMatch = normalizedPathname?.match(/\/app\/(?:tables|data)\/[^\/]+\/(.+)/);
        const isLastDataItem = !dataPathMatch?.[1];

        const openDataSourceEditModal = () => {
          window.dispatchEvent(new CustomEvent('openMetadataEditModal', {
            detail: {
              resourceType: 'datasource',
              id: dataSourceId,
              name: dataSourceName || '',
              description: dataSourceDescription || '',
            },
          }));
        };

        items.push({
          label: dataSourceName || '',
          isLoading,
          truncate: true,
          editable: !isLoading && isLastDataItem,
          onClick: isLoading
            ? undefined
            : isLastDataItem
              ? openDataSourceEditModal
              : () => navigateTo(`/app/tables/${dataSourceId}`),
          // Star only on the table crumb itself, not when drilled into a JSON subpath.
          favorite: (!isLoading && isLastDataItem && isAuthenticated && tableFavorite.isFavorite !== null) ? {
            isFavorite: tableFavorite.isFavorite,
            onToggle: tableFavorite.toggle,
          } : undefined,
        });

        // Extract JSON path segments (only show if loaded)
        if (!isLoading) {
          if (dataPathMatch?.[1]) {
            const pathSegments = dataPathMatch[1].split('/');
            pathSegments.forEach((segment, index) => {
              const pathUrl = pathSegments.slice(0, index + 1).join('/');
              items.push({
                label: segment,
                onClick: index < pathSegments.length - 1
                  ? () => navigateTo(`/app/tables/${dataSourceId}/${pathUrl}`)
                  : undefined,
              });
            });
          }
        }
      }

      return items;
    }

    // Marketplace breadcrumbs
    if (isMarketplaceView) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Marketplace', onClick: () => navigateTo('/app/marketplace') },
      ];

      if (isMarketplacePreview && previewPublicationId) {
        const isLoading = publicationTitle === null;
        items.push({
          label: publicationTitle || '',
          icon: iconForPublicationType(publicationType),
          isLoading,
          truncate: true,
        });
      } else if (isAgentMarketplacePreview && agentPreviewPublicationId) {
        // Back to the AGENTS grid, not the marketplace root: an agent preview is
        // only ever reached from the Agents type filter, and dropping the param
        // landed the user back on Explore/Applications with the agent they had
        // just opened nowhere on screen.
        items.push(
          { label: MARKETPLACE_LABELS['agents'], onClick: () => navigateTo('/app/marketplace?type=agents') },
        );
      } else if (normalizedPathname?.startsWith('/app/marketplace/')) {
        const category = normalizedPathname.replace('/app/marketplace/', '');
        if (category && MARKETPLACE_LABELS[category]) {
          items.push({ label: MARKETPLACE_LABELS[category] });
        }
      }

      return items;
    }

    // Agent breadcrumbs
    if (isAgentView) {
      const view = searchParams.get('view');
      if (view === 'fleet') {
        return [
          homeItem,
          { label: 'Agents', onClick: () => navigateTo('/app/agent') },
          { label: 'Fleet' },
        ];
      }
      if (view === 'metrics') {
        return [
          homeItem,
          { label: 'Agents', onClick: () => navigateTo('/app/agent') },
          { label: 'Metrics' },
        ];
      }
      if (view === 'skills') {
        return [
          homeItem,
          { label: 'Agents', onClick: () => navigateTo('/app/agent') },
          { label: 'Skills' },
        ];
      }
      return [homeItem, { label: 'Agents' }];
    }

    // Interface breadcrumbs
    if (isInterfaceView) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Interfaces', onClick: () => navigateTo('/app/interface') },
      ];

      if (isInterfacePage && interfaceId) {
        const isLoading = interfaceName === null;
        const openInterfaceEditModal = () => {
          window.dispatchEvent(new CustomEvent('openMetadataEditModal', {
            detail: {
              resourceType: 'interface',
              id: interfaceId,
              name: interfaceName || '',
              description: interfaceDescription || '',
            },
          }));
        };
        items.push({
          label: interfaceName || '',
          isLoading,
          truncate: true,
          editable: !isLoading,
          onClick: isLoading ? undefined : openInterfaceEditModal,
          favorite: (!isLoading && isAuthenticated && interfaceFavorite.isFavorite !== null) ? {
            isFavorite: interfaceFavorite.isFavorite,
            onToggle: interfaceFavorite.toggle,
          } : undefined,
        });
      }

      return items;
    }

    // Application breadcrumbs
    if (isApplicationView) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Applications', onClick: isApplicationPage ? () => navigateTo('/app/applications') : undefined },
      ];

      if (isApplicationPage && publicationId) {
        const isLoading = publicationTitle === null;
        items.push({
          label: publicationTitle || '',
          isLoading,
          truncate: true,
          // Star toggle appears on hover over the app name (always shown once
          // favorited). Only when the favorite state has resolved for a signed-in user.
          favorite: (!isLoading && isAuthenticated && appFavorited !== null) ? {
            isFavorite: appFavorited,
            onToggle: toggleAppFavorite,
          } : undefined,
        });
      }

      return items;
    }

    // Files breadcrumbs - Home / Files / [FolderA / SubB …] / [filename]. The
    // Files page is a single route, so the open-file tail AND the manual-folder
    // trail (V313) both come from the focused-viewer state broadcast by
    // FileBrowser. Clicking "Files" returns to the root listing; clicking a
    // folder crumb navigates into that folder; clicking the open-file "Files"
    // ancestor while a file is open closes the viewer. All via the shared bus -
    // these are not routes.
    if (isFilesView) {
      const detailOpen = !!filesDetail?.open;
      const folderTrail = filesDetail?.folderTrail ?? [];
      const atRoot = folderTrail.length === 0;
      const items: BreadcrumbItem[] = [
        homeItem,
        {
          label: 'Files',
          // "Files" navigates to root unless we're already there with no file open
          // (then it's the current page - a no-op). With a file open at root it
          // closes the viewer.
          onClick: detailOpen
            ? () => emitFilesDetailCommand(FILES_DETAIL_BACK)
            : atRoot
              ? undefined
              : () => emitFilesFolderNavigate(null),
        },
      ];

      // Manual-folder trail crumbs. Each intermediate crumb navigates into its
      // folder; the last folder crumb is the current page only when no file is
      // open (then it's not clickable).
      folderTrail.forEach((crumb, index) => {
        const isLastFolder = index === folderTrail.length - 1;
        const isCurrentPage = isLastFolder && !detailOpen;
        items.push({
          label: crumb.name,
          truncate: true,
          onClick: isCurrentPage ? undefined : () => emitFilesFolderNavigate(crumb.id),
        });
      });

      if (detailOpen && filesDetail?.fileName) {
        const fileId = filesDetail.fileId;
        const openFileEditModal = fileId
          ? () => window.dispatchEvent(new CustomEvent('openMetadataEditModal', {
              detail: { resourceType: 'file', id: fileId, name: filesDetail.fileName || '' },
            }))
          : undefined;
        items.push({
          label: filesDetail.fileName,
          truncate: true,
          editable: !!fileId,
          onClick: openFileEditModal,
        });
      }
      return items;
    }

    // Public profile breadcrumbs - Home / Profile / @handle. Replaces the model
    // selector (which is meaningless on a profile page); the @handle tail is the
    // current page, so neither it nor "Profile" navigates anywhere.
    if (isProfileView && profileHandle) {
      return [
        homeItem,
        { label: 'Profile' },
        { label: `@${profileHandle}`, truncate: true },
      ];
    }

    // Project breadcrumbs
    if (isProjectView) {
      const items: BreadcrumbItem[] = [
        homeItem,
        { label: 'Projects', onClick: projectId ? () => navigateTo('/app') : undefined },
      ];

      if (projectId) {
        items.push({
          label: projectName || '',
          isLoading: projectName === null,
          truncate: true,
        });
      }

      return items;
    }

    return [];
  }, [
    settingsPath,
    isSettingsView,
    isWorkflowView,
    isDataView,
    isMarketplaceView,
    isMarketplacePreview,
    previewPublicationId,
    isAgentMarketplacePreview,
    agentPreviewPublicationId,
    publicationTitle,
    publicationType,
    iconForPublicationType,
    isAgentView,
    isInterfaceView,
    isInterfacePage,
    isApplicationPage,
    publicationId,
    interfaceId,
    normalizedPathname,
    homeItem,
    workflowId,
    dataSourceId,
    workflowName,
    workflowDescription,
    dataSourceName,
    dataSourceDescription,
    interfaceName,
    interfaceDescription,
    navigateTo,
    mcpMatch,
    mcpInfo,
    searchParams,
    isProjectView,
    projectId,
    projectName,
    isFilesView,
    filesDetail,
    isProfileView,
    profileHandle,
    isAuthenticated,
    appFavorited,
    toggleAppFavorite,
    workflowFavorite.isFavorite,
    workflowFavorite.toggle,
    tableFavorite.isFavorite,
    tableFavorite.toggle,
    interfaceFavorite.isFavorite,
    interfaceFavorite.toggle,
  ]);

  const isAgentFleet = isAgentView && searchParams.get('view') === 'fleet';

  return {
    breadcrumbItems,
    shouldShowBreadcrumb,
    isDataViewWithDataSource,
    isWorkflowViewWithWorkflow,
    isInterfacePage,
    interfaceId,
    interfaceType,
    isRunMode,
    isApplicationPage,
    isMarketplacePreview,
    previewPublicationId,
    isAgentMarketplacePreview,
    agentPreviewPublicationId,
    isAgentFleet,
    isFilesView,
    filesDetail,
  };
}
