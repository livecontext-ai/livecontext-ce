'use client';

import { useState, useEffect, useMemo } from 'react';
import DataTable from '@/components/DataTable';
import { useSharedConversation } from '@/contexts/SharedConversationContext';
import { usePublicationSnapshot } from '@/contexts/PublicationSnapshotContext';
import { snapshotToDataTable } from '@/lib/datatable/snapshot-adapter';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';

interface DataSourcePanelContentProps {
  dataSourceId: string;
  readOnly?: boolean;
}

/**
 * DataSource content for the side panel.
 *
 * In marketplace preview, renders the frozen snapshot from
 * publication.planSnapshot - never hits the publisher's tenant.
 * Otherwise renders the live DataTable, with optional refresh on
 * `dataSourceModified` events from agent tool runs.
 */
export function DataSourcePanelContent({ dataSourceId, readOnly = false }: DataSourcePanelContentProps) {
  const numericId = Number(dataSourceId);
  const [refreshKey, setRefreshKey] = useState(0);
  const isShared = !!useSharedConversation();
  const canMutate = useCanMutateInCurrentOrg();
  const isReadOnly = readOnly || isShared || !canMutate;
  const snapshotCtx = usePublicationSnapshot();
  const snapshot = snapshotCtx?.getDataSourceSnapshot(dataSourceId) ?? null;
  const snapshotData = useMemo(() => (snapshot ? snapshotToDataTable(snapshot) : null), [snapshot]);

  useEffect(() => {
    if (snapshot) return; // Snapshot is frozen - no live refresh
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('dataSourceModified', handleModified);
    return () => window.removeEventListener('dataSourceModified', handleModified);
  }, [snapshot]);

  if (snapshotData) {
    return (
      <div className="h-full w-full p-4 flex flex-col">
        <DataTable snapshotData={snapshotData} readOnly className="h-full" />
      </div>
    );
  }

  if (isNaN(numericId)) {
    return (
      <div className="h-full w-full flex items-center justify-center p-6">
        <div className="bg-theme-secondary border border-theme rounded-lg p-6 max-w-md text-center">
          <p className="text-sm text-theme-secondary">Invalid table ID</p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full w-full p-4 flex flex-col">
      <DataTable key={refreshKey} dataSourceId={numericId} embedded readOnly={isReadOnly} />
    </div>
  );
}
