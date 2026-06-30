'use client';

import { useState, useEffect } from 'react';
import { Presentation } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { orchestratorApi } from '@/lib/api';
import { SlideRenderer } from '@/components/slides/SlideRenderer';
import type { SlideData } from '@/components/slides/SlideRenderer';
import type { Interface } from '@/lib/api/orchestrator/types';

interface SlidePanelContentProps {
  interfaceId: string;
}

/**
 * Slide deck content for the side panel.
 * Fetches interface data and renders the slide deck with navigation.
 */
export function SlidePanelContent({ interfaceId }: SlidePanelContentProps) {
  const [interfaceData, setInterfaceData] = useState<Interface | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const data = await orchestratorApi.getInterface(interfaceId);
        if (!cancelled) setInterfaceData(data);
      } catch (err: any) {
        if (!cancelled) setError(err.message || 'Failed to load');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [interfaceId]);

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
          <Presentation className="w-8 h-8 mx-auto mb-3 text-theme-muted" />
          <p className="text-sm text-theme-secondary">{error || 'Slide deck not found'}</p>
        </div>
      </div>
    );
  }

  const slideData = interfaceData.data as unknown as SlideData | undefined;

  if (!slideData?.slides?.length) {
    return (
      <div className="h-full w-full flex items-center justify-center p-6">
        <div className="text-center">
          <Presentation className="w-8 h-8 mx-auto mb-3 text-theme-muted" />
          <p className="text-sm text-theme-secondary">No slides in this deck</p>
        </div>
      </div>
    );
  }

  return (
    <SlideRenderer slideData={slideData} className="h-full w-full" />
  );
}
