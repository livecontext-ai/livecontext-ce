'use client';

import { InterfaceView } from '@/components/views/InterfaceView';

/**
 * Interface page component
 * Uses native Next.js routing - view is determined by URL via useCurrentView() hook
 */
export default function AppInterfacePage() {
  return <InterfaceView />;
}
