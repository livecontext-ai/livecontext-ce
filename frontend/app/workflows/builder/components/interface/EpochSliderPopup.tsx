'use client';

import * as React from 'react';
import { Play, Pause } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EpochSlider, type EpochTimestampEntry } from './EpochSlider';
import { useSharedInterfacePage, useSharedPlayState } from '@/lib/stores/interface-pagination-store';

interface EpochSliderPopupProps {
  /** Generic scope key for shared pagination state (runId or interfaceId) */
  scopeId: string;
  totalPages: number;
  epochTimestamps?: EpochTimestampEntry[];
  /** Button size variant */
  iconSize?: 'sm' | 'md';
  /** Current viewing epoch from WorkflowModeContext (null = all) */
  viewingEpoch?: number | null;
  /** Callback to sync epoch selection with canvas (null = all) */
  onViewEpoch?: (epoch: number | null) => void;
}

/**
 * Play/pause button with a hover-activated EpochSlider popup.
 * Reads/writes shared page + play state from Zustand.
 * Used in both CanvasToolbar and InterfaceToolbar (ApplicationTabContent).
 */
export function EpochSliderPopup({
  scopeId,
  totalPages,
  epochTimestamps,
  iconSize = 'md',
  viewingEpoch,
  onViewEpoch,
}: EpochSliderPopupProps) {
  const [currentPage, setCurrentPage] = useSharedInterfacePage(scopeId);
  const [isPlaying, setIsPlaying] = useSharedPlayState(scopeId);

  // Map viewingEpoch (number) to page index for the slider
  const effectiveValue = React.useMemo(() => {
    if (viewingEpoch == null || !epochTimestamps) return currentPage;
    const idx = epochTimestamps.findIndex(e => e.epoch === viewingEpoch);
    return idx >= 0 ? idx : currentPage;
  }, [viewingEpoch, epochTimestamps, currentPage]);

  const handleSliderChange = React.useCallback((_v: number) => {
    if (isPlaying) setIsPlaying(false);
  }, [isPlaying, setIsPlaying]);

  const handleSliderCommit = React.useCallback((pageIdx: number) => {
    setCurrentPage(pageIdx);
    // Sync with canvas: map page index to epoch number
    if (onViewEpoch && epochTimestamps?.[pageIdx]?.epoch != null) {
      onViewEpoch(epochTimestamps[pageIdx].epoch!);
    }
  }, [setCurrentPage, onViewEpoch, epochTimestamps]);

  const handleAllSelected = React.useCallback(() => {
    onViewEpoch?.(null);
    // Go to last page (most recent epoch) instead of first
    setCurrentPage(Math.max(0, totalPages - 1));
  }, [onViewEpoch, setCurrentPage, totalPages]);

  // Play: switch to "all" mode so auto-play cycles through all epochs
  const handlePlayToggle = React.useCallback(() => {
    setIsPlaying(prev => {
      const next = !prev;
      if (next && viewingEpoch != null && onViewEpoch) {
        // Starting play while viewing a specific epoch → switch to "all" mode
        onViewEpoch(null);
      }
      return next;
    });
  }, [setIsPlaying, viewingEpoch, onViewEpoch]);

  // Hover state
  const [showSlider, setShowSlider] = React.useState(false);
  const hoverRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleMouseEnter = React.useCallback(() => {
    if (hoverRef.current) clearTimeout(hoverRef.current);
    setShowSlider(true);
  }, []);

  const handleMouseLeave = React.useCallback(() => {
    hoverRef.current = setTimeout(() => setShowSlider(false), 150);
  }, []);

  React.useEffect(() => {
    return () => { if (hoverRef.current) clearTimeout(hoverRef.current); };
  }, []);

  const iconClass = iconSize === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4';
  const hasViewEpochSync = onViewEpoch != null;

  return (
    <div className="relative flex items-center"
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {/* Floating timeline popup - only when multiple epochs exist */}
      {showSlider && totalPages > 1 && (
        <div
          className="absolute bottom-full left-1/2 -translate-x-1/2 mb-3 z-50"
          onMouseEnter={handleMouseEnter}
          onMouseLeave={handleMouseLeave}
        >
          <div className="rounded-2xl bg-white/95 dark:bg-gray-800/95 backdrop-blur shadow-xl border border-slate-200 dark:border-slate-700 px-3 py-2.5 min-w-[min(320px,calc(100vw-48px))]">
            <EpochSlider
              totalPages={totalPages}
              value={effectiveValue}
              onValueChange={handleSliderChange}
              onValueCommit={handleSliderCommit}
              epochTimestamps={epochTimestamps}
              showAllOption={hasViewEpochSync}
              isAllSelected={hasViewEpochSync && viewingEpoch == null}
              onAllSelected={handleAllSelected}
              className="w-full"
            />
          </div>
        </div>
      )}

      {/* Play/pause button */}
      <Button
        onClick={handlePlayToggle}
        variant={isPlaying ? 'default' : 'ghost'}
        size="sm"
        className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
        title={isPlaying ? 'Pause' : 'Play'}
      >
        {isPlaying ? <Pause className={iconClass} /> : <Play className={iconClass} />}
      </Button>
    </div>
  );
}
