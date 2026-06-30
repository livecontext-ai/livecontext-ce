'use client';

import { FilesView } from '@/components/views/FilesView';

/**
 * Files page component.
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook.
 */
export default function AppFilesPage() {
  return <FilesView />;
}
