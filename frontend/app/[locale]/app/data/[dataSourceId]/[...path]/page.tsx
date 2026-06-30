'use client';

import { use } from 'react';
import DataTable from '@/components/DataTable';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import LoadingSpinner from '@/components/LoadingSpinner';

interface Props {
  params: Promise<{ dataSourceId: string; path: string[] }>;
}

export default function NestedDataSourcePage({ params }: Props) {
  const resolvedParams = use(params);
  const { isAuthChecking, isAuthenticated } = useAuthGuard();

  const dataSourceId = Number(resolvedParams.dataSourceId);
  const jsonPath = resolvedParams.path?.join('.') || '';

  if (isAuthChecking) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="xl" />
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

  if (isNaN(dataSourceId)) {
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
      <DataTable
        dataSourceId={dataSourceId}
        jsonPath={jsonPath}
      />
    </div>
  );
}
