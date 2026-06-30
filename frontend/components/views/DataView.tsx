'use client';

import DataSourceTable from '@/components/DataSourceTable';
import { AuthenticatedView } from './AuthenticatedView';

/**
 * DataView - Data source list view (table only)
 * Detail view is now handled by /app/data/[dataSourceId]/page.tsx
 *
 * Note: DataSourceTable handles its own loading and empty states,
 * so we don't need to fetch datasources here. This avoids duplicate API calls.
 * We wait for auth to be ready before rendering DataSourceTable to ensure
 * the token is available for API calls.
 */
export function DataView() {
  return (
    // Same width as the Workflows list (AuthenticatedView's default max-w-6xl) so the
    // 3-column table-card grid has the same breathing room - not the tighter max-w-4xl.
    <AuthenticatedView>
      <DataSourceTable />
    </AuthenticatedView>
  );
}
