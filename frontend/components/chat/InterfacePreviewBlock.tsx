'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import { Monitor, ChevronLeft, ChevronRight, ExternalLink, X } from 'lucide-react';
import { PreviewActionMenu, ActionIcons } from './PreviewActionMenu';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { SimpleToast } from './SimpleToast';
import { useDeleteFlow } from '@/hooks/useDeleteFlow';
import LoadingSpinner from '@/components/LoadingSpinner';
import { InterfacePreview } from '@/components/InterfacePreview';
import type { RenderMode } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';


interface InterfacePreviewBlockProps {
  interfaceId: string;
  onError?: (error: string) => void;
  onDelete?: () => void;
}

interface InterfaceData {
  id: string;
  name: string;
  description?: string;
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  targetTable?: string;
  dataSourceId?: number;
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
  items: RenderItem[];
  pagination: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
}

export function InterfacePreviewBlock({ interfaceId, onError, onDelete }: InterfacePreviewBlockProps) {
  const t = useTranslations('chat.interfaceBlock');
  const [interfaceData, setInterfaceData] = React.useState<InterfaceData | null>(null);
  const [renderResult, setRenderResult] = React.useState<RenderResult | null>(null);
  const [currentPage, setCurrentPage] = React.useState(0);
  const [loading, setLoading] = React.useState(true);
  const [isResolvingData, setIsResolvingData] = React.useState(false);
  const [isHovered, setIsHovered] = React.useState(false);
  const [notFound, setNotFound] = React.useState(false);

  const sidePanel = useSidePanelSafe();
  const { isDeleted, showDeleteModal, isDeleting, toast, hideToast, handleDeleteClick, handleConfirmDelete, handleCancelDelete } = useDeleteFlow({
    deleteFn: React.useCallback(async () => { await orchestratorApi.deleteInterface(interfaceId); }, [interfaceId]),
    successMessage: t('deleteSuccess'),
    errorMessage: t('deleteError'),
    onDeleted: onDelete,
  });
  const containerRef = React.useRef<HTMLDivElement>(null);

  // Fetch interface data
  React.useEffect(() => {
    async function fetchInterface() {
      try {
        setLoading(true);
        const data = await orchestratorApi.getInterface(interfaceId);
        setInterfaceData(data as InterfaceData);
        // If interface has a datasource, we'll need to resolve data
        if ((data as InterfaceData).dataSourceId) {
          setIsResolvingData(true);
        }
      } catch (err: any) {
        const errorMessage = err.message || '';
        if (errorMessage.includes('404') || errorMessage.toLowerCase().includes('not found')) {
          setNotFound(true);
        } else {
          onError?.(err.message || 'Failed to load interface');
        }
      } finally {
        setLoading(false);
      }
    }

    if (!isDeleted) {
      fetchInterface();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [interfaceId, isDeleted]);

  // Fetch render data if interface has a datasource
  React.useEffect(() => {
    async function fetchRenderData() {
      if (!interfaceData?.dataSourceId) return;

      try {
        setIsResolvingData(true);
        const result = await orchestratorApi.renderInterfaceWithDatasource(interfaceId, {
          page: currentPage,
          size: 1 // One item at a time for preview
        });
        setRenderResult(result as unknown as RenderResult);
      } catch (err: any) {
        console.error('Failed to fetch render data:', err);
      } finally {
        setIsResolvingData(false);
      }
    }

    if (interfaceData?.dataSourceId) {
      fetchRenderData();
    }
  }, [interfaceData, interfaceId, currentPage]);

  // Get resolved item data for run mode
  const itemData = React.useMemo(() => {
    if (renderResult?.items?.length > 0) {
      const currentItem = renderResult.items[0];
      // Check if backend provides _resolvedHtml directly
      if (currentItem.data?._resolvedHtml) {
        return currentItem.data;
      }
      return currentItem.data as Record<string, unknown>;
    }
    return undefined;
  }, [renderResult]);

  // Determine render mode and template
  const htmlTemplate = renderResult?.htmlTemplate || interfaceData?.htmlTemplate || '';
  const cssTemplate = renderResult?.cssTemplate || interfaceData?.cssTemplate || undefined;
  const jsTemplate = renderResult?.jsTemplate || interfaceData?.jsTemplate || undefined;
  const renderMode: RenderMode = itemData && Object.keys(itemData).length > 0 ? 'run' : 'edit';

  const handleOpenInterface = () => {
    window.open(`/app/interface/${interfaceId}`, '_blank');
  };

  const tabId = `interface-${interfaceId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const handleCardClick = () => {
    if (!sidePanel || !interfaceData) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    sidePanel.openTab({
      id: tabId,
      label: interfaceData.name,
      icon: <Monitor className="h-4 w-4" />,
      content: <InterfacePanelContent interfaceId={interfaceId} />,
      preferredWidth: 0.35,
      onDelete: handleDeleteClick,
    });
  };

  const totalItems = renderResult?.pagination?.totalItems || 0;
  const totalPages = renderResult?.pagination?.totalPages || 0;

  // Epoch nav: page 0 = newest. handleNewer decrements, handleOlder increments.
  const handleNewer = () => {
    if (currentPage > 0) {
      setCurrentPage(prev => prev - 1);
    }
  };

  const handleOlder = () => {
    if (currentPage < totalPages - 1) {
      setCurrentPage(prev => prev + 1);
    }
  };

  // Don't render if deleted
  if (isDeleted) {
    return null;
  }

  // Stable shell - always the same DOM structure, content swaps inside
  const hasTemplate = !!interfaceData?.htmlTemplate;
  const showSkeleton = loading;
  const showEmpty = !loading && (notFound || !interfaceData || !hasTemplate);

  return (
    <div
      ref={containerRef}
      className={`relative my-6 isolate${hasTemplate || showSkeleton ? ' cursor-pointer' : ''}`}
      onClick={hasTemplate ? handleCardClick : undefined}
      onMouseEnter={(e) => { e.stopPropagation(); setIsHovered(true); }}
      onMouseLeave={(e) => { e.stopPropagation(); setIsHovered(false); }}
    >
      {/* Active tab overlay */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-xl cursor-pointer">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}

      {/* Preview area - skeleton / empty / thumbnail */}
      <div className="rounded-xl overflow-hidden relative">
        {showSkeleton ? (
          /* Skeleton: matches live preview height */
          <div className="w-full animate-pulse bg-theme-secondary" style={{ height: 400 }} />
        ) : showEmpty ? (
          /* Not found / no template */
          <div className="flex flex-col items-center justify-center text-theme-muted bg-theme-secondary" style={{ height: 400 }}>
            <Monitor className="w-8 h-8 mb-2 opacity-50" />
            <span className="text-sm">{notFound ? t('notFound') : t('noTemplate')}</span>
            {!notFound && (
              <button
                onClick={handleOpenInterface}
                className="mt-3 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-theme-primary bg-theme-secondary hover:bg-theme-tertiary border border-theme rounded transition-colors"
              >
                <ExternalLink className="h-3 w-3" />
                {t('openInterface')}
              </button>
            )}
          </div>
        ) : (
          /* Live preview - native viewport (same primitive as /app/interface/[id]) */
          <div className="relative" style={{ height: 400 }}>
            <div className="absolute inset-0 z-10" />
            <InterfacePreview
              htmlTemplate={htmlTemplate}
              cssTemplate={cssTemplate}
              jsTemplate={jsTemplate}
              resolvedData={itemData}
              mode={renderMode}
              autoFit={false}
              className="w-full h-full"
              isLoading={isResolvingData}
            />
            {isResolvingData && (
              <div className="absolute inset-0 flex items-center justify-center bg-white/50 dark:bg-gray-800/50">
                <LoadingSpinner size="md" />
              </div>
            )}
            {totalItems > 1 && (
              <div
                className={`absolute bottom-3 left-1/2 -translate-x-1/2 z-10 transition-opacity duration-300 ${
                  isHovered ? 'opacity-100' : 'opacity-0 pointer-events-none'
                }`}
              >
                <div className="flex items-center gap-1 text-xs text-theme-secondary bg-white/90 dark:bg-slate-800/90 backdrop-blur-sm rounded-full px-3 py-1.5 shadow-lg border border-theme">
                  <button
                    onClick={handleOlder}
                    disabled={currentPage >= totalPages - 1}
                    className={`p-1 rounded-full hover:bg-theme-secondary transition-colors ${currentPage >= totalPages - 1 ? 'opacity-30 cursor-not-allowed' : ''}`}
                  >
                    <ChevronLeft className="w-3.5 h-3.5" />
                  </button>
                  <span className="px-2 font-medium text-theme-primary">
                    {totalPages - currentPage} / {totalPages}
                  </span>
                  <button
                    onClick={handleNewer}
                    disabled={currentPage === 0}
                    className={`p-1 rounded-full hover:bg-theme-secondary transition-colors ${currentPage === 0 ? 'opacity-30 cursor-not-allowed' : ''}`}
                  >
                    <ChevronRight className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Footer - always visible, shows skeleton text while loading */}
      <div className="flex items-center justify-between px-1 pt-2">
        <div className="flex items-center gap-2 min-w-0 flex-1">
          <Monitor className="w-4 h-4 text-theme-muted shrink-0" />
          {showSkeleton ? (
            <div className="h-4 w-32 bg-theme-tertiary rounded animate-pulse" />
          ) : (
            <>
              <span className="text-sm font-medium text-theme-primary truncate">{interfaceData?.name || 'Interface'}</span>
              {interfaceData?.dataSourceId && (
                <span className="text-xs text-theme-muted shrink-0">
                  {t('itemCount', { count: totalItems })}
                </span>
              )}
            </>
          )}
        </div>
        {!showSkeleton && !showEmpty && (
          <div onClick={e => e.stopPropagation()}>
            <PreviewActionMenu
              items={[
                {
                  id: 'open',
                  label: t('open'),
                  icon: ActionIcons.open,
                  onClick: handleOpenInterface,
                },
                {
                  id: 'delete',
                  label: t('delete'),
                  icon: ActionIcons.delete,
                  onClick: handleDeleteClick,
                  variant: 'danger',
                },
              ]}
            />
          </div>
        )}
      </div>

      {/* Delete confirmation modal */}
      <ConfirmDeleteModal
        isOpen={showDeleteModal}
        title={t('deleteTitle')}
        message={t('deleteConfirm', { name: interfaceData?.name || 'this interface' })}
        onConfirm={handleConfirmDelete}
        onCancel={handleCancelDelete}
        isLoading={isDeleting}
      />

      {/* Toast notification */}
      {toast && (
        <SimpleToast
          type={toast.type}
          message={toast.message}
          isVisible={!!toast}
          onClose={hideToast}
        />
      )}
    </div>
  );
}
