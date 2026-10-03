'use client';

import { use, useMemo } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { InterfacePreview } from '@/components/InterfacePreview';
import { InterfaceViewerControls } from '@/components/interfaces/InterfaceViewerControls';
import {
  emitInterfaceViewerControls,
  onInterfaceViewerControlsToggle,
} from '@/lib/interfaces/interfaceViewerBus';
import { CreateInterfaceModal } from '@/components/chat/CreateInterfaceModal';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { urlPageIndex, useUrlState } from '@/hooks/useUrlState';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useState, useEffect, useCallback } from 'react';
import { orchestratorApi } from '@/lib/api';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { resolveTemplateWithData, resolveHtmlTemplate } from '@/app/workflows/builder/utils/htmlTemplateResolver';

interface InterfaceData {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  targetTable?: string;
  dataSourceId?: number;
  /** Display format (preset name or "WIDTHxHEIGHT"); null = full page at 1280x800. */
  format?: string | null;
  isPublic: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
  sourceWorkflowId?: string;
  interfaceType?: string;
  data?: Record<string, unknown>;
}

interface RenderItem {
  itemIndex: number;
  data: Record<string, any>;
}

interface RenderResult {
  htmlTemplate: string;
  cssTemplate?: string;
  jsTemplate?: string;
  format?: string | null;
  items: RenderItem[];
  pagination: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
}

/**
 * Interface detail page component
 * Displays a specific interface by ID in fullscreen with HTML preview
 */
