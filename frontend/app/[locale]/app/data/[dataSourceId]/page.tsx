'use client';

import { use, useEffect, useState } from 'react';
import DataTable from '@/components/DataTable';
import { useAuthGuard } from '@/hooks/useAuthGuard';

interface Props {
  params: Promise<{ dataSourceId: string }>;
}

export default function DataSourceDetailPage({ params }: Props) {
  const { dataSourceId } = use(params);
  const { isAuthChecking, isAuthenticated } = useAuthGuard();

  // Live sync: when the LLM mutates this datasource via chat (StreamingContext
  // dispatches `dataSourceModified` on table tool completion), remount the
  // DataTable so an already-open page reflects the change without a manual
  // reload. Mirrors DataSourcePanelContent's refreshKey idiom - the bare
  // <DataTable> rendered here does not self-listen for this event.
  const [refreshKey, setRefreshKey] = useState(0);
  useEffect(() => {
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('dataSourceModified', handleModified);
    return () => window.removeEventListener('dataSourceModified', handleModified);
  }, []);

  // Use isAuthChecking (OIDC native loading) for faster UI rendering
  if (isAuthChecking) {
    return (
      <div className="h-full w-full p-6">
        <div className="space-y-6 animate-pulse">
          <div className="flex items-center justify-between mb-6">
            <div className="h-8 bg-theme-secondary rounded w-1/3"></div>
            <div className="flex gap-2">
              <div className="h-10 bg-theme-secondary rounded w-24"></div>
              <div className="h-10 bg-theme-secondary rounded w-24"></div>
            </div>
          </div>
          <div className="bg-theme-secondary rounded-lg border border-theme p-6">
            <div className="flex gap-4 mb-4">
              <div className="h-10 bg-theme-tertiary rounded flex-1"></div>
              <div className="h-10 bg-theme-tertiary rounded w-32"></div>
            </div>
            <div className="space-y-3">
              {[...Array(8)].map((_, i) => (
                <div key={i} className="flex items-center gap-4 py-3 border-b border-theme last:border-0">
                  <div className="h-4 bg-theme-tertiary rounded w-8"></div>
                  <div className="h-4 bg-theme-tertiary rounded flex-1"></div>
                  <div className="h-4 bg-theme-tertiary rounded w-24"></div>
                  <div className="h-4 bg-theme-tertiary rounded w-32"></div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="p-6">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6">
          <h2 className="text-xl font-semibold text-theme-primary mb-2">Unauthorized</h2>
          <p className="text-theme-secondary">Sign in to view this datasource.</p>
        </div>
      </div>
    );
  }

  const numericId = Number(dataSourceId);
  if (isNaN(numericId)) {
    return (
      <div className="p-6">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6">
          <h2 className="text-xl font-semibold text-theme-primary mb-2">Invalid DataSource</h2>
          <p className="text-theme-secondary">The datasource ID is invalid.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full w-full p-6 flex flex-col">
      <DataTable key={refreshKey} dataSourceId={numericId} />
    </div>
  );
}
