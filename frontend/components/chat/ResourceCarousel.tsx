'use client';

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { VisualizeBlock } from './VisualizeBlock';
import { useTranslations } from 'next-intl';

export interface CarouselItem {
  vizType: 'workflow' | 'datasource' | 'interface' | 'application' | 'agent' | 'web_search' | 'agent_browse' | 'image_generation' | 'file';
  id: string;
  title?: string;
  /**
   * Live-execution run id (4-field marker `[visualize:application:id:runId]`).
   * MUST be threaded through the carousel - without it, grouped application
   * cards drop back to their frozen publish-time showcase instead of the
   * agent's actual execution run/epoch (the "both cards pinned to the same
   * epoch / live result never shown" bug).
   */
  runId?: string;
}

interface ResourceCarouselProps {
  items: CarouselItem[];
  onDeleteVisualization?: (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface' | 'web_search' | 'agent_browse', id: string) => void;
  onRunWorkflow?: (workflowId: string) => void;
  readOnly?: boolean;
}

/**
 * Carousel for grouping multiple VisualizeBlock items in a single chat message.
 * If only 1 item, renders the VisualizeBlock directly (no carousel chrome).
 */
export function ResourceCarousel({ items, onDeleteVisualization, onRunWorkflow, readOnly }: ResourceCarouselProps) {
  const t = useTranslations('resourceCarousel');
  const [currentIndex, setCurrentIndex] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);

  // Clamp index if items shrink (streaming)
  const safeIndex = Math.min(currentIndex, Math.max(items.length - 1, 0));

  // When new items appear during streaming, stay on current unless user hasn't navigated
  useEffect(() => {
    if (currentIndex >= items.length && items.length > 0) {
      setCurrentIndex(items.length - 1);
    }
  }, [items.length, currentIndex]);

  const handlePrev = useCallback(() => {
    setCurrentIndex(i => Math.max(i - 1, 0));
  }, []);

  const handleNext = useCallback(() => {
    setCurrentIndex(i => Math.min(i + 1, items.length - 1));
  }, [items.length]);

  // Keyboard navigation when focused
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      handlePrev();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      handleNext();
    }
  }, [handlePrev, handleNext]);

  // Single item: render directly, no carousel
  if (items.length <= 1) {
    const item = items[0];
    if (!item) return null;
    return (
      <div className="not-prose">
        <VisualizeBlock
          type={item.vizType}
          id={item.id}
          title={item.title}
          runId={item.runId}
          onDelete={onDeleteVisualization}
          onRunWorkflow={onRunWorkflow}
          readOnly={readOnly}
        />
      </div>
    );
  }

  const current = items[safeIndex];

  return (
    <div
      ref={containerRef}
      className="not-prose"
      onKeyDown={handleKeyDown}
      tabIndex={0}
      role="region"
      aria-label={t('resourcesCreated', { count: items.length })}
      aria-roledescription="carousel"
    >
      {/* Current slide */}
      <VisualizeBlock
        type={current.vizType}
        id={current.id}
        title={current.title}
        runId={current.runId}
        onDelete={onDeleteVisualization}
        onRunWorkflow={onRunWorkflow}
        readOnly={readOnly}
      />

      {/* Bottom-centered pagination */}
      <div className="flex items-center justify-center gap-1 mt-2">
        {/* Prev arrow */}
        <Button
          onClick={handlePrev}
          disabled={safeIndex === 0}
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title={t('previous')}
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>

        {/* Dot indicators */}
        <div className="flex items-center gap-1.5 px-0.5">
          {items.map((item, i) => (
            <button
              key={`${item.vizType}-${item.id}-${item.runId ?? i}`}
              type="button"
              onClick={() => setCurrentIndex(i)}
              title={item.title || `${item.vizType} ${i + 1}`}
              className={cn(
                'rounded-full transition-all duration-200',
                i === safeIndex
                  ? 'w-2 h-2 bg-slate-500 dark:bg-slate-400'
                  : 'w-1.5 h-1.5 bg-slate-300 dark:bg-slate-600',
              )}
            />
          ))}
        </div>

        {/* Next arrow */}
        <Button
          onClick={handleNext}
          disabled={safeIndex >= items.length - 1}
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title={t('next')}
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}
