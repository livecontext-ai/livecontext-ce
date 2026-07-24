'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { ApplicationTabContent, type ApplicationConfig } from './ApplicationTabContent';
import { useRun } from '@/contexts/WorkflowRunContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useInterfacePaginationStore } from '@/lib/stores/interface-pagination-store';

interface ApplicationCarouselProps {
  configs: ApplicationConfig[];
  runId: string | null;
  workflowId?: string;
  onAction: (triggerRef: string, data: Record<string, unknown>) => void;
  /** External request to navigate to a specific interfaceId */
  targetInterfaceId?: string | null;
  onTargetConsumed?: () => void;
  /**
   * Marketplace-preview mode. Forwards to {@link ApplicationTabContent} so it
   * (a) hides the trigger Launch + Continue buttons - anonymous visitors must
   * not be able to fire the workflow against the publisher's tenant, and
   * (b) portals the floating pill toolbar to {@code document.body} so it
   * escapes the preview shell's {@code overflow-hidden}. The expand/collapse
   * fullscreen toggle stays available (its expanded view is a full-viewport
   * {@code document.body} portal, so it works inside the preview shell).
   */
  previewMode?: boolean;
}

export function ApplicationCarousel({
  configs,
  runId,
  workflowId,
  onAction,
  targetInterfaceId,
  onTargetConsumed,
  previewMode = false,
}: ApplicationCarouselProps) {
  const t = useTranslations('chat.carousel');
  const { isRunMode } = useWorkflowMode();
  const [runState] = useRun(isRunMode ? runId || undefined : undefined);

  // Carousel index persisted in Zustand store (survives panel close/open)
  const carouselIndex = useInterfacePaginationStore((s) => s.carouselIndex);
  const setCarouselIndex = useInterfacePaginationStore((s) => s.setCarouselIndex);

  // Clamp index to valid range
  const currentIndex = Math.min(Math.max(carouselIndex, 0), Math.max(configs.length - 1, 0));

  // --- Auto-navigation: detect when workflow reaches a new interface ---
  const lastAutoNavNodeId = React.useRef<string | null>(null);

  const activeInterfaceIndex = React.useMemo(() => {
    if (!runState) return -1;
    // Find last config whose nodeId is running or awaiting signal (most recently reached)
    for (let i = configs.length - 1; i >= 0; i--) {
      const nodeId = configs[i].nodeId;
      if (nodeId && (runState.runningSteps.has(nodeId) || runState.awaitingSignalSteps.has(nodeId))) {
        return i;
      }
    }
    // Fallback: find last completed interface
    for (let i = configs.length - 1; i >= 0; i--) {
      const nodeId = configs[i].nodeId;
      if (nodeId && runState.completedSteps.has(nodeId)) {
        return i;
      }
    }
    return -1;
  }, [configs, runState?.runningSteps, runState?.awaitingSignalSteps, runState?.completedSteps]);

  React.useEffect(() => {
    if (activeInterfaceIndex < 0) return;
    const activeNodeId = configs[activeInterfaceIndex]?.nodeId;
    if (activeNodeId && activeNodeId !== lastAutoNavNodeId.current) {
      lastAutoNavNodeId.current = activeNodeId;
      setCarouselIndex(activeInterfaceIndex);
    }
  }, [activeInterfaceIndex, configs, setCarouselIndex]);


  // --- Open on the ENTRY interface (isEntryInterface) ---
  // Page ORDER stays canvas x-order (multi-page apps read left to right); only the
  // INITIAL page honors the entry flag. One-shot per mount, and only while nothing
  // else has navigated: the index is still the untouched default and no run activity
  // has auto-navigated. A user's remembered position or an auto-nav always wins.
  const didInitEntryIndexRef = React.useRef(false);
  React.useEffect(() => {
    if (didInitEntryIndexRef.current || configs.length === 0) return;
    didInitEntryIndexRef.current = true;
    if (carouselIndex !== 0 || lastAutoNavNodeId.current) return;
    const entryIdx = configs.findIndex(c => c.isEntryInterface);
    if (entryIdx > 0) setCarouselIndex(entryIdx);
  }, [configs, carouselIndex, setCarouselIndex]);

  // --- External navigation request (from workflowOpenApplicationTab event) ---
  React.useEffect(() => {
    if (!targetInterfaceId) return;
    const idx = configs.findIndex(c => c.interfaceId === targetInterfaceId);
    if (idx >= 0) {
      setCarouselIndex(idx);
    }
    onTargetConsumed?.();
  }, [targetInterfaceId, configs, setCarouselIndex, onTargetConsumed]);

  // --- Navigation handlers ---
  const handlePrev = React.useCallback(() => {
    setCarouselIndex(Math.max(currentIndex - 1, 0));
  }, [currentIndex, setCarouselIndex]);

  const handleNext = React.useCallback(() => {
    setCarouselIndex(Math.min(currentIndex + 1, configs.length - 1));
  }, [currentIndex, configs.length, setCarouselIndex]);

  // Fullscreen + toolbar + viewingEpoch state lifted here so they survive carousel navigation (key change)
  const [isExpanded, setIsExpanded] = React.useState(false);
  const [toolbarOpen, setToolbarOpen] = React.useState(false);
  const [viewingEpoch, setViewingEpoch] = React.useState<number | null>(null);

  // Fade-in animation when switching carousel tabs
  const [fadeIn, setFadeIn] = React.useState(true);
  const prevIndexRef = React.useRef(currentIndex);
  React.useEffect(() => {
    if (prevIndexRef.current !== currentIndex) {
      prevIndexRef.current = currentIndex;
      setFadeIn(false);
      // Trigger fade-in on next frame
      requestAnimationFrame(() => {
        requestAnimationFrame(() => setFadeIn(true));
      });
    }
  }, [currentIndex]);

  if (configs.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-theme-secondary text-sm">
        {t('noInterfaces')}
      </div>
    );
  }

  const currentConfig = configs[currentIndex];
  const showNav = configs.length > 1;

  // Determine status indicator per interface
  const getStatus = (config: ApplicationConfig): 'active' | 'completed' | 'idle' => {
    if (!runState || !config.nodeId) return 'idle';
    if (runState.runningSteps.has(config.nodeId) || runState.awaitingSignalSteps.has(config.nodeId)) return 'active';
    if (runState.completedSteps.has(config.nodeId)) return 'completed';
    return 'idle';
  };

  // Build carousel dot controls to inject into InterfaceToolbar
  const carouselControls = showNav ? (
    <div className="flex items-center gap-1">
      {/* Prev arrow */}
      <Button
        onClick={handlePrev}
        disabled={currentIndex === 0}
        variant="ghost"
        size="sm"
        className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
        title={t('previous')}
      >
        <ChevronLeft className="h-4 w-4" />
      </Button>

      {/* Dot indicators */}
      <div className="flex items-center gap-1.5 px-0.5">
        {configs.map((config, i) => {
          const status = getStatus(config);
          return (
            <button
              key={config.interfaceId}
              type="button"
              onClick={() => setCarouselIndex(i)}
              title={config.label}
              className={cn(
                'rounded-full transition-all duration-200',
                i === currentIndex ? 'w-2 h-2' : 'w-1.5 h-1.5',
                i === currentIndex
                  ? status === 'active'
                    ? 'bg-blue-500'
                    : status === 'completed'
                      ? 'bg-emerald-500'
                      : 'bg-slate-400 dark:bg-slate-500'
                  : status === 'active'
                    ? 'bg-blue-400/70 dark:bg-blue-500/60'
                    : status === 'completed'
                      ? 'bg-emerald-400/70 dark:bg-emerald-500/60'
                      : 'bg-slate-300 dark:bg-slate-600',
              )}
            />
          );
        })}
      </div>

      {/* Next arrow */}
      <Button
        onClick={handleNext}
        disabled={currentIndex >= configs.length - 1}
        variant="ghost"
        size="sm"
        className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
        title={t('next')}
      >
        <ChevronRight className="h-4 w-4" />
      </Button>
    </div>
  ) : undefined;

  return (
    <div data-testid="application-carousel" className="flex-1 flex flex-col min-h-0">
      {currentConfig && (
        <div
          className="flex-1 flex flex-col min-h-0"
          style={{
            opacity: fadeIn ? 1 : 0,
            transition: 'opacity 150ms ease-in-out',
          }}
        >
          <ApplicationTabContent
            key={currentConfig.interfaceId}
            config={currentConfig}
            runId={runId}
            workflowId={workflowId}
            onAction={onAction}
            carouselControls={carouselControls}
            isExpanded={isExpanded}
            onExpandedChange={setIsExpanded}
            toolbarOpen={toolbarOpen}
            onToolbarOpenChange={setToolbarOpen}
            viewingEpoch={viewingEpoch}
            onViewingEpochChange={setViewingEpoch}
            previewMode={previewMode}
          />
        </div>
      )}
    </div>
  );
}