export default function InterfaceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = use(params);
  const router = useRouter();
  const t = useTranslations();
  // Use isAuthChecking (OIDC native loading) for faster UI rendering
  const { user, isAuthenticated, isAuthChecking } = useAuthGuard();
  const tenantId = user?.sub || user?.email || 'demo';
  const [interfaceData, setInterfaceData] = useState<InterfaceData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Edit modal state
  const [showEditModal, setShowEditModal] = useState(false);

  // What the reader can change about the page on screen.
  //
  // Sound starts OFF. A page opened from the list is something you look at, and a page that
  // starts talking the moment it is opened has taken a decision that belongs to the reader -
  // the same reason an application's preview starts silent. Claiming the volume here is what
  // earns the control: everywhere that offers no control passes nothing and the page plays
  // exactly as authored.
  const [soundOn, setSoundOn] = useState(false);
  const [hasAudio, setHasAudio] = useState(false);
  const [controlsOpen, setControlsOpen] = useState(false);

  // Render data for interfaces with datasource
  const [renderResult, setRenderResult] = useState<RenderResult | null>(null);
  // Which item of the datasource is on screen (0 = newest), kept in the address so a reload
  // reopens the same one.
  const [currentPage, setCurrentPage] = useUrlState('page', 0, { codec: urlPageIndex });
  const [isResolvingData, setIsResolvingData] = useState(false);

  // Fetch interface data
  const fetchInterface = useCallback(async () => {
    if (!tenantId || !resolvedParams.id) return;

    setIsLoading(true);
    setError(null);

    try {
      const data = await orchestratorApi.getInterface(resolvedParams.id);
      setInterfaceData(data as unknown as InterfaceData);
      if ((data as any).dataSourceId) {
        setIsResolvingData(true);
      }
    } catch (err: any) {
      console.error('Error fetching interface:', err);
      if (err?.status === 404) {
        router.push('/app/interface');
        return;
      }
      setError('Failed to load interface');
    } finally {
      setIsLoading(false);
    }
  }, [tenantId, resolvedParams.id, router]);

  useEffect(() => {
    if (isAuthenticated && !isAuthChecking) {
      fetchInterface();
    }
  }, [isAuthenticated, isAuthChecking, fetchInterface]);

  // Fetch render data if interface has a datasource
  useEffect(() => {
    const fetchRenderData = async () => {
      if (!interfaceData?.dataSourceId) {
        setRenderResult(null);
        return;
      }

      try {
        setIsResolvingData(true);
        const result = await orchestratorApi.renderInterfaceWithDatasource(resolvedParams.id, {
          page: currentPage,
          size: 1 // One item at a time for preview
        });
        setRenderResult(result as unknown as RenderResult);
      } catch (err: any) {
        console.error('Failed to fetch render data:', err);
        setRenderResult(null);
      } finally {
        setIsResolvingData(false);
      }
    };

    if (interfaceData?.dataSourceId) {
      fetchRenderData();
    }
  }, [interfaceData, resolvedParams.id, currentPage]);

  // Compute resolved HTML template (pre-computed by backend with expression engine)
  const resolvedHtmlTemplate = useMemo(() => {
    if (renderResult?.items?.length > 0) {
      const currentItem = renderResult.items[0];
      // Backend provides _resolvedHtml with all functions evaluated (formatDate, formatNumber, etc.)
      if (currentItem.data?._resolvedHtml) {
        return currentItem.data._resolvedHtml as string;
      }
      // Fallback to simple variable replacement
      return resolveTemplateWithData(renderResult.htmlTemplate, currentItem.data);
    }
    return resolveHtmlTemplate(interfaceData?.htmlTemplate || '');
  }, [interfaceData, renderResult]);

  const totalItems = renderResult?.pagination?.totalItems || 0;
  const totalPages = renderResult?.pagination?.totalPages || 0;

  // An address asking for an item past the last one (rows were deleted since) lands on the last.
  // Only once the count is known: it is 0 until the first render answer arrives.
  useEffect(() => {
    if (totalPages > 0 && currentPage > totalPages - 1) setCurrentPage(totalPages - 1);
  }, [totalPages, currentPage, setCurrentPage]);

  // Epoch nav: page 0 = newest. handleNewer decrements, handleOlder increments.
  const handleNewer = useCallback(() => {
    if (currentPage > 0) {
      setCurrentPage(prev => prev - 1);
    }
  }, [currentPage, setCurrentPage]);

  const handleOlder = useCallback(() => {
    if (currentPage < totalPages - 1) {
      setCurrentPage(prev => prev + 1);
    }
  }, [currentPage, totalPages, setCurrentPage]);

  // Listen for edit event from AppHeader
  useEffect(() => {
    const handleEditInterface = () => {
      setShowEditModal(true);
    };

    window.addEventListener('interfaceEdit', handleEditInterface);
    return () => {
      window.removeEventListener('interfaceEdit', handleEditInterface);
    };
  }, []);

  // The header owns the button beside Edit, and only this page knows whether pressing it would
  // show anything: a page with no media has nothing to control yet. Cleared on the way out, or
  // the header would keep offering controls for a page that is gone.
  useEffect(() => {
    emitInterfaceViewerControls({ available: hasAudio, open: controlsOpen, soundOn });
  }, [hasAudio, controlsOpen, soundOn]);
  useEffect(
    () => () => emitInterfaceViewerControls({ available: false, open: false, soundOn: false }),
    [],
  );

  useEffect(() => onInterfaceViewerControlsToggle(() => setControlsOpen((open) => !open)), []);

  // A page with nothing to control must not leave its panel stranded on screen.
  useEffect(() => {
    if (!hasAudio) setControlsOpen(false);
  }, [hasAudio]);

  // A page whose template goes away takes its frame with it, so nothing is left to report that
  // the media has gone. Without this the header would go on offering a control over a page that
  // is no longer rendered.
  useEffect(() => {
    if (!resolvedHtmlTemplate?.trim()) setHasAudio(false);
  }, [resolvedHtmlTemplate]);

  // Escape closes the controls, the way it closes everything else that floats over a page.
  useEffect(() => {
    if (!controlsOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setControlsOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [controlsOpen]);

  // Live sync: when the LLM updates this interface via chat (StreamingContext
  // dispatches `interfaceModified` on interface tool completion), re-fetch so an
  // already-open detail page doesn't stay frozen on the pre-mutation template
  // until a manual reload. Mirrors the canvas node (InterfacePreviewNode) and
  // the side panel (InterfacePanelContent), which already refresh on this event.
  useEffect(() => {
    const handleInterfaceModified = () => {
      fetchInterface();
    };

    window.addEventListener('interfaceModified', handleInterfaceModified);
    return () => {
      window.removeEventListener('interfaceModified', handleInterfaceModified);
    };
  }, [fetchInterface]);

  // Handle edit modal close with refresh
  const handleEditComplete = useCallback(() => {
    setShowEditModal(false);
    // Refresh interface data after edit
    fetchInterface();
  }, [fetchInterface]);

  if (isAuthChecking || isLoading) {
    return (
      <div className="h-full w-full flex items-center justify-center bg-theme-primary transition-colors duration-300">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="h-full w-full flex items-center justify-center bg-theme-primary">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6">
          <h2 className="text-xl font-semibold text-theme-primary mb-2">Unauthorized</h2>
          <p className="text-theme-secondary">Sign in to view this interface.</p>
        </div>
      </div>
    );
  }

  if (error || !interfaceData) {
    return (
      <div className="h-full w-full flex items-center justify-center bg-theme-primary">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6 max-w-md">
          <h2 className="text-xl font-semibold text-theme-primary mb-2">Error</h2>
          <p className="text-theme-secondary">{error || 'Interface not found'}</p>
          <button
            onClick={() => router.push('/app/interface')}
            className="mt-4 text-sm text-[var(--accent-primary)] hover:underline"
          >
            ← Back to interfaces
          </button>
        </div>
      </div>
    );
  }

  // Legacy web_search interfaces (created before the sidepanel was retired) have no
  // htmlTemplate to render. Send the user back to the list with a one-line note
  // so they don't land on a blank page.
  if (interfaceData.interfaceType === 'web_search') {
    return (
      <div className="h-full w-full flex items-center justify-center bg-theme-primary">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6 max-w-md text-center">
          <p className="text-theme-secondary text-sm">
            {t('chat.interfaceBlock.webSearchRetired')}
          </p>
          <button
            onClick={() => router.push('/app/interface')}
            className="mt-4 text-sm text-[var(--accent-primary)] hover:underline"
          >
            ← {t('chat.interfaceBlock.backToInterfaces')}
          </button>
        </div>
      </div>
    );
  }

  // Fullscreen preview (HTML interfaces)
  return (
    <div className="h-full overflow-hidden">
      <div className="w-full h-full relative">
        <InterfacePreview
          htmlTemplate={resolvedHtmlTemplate}
          cssTemplate={renderResult?.cssTemplate || interfaceData?.cssTemplate}
          jsTemplate={renderResult?.jsTemplate || interfaceData?.jsTemplate}
          className="w-full h-full"
          isLoading={isResolvingData}
          autoFit={false}
          // The interface's own shape. Without it, a 1080x1920 page rendered at its native size
          // in whatever box the layout gave it - the reason vertical interfaces looked broken.
          format={renderResult?.format ?? interfaceData?.format}
          emptyLabel={t('chat.interfaceBlock.noTemplate')}
          mediaMuted={!soundOn}
          onMediaAudioPresence={setHasAudio}
        />

        {/* What can be changed about the page, opened from the header button beside Edit. */}
        {controlsOpen && (
          <div
            id="interface-viewer-controls"
            className="absolute bottom-4 left-1/2 -translate-x-1/2 z-20"
          >
            <InterfaceViewerControls
              soundOn={soundOn}
              onToggleSound={() => setSoundOn((on) => !on)}
              hasAudio={hasAudio}
              onClose={() => setControlsOpen(false)}
            />
          </div>
        )}

        {/* Pagination controls - show if there are multiple items */}
        {totalItems > 1 && (
          <div className="absolute top-4 left-4 flex items-center gap-2 bg-white/90 dark:bg-gray-800/90 backdrop-blur-sm rounded-full px-3 py-2 shadow-lg">
            <button
              onClick={handleOlder}
              disabled={currentPage >= totalPages - 1}
              className={`p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors ${currentPage >= totalPages - 1 ? 'opacity-30 cursor-not-allowed' : ''}`}
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300 px-2">
              {totalPages - currentPage} / {totalPages}
            </span>
            <button
              onClick={handleNewer}
              disabled={currentPage === 0}
              className={`p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors ${currentPage === 0 ? 'opacity-30 cursor-not-allowed' : ''}`}
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        )}

      </div>

      {/* Edit Modal */}
      {showEditModal && (
        <CreateInterfaceModal
          onClose={() => setShowEditModal(false)}
          onInterfaceCreated={handleEditComplete}
          interfaceData={interfaceData}
        />
      )}
    </div>
  );
}
