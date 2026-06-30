'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import { InterfacePreview } from '@/components/InterfacePreview';
import LoadingSpinner from '@/components/LoadingSpinner';
import { orchestratorApi } from '@/lib/api';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { resolveTemplateWithData, resolveHtmlTemplate } from '@/app/workflows/builder/utils/htmlTemplateResolver';
import { usePublicationSnapshot, getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

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
  isPublic: boolean;
  isActive: boolean;
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

interface InterfacePanelContentProps {
  interfaceId: string;
}

/**
 * Preview-only interface content for the side panel.
 * Extracts the preview logic from the interface detail page.
 */
export function InterfacePanelContent({ interfaceId }: InterfacePanelContentProps) {
  const [interfaceData, setInterfaceData] = useState<InterfaceData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [renderResult, setRenderResult] = useState<RenderResult | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [isResolvingData, setIsResolvingData] = useState(false);

  // Live sync: refresh when agent modifies the interface
  const [refreshKey, setRefreshKey] = useState(0);

  // Snapshot-first resolution: when this panel is rendered inside a publication
  // preview surface (via PublicationPreviewShell), the planSnapshot already
  // carries _snapshot_htmlTemplate / _snapshot_cssTemplate / _snapshot_jsTemplate
  // for every interface referenced by the workflow. We MUST consume those and
  // never call the auth'd /api/interfaces/{id} endpoint, which would either
  // 401 (anonymous) or leak across tenants (publisher viewing own preview).
  const snapshotCtx = usePublicationSnapshot();
  const inPreview = !!getActivePublicPreview();

  useEffect(() => {
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('interfaceModified', handleModified);
    return () => window.removeEventListener('interfaceModified', handleModified);
  }, []);

  // Fetch interface data
  useEffect(() => {
    const fetchInterface = async () => {
      if (!interfaceId) return;

      setIsLoading(true);
      setError(null);

      try {
        // Snapshot path: try the frozen plan first. If the snapshot has the
        // interface (with _snapshot_* prefix marker) we render from it and
        // skip the auth'd metadata call entirely.
        const snap = snapshotCtx?.getInterfaceSnapshot(interfaceId);
        if (snap) {
          setInterfaceData({
            id: interfaceId,
            tenantId: '',
            name: '',
            htmlTemplate: snap.htmlTemplate,
            cssTemplate: snap.cssTemplate,
            jsTemplate: snap.jsTemplate,
            isPublic: true,
            isActive: true,
          });
          return;
        }

        // In a preview but the interface wasn't snapshotted - surface a clear
        // error rather than fall back to the auth'd endpoint, which would leak
        // or 401 (the publish step should always snapshot every referenced
        // interface; if it didn't the publication is malformed).
        if (inPreview) {
          setError('Interface snapshot missing - re-publish the application');
          return;
        }

        const data = await orchestratorApi.getInterface(interfaceId);
        setInterfaceData(data as unknown as InterfaceData);
        if ((data as any).dataSourceId) {
          setIsResolvingData(true);
        }
      } catch (err: any) {
        console.error('Error fetching interface:', err);
        setError('Failed to load interface');
      } finally {
        setIsLoading(false);
      }
    };

    fetchInterface();
  }, [interfaceId, refreshKey, snapshotCtx, inPreview]);

  // Fetch render data if interface has a datasource - only when not in preview;
  // the public showcase endpoint resolves data server-side via /showcase-render.
  useEffect(() => {
    const fetchRenderData = async () => {
      if (!interfaceData?.dataSourceId) {
        setRenderResult(null);
        return;
      }

      try {
        setIsResolvingData(true);
        const result = await orchestratorApi.renderInterfaceWithDatasource(interfaceId, {
          page: currentPage,
          size: 1,
        });
        setRenderResult(result as unknown as RenderResult);
      } catch (err: any) {
        console.error('Failed to fetch render data:', err);
        setRenderResult(null);
      } finally {
        setIsResolvingData(false);
      }
    };

    if (interfaceData?.dataSourceId && !inPreview) {
      fetchRenderData();
    }
  }, [interfaceData, interfaceId, currentPage, refreshKey, inPreview]);

  // Compute resolved HTML template
  const resolvedHtmlTemplate = useMemo(() => {
    if (renderResult?.items?.length > 0) {
      const currentItem = renderResult.items[0];
      if (currentItem.data?._resolvedHtml) {
        return currentItem.data._resolvedHtml as string;
      }
      return resolveTemplateWithData(renderResult.htmlTemplate, currentItem.data);
    }
    return resolveHtmlTemplate(interfaceData?.htmlTemplate || '');
  }, [interfaceData, renderResult]);

  const totalItems = renderResult?.pagination?.totalItems || 0;
  const totalPages = renderResult?.pagination?.totalPages || 0;

  // Epoch nav: page 0 = newest. handleNewer decrements, handleOlder increments.
  const handleNewer = useCallback(() => {
    if (currentPage > 0) {
      setCurrentPage(prev => prev - 1);
    }
  }, [currentPage]);

  const handleOlder = useCallback(() => {
    if (currentPage < totalPages - 1) {
      setCurrentPage(prev => prev + 1);
    }
  }, [currentPage, totalPages]);

  if (isLoading) {
    return (
      <div className="h-full w-full flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error || !interfaceData) {
    return (
      <div className="h-full w-full flex items-center justify-center p-6">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6 max-w-md text-center">
          <p className="text-sm text-theme-secondary">{error || 'Interface not found'}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full h-full relative">
      <InterfacePreview
        htmlTemplate={resolvedHtmlTemplate}
        cssTemplate={renderResult?.cssTemplate || interfaceData?.cssTemplate}
        jsTemplate={renderResult?.jsTemplate || interfaceData?.jsTemplate}
        className="w-full h-full"
        autoFit={false}
        isLoading={isResolvingData}
      />

      {/* Pagination controls */}
      {totalItems > 1 && (
        <div className="absolute top-4 left-4 flex items-center gap-2 bg-white/90 dark:bg-gray-800/90 backdrop-blur-sm rounded-full px-3 py-2 shadow-lg">
          <button
            onClick={handleOlder}
            disabled={currentPage >= totalPages - 1}
            className={`p-1.5 rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors ${currentPage >= totalPages - 1 ? 'opacity-30 cursor-not-allowed' : ''}`}
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300 px-2">
            {totalPages - currentPage} / {totalPages}
          </span>
          <button
            onClick={handleNewer}
            disabled={currentPage === 0}
            className={`p-1.5 rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors ${currentPage === 0 ? 'opacity-30 cursor-not-allowed' : ''}`}
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Items count badge */}
      {interfaceData.dataSourceId && totalItems > 0 && (
        <div className="absolute top-4 right-4">
          <span className="text-xs px-3 py-1.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-full shadow-sm">
            {totalItems} items
          </span>
        </div>
      )}
    </div>
  );
}
