'use client';

import type { AgentPageTab } from '@/components/views/AgentPageTabBar';
import { useMemo, useState, useEffect, useCallback } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { Home, Table as TableIcon, Workflow, LayoutPanelTop, Zap, Bot } from 'lucide-react';
import { useCurrentView } from '@/hooks/useCurrentView';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';
import type { DataSource } from '@/lib/api';
import type { ResourceEditor } from '@/lib/api/orchestrator/types';
import { loadWorkflowEditors } from '@/lib/api/orchestrator/version.service';
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
import {
  onResourceFolderTrail,
  showFolderLevel,
  FOLDER_QUERY_PARAM,
  type ResourceFolderCrumb,
  type ResourceFolderTrailState,
} from '@/lib/folders/foldersHeaderBus';
import { samePageUrl, showSamePageUrl } from '@/lib/navigation/showSamePageUrl';

/**
 * What the crumb's info control shows about the open resource. Every field comes from the
 * response the crumb already fetched to get the title, so carrying it costs no request.
 */
interface ResourceAttribution {
  /** Row owner (its `tenantId`), resolved to a name by the popover. */
  ownerId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

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
  /**
   * Keep this segment clickable even in the LAST position. The last crumb is normally the page
   * you are on, so it does not navigate - but a crumb can be last and still be the way OUT: a
   * list showing a folder it cannot name yet prints no folder segment after its own, and
   * without this the only exit is the browser's Back button.
   */
  alwaysClickable?: boolean;
  /** When set, renders a favorite-toggle star on this segment (see breadcrumb.tsx). */
  favorite?: { isFavorite: boolean; onToggle: () => void };
  /** When set, renders the resource-info control on this segment (see breadcrumb.tsx). */
  info?: ResourceAttribution & {
    /** Identifies the resource, so the reused control drops the previous one's editors. */
    resourceKey?: string;
    loadEditors?: () => Promise<ResourceEditor[]>;
  };
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

/**
 * Agents-page tabs (?view=) and the trailing crumb each one gets under "Agents".
 *
 * Keyed by the tab union MINUS the default tab, so adding a tab to AgentPageTab without
 * giving it a crumb is a COMPILE error rather than a silent fall-through to the bare
 * "Agents" crumb - which is how the Memory tab shipped crumbless.
 */
export const AGENT_TAB_CRUMBS: Record<Exclude<AgentPageTab, 'agents'>, string> = {
  'fleet': 'Fleet',
  'metrics': 'Metrics',
  'skills': 'Skills',
  'memory': 'Memory',
  'settings': 'Settings',
};

const SETTINGS_LABELS: Record<string, string> = {
  'overview': 'Overview',
  'agents': 'Agents & Chat',
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
  // Attribution for the crumb's info control (owner + timestamps). Kept per resource kind
  // rather than in one slot: the name fetches are independent effects, and a shared slot
  // would let a stale interface answer paint under an open workflow.
  const [workflowAttribution, setWorkflowAttribution] = useState<ResourceAttribution | null>(null);
  const [dataSourceAttribution, setDataSourceAttribution] = useState<ResourceAttribution | null>(null);
  const [interfaceAttribution, setInterfaceAttribution] = useState<ResourceAttribution | null>(null);
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

  // The folder path a resource LIST is showing (V448-V452). Which folder is open lives in
  // the address, but only the list knows what those ids are CALLED, so it broadcasts the
  // names and we print them. Dropped as soon as we leave a list, or the header would keep
  // showing a path the user has left.
  const isResourceListView = isWorkflowView || isAgentView || isDataView
    || isInterfaceView || isApplicationView;
  const [folderTrailState, setFolderTrailState] = useState<ResourceFolderTrailState | null>(null);
  useEffect(() => {
    if (!isResourceListView) {
      setFolderTrailState(null);
      return;
    }
    return onResourceFolderTrail(setFolderTrailState);
  }, [isResourceListView]);

  /**
   * Go to a level of the list already on screen. Same page, only `?folder=` changes, so it
   * goes through {@link showFolderLevel} rather than the router - see that helper for why a
   * router push cannot leave a folder the page was loaded directly into.
   */
  const goToFolderLevel = useCallback((folderId: string | null) => {
    showFolderLevel(pathname ?? '', searchParams, folderId);
  }, [pathname, searchParams]);

  /**
   * Go to a list's own page, from wherever the crumb was clicked.
   *
   * <p>When that page is the one already on screen - a folder open on the workflows list, the
   * Fleet tab of the agents list, a type filter on the marketplace - only the QUERY is being
   * dropped, and routing to a pathname the page was loaded at is exactly the push Next drops.
   * That is the whole bug this change exists for, and every crumb that says "take me back to
   * the plain list" is the same shape of it. Off that page it is an ordinary route change.
   */
  const goToListPage = useCallback((path: string) => {
    const target = buildLocalePath(locale, path);
    const [targetPath] = target.split('?');
    if (targetPath === pathname) {
      showSamePageUrl(target, samePageUrl(pathname ?? '', searchParams));
      return;
    }
    navigateTo(path);
  }, [locale, pathname, searchParams, navigateTo]);

  /**
   * The folder crumbs to append after a list's own crumb. Each one navigates by changing the
   * address (`?folder=<id>`), which is what the list itself listens to - so a crumb, a
   * shared link and the back button all take exactly the same path.
   */
  const folderCrumbs = useCallback((view: ResourceFolderTrailState['view']): BreadcrumbItem[] => {
    if (folderTrailState?.view !== view) return [];
    const trail = folderTrailState.trail;
    return trail.map((crumb: ResourceFolderCrumb, index: number) => ({
      label: crumb.name,
      truncate: true,
      // The last crumb IS the page being shown, so it does not navigate.
      onClick: index === trail.length - 1 ? undefined : () => goToFolderLevel(crumb.id),
    }));
  }, [folderTrailState, goToFolderLevel]);

  /**
   * A list's OWN crumb ("Workflows"). While a folder is open it is the way back to the TOP
   * LEVEL of the page already on screen, which is a `?folder=` change and not a route change:
   * routing to the list's own path leaves the address untouched (the pathname is already that
   * one) and the crumb does nothing at all. Off a folder it is an ordinary link to the list.
   *
   * <p>Which of the two it is comes from the ADDRESS, never from the trail the list broadcasts.
   * The trail is a repaint of names that can outlive the level it described - a list unmounting,
   * a navigation the list has not caught up with - and reading it here would put the crumb back
   * in the state this whole change exists to remove: a click that resolves to the address it is
   * already on, and so does nothing at all.
   *
   * <p>The caller still passes the folder path it decided to SHOW, so a crumb cannot offer to
   * leave a folder on a page that is not the list (a detail page hides the crumbs but sits on
   * its own pathname, where a `?folder=` would be meaningless).
   */
  const listRootCrumb = useCallback(
    (label: string, path: string, clickableAtRoot = true): BreadcrumbItem => {
      // On the list's own page with a folder open, this is the way OUT: up one address,
      // keeping whatever else the page carries (a search, a page number). It reads that from
      // the ADDRESS, not from the trail the list broadcasts - the trail arrives after the
      // first render and can outlive the level it described, and either way the exit must not
      // wait for a name it does not need. `alwaysClickable` because until the trail lands
      // there is no folder segment after this one, which would otherwise make it the last
      // crumb and so inert, leaving the browser's Back button as the only escape.
      const onListPage = buildLocalePath(locale, path).split('?')[0] === pathname;
      if (onListPage && searchParams.get(FOLDER_QUERY_PARAM) !== null) {
        return { label, onClick: () => goToFolderLevel(null), alwaysClickable: true };
      }
      // Anywhere else it is an ordinary link to the list. Some lists keep their own crumb
      // inert at the top level rather than offering to go where you already are.
      return { label, onClick: clickableAtRoot ? () => goToListPage(path) : undefined };
    },
    [goToFolderLevel, goToListPage, locale, pathname, searchParams],
  );


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
    setDataSourceAttribution(null);
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
        const current = dataSources.find((ds: DataSource) => String(ds.id) === String(dsId));
        setDataSourceName(current?.name || 'Table');
        setDataSourceDescription(current?.description || '');
        setDataSourceAttribution(current ? {
          ownerId: current.tenantId ?? current.tenant_id ?? null,
          // The list DTO serializes snake_case; the camelCase forms are kept for other
          // producers (see the DataSource type), so read both rather than assume one.
          createdAt: current.createdAt ?? current.created_at ?? null,
          updatedAt: current.updatedAt ?? current.updated_at ?? null,
        } : null);
      })
      .catch(() => { setDataSourceName('Table'); setDataSourceAttribution(null); });
  }, [dataSourceId, authLoading, isAuthenticated]);

  // Fetch workflow name
  useEffect(() => {
    // Reset with the name: attribution belongs to the resource being left, and a crumb that
    // kept it would date workflow B by workflow A until B's fetch lands.
    setWorkflowAttribution(null);
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
      .then((workflow: { name?: string; description?: string; tenantId?: string; createdAt?: string; updatedAt?: string }) => {
        setWorkflowName(workflow.name || primedName || `Workflow ${workflowId}`);
        setWorkflowDescription(workflow.description || '');
        // Free: the response the title already needed carries the attribution too.
        setWorkflowAttribution({
          ownerId: workflow.tenantId ?? null,
          createdAt: workflow.createdAt ?? null,
          updatedAt: workflow.updatedAt ?? null,
        });
        // The server is reachable and has an authoritative name - the prime has
        // done its job; drop it so it can never resurface as a stale name later
        // (e.g. after a rename + re-navigation in the same session).
        if (workflow.name) forgetWorkflowName(workflowId);
      })
      .catch(() => {
        // The post-create getWorkflow round-trip can transiently fail; prefer the
        // primed name over the bare "Workflow {uuid}" fallback so the title stays correct.
        setWorkflowName(primedName || `Workflow ${workflowId}`);
        setWorkflowAttribution(null);
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
    setInterfaceAttribution(null);
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
      .then((data: { name?: string; description?: string; interfaceType?: string; tenantId?: string; createdAt?: string; updatedAt?: string }) => {
        setInterfaceName(data.name || `Interface ${interfaceId}`);
        setInterfaceDescription(data.description || '');
        setInterfaceType(data.interfaceType || null);
        setInterfaceAttribution({
          ownerId: data.tenantId ?? null,
          createdAt: data.createdAt ?? null,
          updatedAt: data.updatedAt ?? null,
        });
      })
      .catch(() => { setInterfaceName(`Interface ${interfaceId}`); setInterfaceAttribution(null); });
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

      // Inside a folder, the path continues: Workflows / Marketing / Q4.
      const workflowFolderPath = workflowId ? [] : folderCrumbs('workflow');
      const items: BreadcrumbItem[] = [
        homeItem,
        listRootCrumb('Workflows', '/app/workflow'),
        ...workflowFolderPath,
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
          // Workflows are the one resource with a real edit history (their plan versions),
          // so this is the only crumb that offers the "recent editors" section.
          info: (!isLoading && workflowAttribution) ? {
            resourceKey: `workflow:${workflowId}`,
            ...workflowAttribution,
            loadEditors: () => loadWorkflowEditors(workflowId),
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
      const tableFolderPath = dataSourceId ? [] : folderCrumbs('table');
      const items: BreadcrumbItem[] = [
        homeItem,
        listRootCrumb('Tables', '/app/tables'),
        ...tableFolderPath,
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
          // Same rule as the star: the table's own crumb, not a JSON subpath crumb.
          info: (!isLoading && isLastDataItem && dataSourceAttribution)
            ? { resourceKey: `table:${dataSourceId}`, ...dataSourceAttribution }
            : undefined,
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
        { label: 'Marketplace', onClick: () => goToListPage('/app/marketplace') },
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
          { label: MARKETPLACE_LABELS['agents'], onClick: () => goToListPage('/app/marketplace?type=agents') },
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
      // Every non-default Agents tab is one crumb under 'Agents'; AGENT_TAB_CRUMBS is keyed by
      // the tab union, so a new tab cannot reach here without one. hasOwnProperty, not a bare
      // lookup: ?view= is whatever the address bar holds, and 'toString' or '__proto__' would
      // otherwise resolve up the prototype chain to a truthy non-string and land a function or
      // an object where the crumb expects a label. An unknown ?view= is not a tab and falls
      // through to the plain Agents crumb below.
      const tabLabel = view && Object.prototype.hasOwnProperty.call(AGENT_TAB_CRUMBS, view)
        ? AGENT_TAB_CRUMBS[view as Exclude<AgentPageTab, 'agents'>]
        : undefined;
      if (tabLabel) {
        return [
          homeItem,
          { label: 'Agents', onClick: () => goToListPage('/app/agent') },
          { label: tabLabel },
        ];
      }
      const agentFolderPath = folderCrumbs('agent');
      return [
        homeItem,
        // With a folder open, "Agents" goes back to the top level rather than sitting inert.
        listRootCrumb('Agents', '/app/agent', false),
        ...agentFolderPath,
      ];
    }

    // Interface breadcrumbs
    if (isInterfaceView) {
      const interfaceFolderPath = isInterfacePage ? [] : folderCrumbs('interface');
      const items: BreadcrumbItem[] = [
        homeItem,
        listRootCrumb('Interfaces', '/app/interface'),
        ...interfaceFolderPath,
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
          info: (!isLoading && interfaceAttribution)
            ? { resourceKey: `interface:${interfaceId}`, ...interfaceAttribution }
            : undefined,
        });
      }

      return items;
    }

    // Application breadcrumbs
    if (isApplicationView) {
      const folderPath = isApplicationPage ? [] : folderCrumbs('application');
      const items: BreadcrumbItem[] = [
        homeItem,
        listRootCrumb('Applications', '/app/applications', isApplicationPage),
        ...folderPath,
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
    // The folder path of a resource list: without this the header keeps the path it printed
    // when the memo last ran, which is the whole point of the crumbs. `listRootCrumb` and
    // `goToListPage` carry the address those crumbs navigate to, so they belong here too.
    folderCrumbs,
    listRootCrumb,
    goToListPage,
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
    // Attribution for the crumb's info control. Without these the control would keep the
    // (usually absent) attribution captured when the memo last ran, so it would never
    // appear after the name fetch resolves.
    workflowAttribution,
    dataSourceAttribution,
    interfaceAttribution,
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
