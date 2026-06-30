'use client';

import { DataView } from '@/components/views/DataView';

/**
 * Tables page component
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook
 */
export default function AppTablesPage() {
  return <DataView />;
}
