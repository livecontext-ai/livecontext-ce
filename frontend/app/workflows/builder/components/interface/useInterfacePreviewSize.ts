'use client';

import * as React from 'react';
import type { ContentSize } from './InterfaceIframe';

export interface UseInterfacePreviewSizeOptions {
  /** Max width constraint (default: 800) */
  maxWidth?: number;
  /** Max height constraint (default: 600) */
  maxHeight?: number;
  /** Default width when no measurement and no saved dimensions */
  defaultWidth?: number;
  /** Default height when no measurement and no saved dimensions */
  defaultHeight?: number;
  /** Saved width from persistence (e.g., interfaceData.previewWidth) */
  savedWidth?: number;
  /** Saved height from persistence (e.g., interfaceData.previewHeight) */
  savedHeight?: number;
  /** Whether this preview is currently active/visible */
  isActive?: boolean;
  /** @deprecated No longer used - resize is handled by NodeResizer */
  resizable?: boolean;
  /** @deprecated No longer used - resize is handled by NodeResizer */
  onManualResize?: (width: number, height: number) => void;
}

export interface UseInterfacePreviewSizeResult {
  /** Computed display width - use for container sizing */
  displayWidth: number;
  /** Computed display height - use for container sizing */
  displayHeight: number;
  /** Pass this to InterfaceIframe.onSizeChange for auto-sizing */
  handleSizeChange: (size: ContentSize) => void;
  /** Whether user has manually resized (suppresses auto-sizing) */
  hasManuallyResized: boolean;
  /** Reset sizing state (e.g., when preview mode toggles) */
  resetSize: () => void;
}

/**
 * Centralized hook for interface preview sizing.
 *
 * Handles auto-fit logic using InterfaceIframe's onSizeChange callback.
 * Manual resize is now handled by NodeResizer (ResizableNodeWrapper).
 */
export function useInterfacePreviewSize({
  maxWidth = 800,
  maxHeight = 600,
  defaultWidth = 400,
  defaultHeight = 300,
  savedWidth,
  savedHeight,
  isActive = true,
}: UseInterfacePreviewSizeOptions = {}): UseInterfacePreviewSizeResult {
  // Auto-measured content size (from InterfaceIframe.onSizeChange)
  const [contentSize, setContentSize] = React.useState<ContentSize | null>(null);

  // Check if saved dimensions exist (user previously resized and persisted)
  const hasCustomDimensions = savedWidth != null || savedHeight != null;

  // Track if user has manually resized (prevents auto-sizing override)
  const hasManuallyResizedRef = React.useRef(hasCustomDimensions);

  // Sync ref when custom dimensions are loaded from persistence
  if (hasCustomDimensions && !hasManuallyResizedRef.current) {
    hasManuallyResizedRef.current = true;
  }

  // Reset content size when becoming active (unless manually resized)
  React.useEffect(() => {
    if (isActive && !hasManuallyResizedRef.current) {
      setContentSize(null);
    }
  }, [isActive]);

  // Reset manual resize flag when becoming inactive
  React.useEffect(() => {
    if (!isActive) {
      hasManuallyResizedRef.current = hasCustomDimensions;
    }
  }, [isActive, hasCustomDimensions]);

  // Auto-fit callback: applies constraints while preserving aspect ratio
  const handleSizeChange = React.useCallback((size: ContentSize) => {
    if (hasManuallyResizedRef.current) return;

    const aspectRatio = size.width / size.height;
    let width = size.width;
    let height = size.height;

    if (width > maxWidth) {
      width = maxWidth;
      height = width / aspectRatio;
    }
    if (height > maxHeight) {
      height = maxHeight;
      width = height * aspectRatio;
    }

    setContentSize({ width: Math.round(width), height: Math.round(height) });
  }, [maxWidth, maxHeight]);

  // Compute display dimensions: measured > saved > default
  const displayWidth = contentSize?.width || savedWidth || defaultWidth;
  const displayHeight = contentSize?.height || savedHeight || defaultHeight;

  // Reset sizing state
  const resetSize = React.useCallback(() => {
    setContentSize(null);
    hasManuallyResizedRef.current = false;
  }, []);

  return {
    displayWidth,
    displayHeight,
    handleSizeChange,
    hasManuallyResized: hasManuallyResizedRef.current,
    resetSize,
  };
}
