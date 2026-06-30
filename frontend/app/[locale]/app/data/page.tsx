'use client';

import { DataView } from '@/components/views/DataView';

/**
 * Data page component
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook
 */
export default function AppDataPage() {
  return <DataView />;
}
