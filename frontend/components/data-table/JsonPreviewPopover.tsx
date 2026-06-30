'use client';

import React, { useLayoutEffect, useRef, useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { Copy, Check, Pin, X, GripVertical } from 'lucide-react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';

export interface JsonPreviewPopoverProps {
  /** L'élément de référence pour le positionnement */
  anchorElement: HTMLElement | null;
  /** Les données JSON à afficher */
  jsonData: any;
  /** Le nom de l'en-tête de colonne */
  headerName?: string;
  /** L'ID de la ligne (optionnel) */
  rowId?: string | number;
  /** Callback appelé quand la popover doit être fermée */
  onClose: () => void;
  /** Délai avant de fermer la popover (en ms) */
  closeDelay?: number;
  /** Ref pour indiquer si on survole la popover (optionnel, géré en interne sinon) */
  isHoveringPopoverRef?: React.MutableRefObject<boolean>;
  /** Callback pour épingler la popover (créer une copie indépendante) */
  onPin?: (data: { jsonData: any; headerName?: string; rowId?: string | number; position: { x: number; y: number } }) => void;
}

export interface ComparisonItem {
  id: string;
  jsonData: any;
  headerName?: string;
  rowId?: string | number;
}

export interface PinnedPopoverData {
  id: string;
  // Legacy fields are optional/derived
  jsonData?: any;
  headerName?: string;
  rowId?: string | number;

  comparisonItems: ComparisonItem[];
  position: { x: number; y: number };
}

/**
 * Composant pour une popover épinglée (indépendante, draggable)
 */
export function PinnedJsonPopover({
  id,
  jsonData,
  headerName,
  rowId,
  comparisonItems,
  position: initialPosition,
  onClose,
  onMerge,
}: PinnedPopoverData & { onClose: (id: string) => void; onMerge?: (sourceId: string, targetId: string) => void }) {
  const t = useTranslations('dataTable');
  const popoverRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState(initialPosition);
  const [copied, setCopied] = useState(false);
  const dragStateRef = useRef<{ isDragging: boolean; startX: number; startY: number; startPosX: number; startPosY: number }>({
    isDragging: false,
    startX: 0,
    startY: 0,
    startPosX: 0,
    startPosY: 0,
  });

  // Calculate items to display (backward compatibility)
  const items: ComparisonItem[] = comparisonItems || [
    { id: 'default', jsonData, headerName, rowId }
  ];

  // Determine width based on number of items
  // 1 item: max-w-md (approx 28rem/448px)
  // 2+ items: wider to accommodate side-by-side
  const containerWidth = items.length > 1 ? 'max-w-4xl w-[800px]' : 'max-w-md w-96';

  // Formater les données JSON pour l'affichage
  const formatJsonData = useCallback((data: any): string => {
    if (data === null || data === undefined) {
      return String(data);
    }
    try {
      return JSON.stringify(data, null, 2);
    } catch (e) {
      return String(data);
    }
  }, []);

  // Copier le JSON dans le presse-papier (copie tout si comparaison)
  const handleCopy = useCallback(async () => {
    // If multiple items, copy as array of objects or just concatenated?
    // Let's copy the first one or create an array if multiple
    const dataToCopy = items.length > 1
      ? items.map(i => ({ header: i.headerName, id: i.rowId, data: i.jsonData }))
      : items[0].jsonData;

    const jsonString = formatJsonData(dataToCopy);
    try {
      await navigator.clipboard.writeText(jsonString);
      setCopied(true);
      setTimeout(() => {
        setCopied(false);
      }, 2000);
    } catch (err) {
      console.error('Error copying to clipboard:', err);
    }
  }, [items, formatJsonData]);

  // State for drop target hint
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);

  // Gérer le drag avec pointer events
  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);

    dragStateRef.current = {
      isDragging: true,
      startX: e.clientX,
      startY: e.clientY,
      startPosX: position.x,
      startPosY: position.y,
    };
  }, [position]);

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!dragStateRef.current.isDragging) return;

    const deltaX = e.clientX - dragStateRef.current.startX;
    const deltaY = e.clientY - dragStateRef.current.startY;

    setPosition({
      x: dragStateRef.current.startPosX + deltaX,
      y: dragStateRef.current.startPosY + deltaY,
    });

    // Check for potential merge target
    if (onMerge) {
      const elements = document.elementsFromPoint(e.clientX, e.clientY);
      const targetPopover = elements.find(el => {
        const foundId = el.getAttribute('data-pinned-popover-id');
        return foundId && foundId !== id;
      });
      const newTargetId = targetPopover?.getAttribute('data-pinned-popover-id') || null;

      setDropTargetId(prev => (prev === newTargetId ? prev : newTargetId));
    }
  }, [id, onMerge]);

  const handlePointerUp = useCallback((e: React.PointerEvent) => {
    if (dragStateRef.current.isDragging && onMerge) {
      // Check for drop on another popover
      // We disable pointer events on ref momentarily to peek through (optional if using elementsFromPoint correctly)
      // actually elementFromPoint returns all elements in z-order if supported, or just top one.
      // document.elementsFromPoint returns array.
      const elements = document.elementsFromPoint(e.clientX, e.clientY);
      const targetPopover = elements.find(el => {
        const foundId = el.getAttribute('data-pinned-popover-id');
        // Prevent merging with self
        return foundId && foundId !== id;
      });

      if (targetPopover) {
        const targetId = targetPopover.getAttribute('data-pinned-popover-id');
        if (targetId) {
          onMerge(id, targetId);
        }
      }
    }

    dragStateRef.current.isDragging = false;
    setDropTargetId(null);
    (e.target as HTMLElement).releasePointerCapture(e.pointerId);
  }, [id, onMerge]);

  return createPortal(
    <div
      ref={popoverRef}
      className="fixed z-[99998]"
      style={{
        left: `${position.x}px`,
        top: `${position.y}px`,
        pointerEvents: 'auto',
      }}
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
      data-pinned-popover="true"
      data-pinned-popover-id={id}
    >
      <div
        className={`bg-white/95 dark:bg-gray-900/95 backdrop-blur-sm border ${dropTargetId ? 'border-black ring-2 ring-black/10 dark:border-white dark:ring-white/10' : 'border-theme-primary/30'} dark:border-theme-primary/30 rounded-[24px] p-4 ${containerWidth} max-h-96 flex flex-col relative select-text shadow-lg transition-all`}
        style={{ pointerEvents: 'auto' }}
      >
        {/* Merge Hint Overlay */}
        {dropTargetId && (
          <div className="absolute inset-x-0 -top-10 flex justify-center z-50 pointer-events-none">
            <div className="bg-black text-white dark:bg-white dark:text-black px-3 py-1 rounded-full text-sm font-semibold shadow-md animate-bounce">
              {t('releaseToMerge')}
            </div>
          </div>
        )}

        {/* Main Header (Global controls) */}
        <div className={`flex-shrink-0 flex items-center gap-2 mb-3 pb-2 border-b border-gray-100 dark:border-gray-800 ${dropTargetId ? 'opacity-50' : ''}`}>
          {/* Drag handle */}
          <div
            className="cursor-grab active:cursor-grabbing p-1 -ml-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800 touch-none"
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
          >
            <GripVertical className="h-4 w-4 text-theme-muted" />
          </div>

          <div className="flex-1 font-semibold text-sm text-theme-primary">
            {items.length > 1 ? t('comparisonMode', { count: items.length }) : t('pinnedItem')}
          </div>

          <div className="flex items-center gap-1 ml-auto">
            <Button
              variant="ghost"
              size="sm"
              onClick={handleCopy}
              className="h-7 w-7 p-0 rounded-lg hover:bg-theme-tertiary"
              title={copied ? t('copied') : t('copyAllJson')}
            >
              {copied ? (
                <Check className="h-4 w-4 text-green-500" />
              ) : (
                <Copy className="h-4 w-4 text-theme-muted" />
              )}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onClose(id)}
              className="h-7 w-7 p-0 rounded-lg hover:bg-red-100 dark:hover:bg-red-900/50"
              title={t('close')}
            >
              <X className="h-4 w-4 text-theme-muted hover:text-red-500" />
            </Button>
          </div>
        </div>

        {/* Content Area - Comparison View or Single Item */}
        <div className={`flex-1 overflow-auto min-h-0 ${dropTargetId ? 'opacity-50' : ''}`}>
          <div className={`h-full ${items.length > 1 ? 'grid grid-flow-col auto-cols-fr gap-4 divide-x divide-gray-100 dark:divide-gray-800' : ''}`}>
            {items.map((item, index) => (
              <div key={item.id || index} className={`flex flex-col min-w-[200px] h-full ${index > 0 ? 'pl-4' : ''}`}>
                <div className="flex-shrink-0 mb-2 mt-1">
                  {item.headerName && (
                    <h3 className="text-sm font-semibold text-theme-primary truncate" title={item.headerName}>
                      {item.headerName}
                    </h3>
                  )}
                  {item.rowId !== undefined && (
                    <div className="text-xs text-theme-muted font-mono truncate">ID: {item.rowId}</div>
                  )}
                </div>
                <div className="flex-1 overflow-auto min-h-0 rounded-lg p-2">
                  <pre className="text-xs text-theme-primary font-mono whitespace-pre-wrap break-words select-text cursor-text">
                    {formatJsonData(item.jsonData)}
                  </pre>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}

/**
 * Composant réutilisable pour afficher une prévisualisation JSON dans une popover
 * avec positionnement intelligent qui s'adapte aux bords de l'écran
 */
export function JsonPreviewPopover({
  anchorElement,
  jsonData,
  headerName,
  rowId,
  onClose,
  closeDelay = 150,
  isHoveringPopoverRef: externalHoveringRef,
  onPin,
}: JsonPreviewPopoverProps) {
  const t = useTranslations('dataTable');
  const popoverRef = useRef<HTMLDivElement>(null);
  const internalHoveringRef = useRef(false);
  const isHoveringPopoverRef = externalHoveringRef || internalHoveringRef;
  const [position, setPosition] = useState<{ x: number; y: number; placement: 'right' | 'left' } | null>(null);
  const [copied, setCopied] = useState(false);

  // Calculer la position avec useLayoutEffect pour éviter le scintillement
  useLayoutEffect(() => {
    if (!anchorElement) {
      setPosition(null);
      return;
    }

    const updatePosition = () => {
      const anchorRect = anchorElement.getBoundingClientRect();
      const popoverWidth = 400;
      const popoverHeight = 300;
      const padding = 8;
      const viewportWidth = window.innerWidth;
      const viewportHeight = window.innerHeight;

      // Position par défaut : à droite de la cellule
      let x = anchorRect.right;
      let y = anchorRect.top;
      let placement: 'right' | 'left' = 'right';

      // Vérifier si ça rentre à droite
      if (x + popoverWidth > viewportWidth - padding) {
        // Essayer à gauche
        const leftX = anchorRect.left - popoverWidth;
        if (leftX >= padding) {
          x = leftX;
          placement = 'left';
        } else {
          x = padding;
          placement = 'left';
        }
      }

      // Ajuster verticalement
      if (y < padding) {
        y = padding;
      } else if (y + popoverHeight > viewportHeight - padding) {
        y = Math.max(padding, viewportHeight - popoverHeight - padding);
      }

      setPosition({ x, y, placement });
    };

    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);

    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [anchorElement]);

  // Gérer le survol de la popover
  const handlePopoverMouseEnter = useCallback(() => {
    isHoveringPopoverRef.current = true;
  }, [isHoveringPopoverRef]);

  const handlePopoverMouseLeave = useCallback(() => {
    isHoveringPopoverRef.current = false;
  }, [isHoveringPopoverRef]);

  // Épingler la popover
  const handlePin = useCallback(() => {
    if (onPin && position) {
      onPin({
        jsonData,
        headerName,
        rowId,
        position: { x: position.x, y: position.y },
      });
      onClose();
    }
  }, [onPin, jsonData, headerName, rowId, position, onClose]);

  // Formater les données JSON pour l'affichage
  const formatJsonData = useCallback((data: any): string => {
    if (data === null || data === undefined) {
      return String(data);
    }
    try {
      return JSON.stringify(data, null, 2);
    } catch (e) {
      return String(data);
    }
  }, []);

  // Copier le JSON dans le presse-papier
  const handleCopy = useCallback(async () => {
    const jsonString = formatJsonData(jsonData);
    try {
      await navigator.clipboard.writeText(jsonString);
      setCopied(true);
      setTimeout(() => {
        setCopied(false);
      }, 2000);
    } catch (err) {
      console.error('Error copying to clipboard:', err);
    }
  }, [jsonData, formatJsonData]);

  if (!anchorElement || !position) {
    return null;
  }

  const popoverContent = (
    <div
      ref={popoverRef}
      className="fixed z-[99999]"
      style={{
        left: `${position.x}px`,
        top: `${position.y}px`,
        pointerEvents: 'auto',
      }}
      onMouseEnter={handlePopoverMouseEnter}
      onMouseLeave={handlePopoverMouseLeave}
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
      onMouseDown={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
      data-json-preview="true"
    >
      <div
        className="bg-white/95 dark:bg-gray-900/95 backdrop-blur-sm border border-gray-200/50 dark:border-gray-700/50 rounded-[24px] p-4 max-w-md max-h-96 flex flex-col relative select-text"
        style={{ pointerEvents: 'auto' }}
      >
        {/* Header fixe */}
        <div className="flex-shrink-0 flex items-center justify-between pr-2 mb-3">
          <div className="flex-1 min-w-0 pr-2">
            {headerName && (
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100 truncate">{headerName}</h3>
            )}
            {rowId !== undefined && (
              <div className="text-xs text-gray-500 font-mono truncate">ID: {rowId}</div>
            )}
          </div>

          <div className="flex items-center gap-1 ml-auto">
            {onPin && (
              <Button
                variant="ghost"
                size="sm"
                onClick={handlePin}
                className="h-7 w-7 p-0 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800"
                title={t('pinCopy')}
              >
                <Pin className="h-4 w-4 text-gray-500" />
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              onClick={handleCopy}
              className="h-7 w-7 p-0 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800"
              title={copied ? t('copied') : t('copyJson')}
            >
              {copied ? (
                <Check className="h-4 w-4 text-green-500" />
              ) : (
                <Copy className="h-4 w-4 text-gray-500" />
              )}
            </Button>
          </div>
        </div>
        {/* Contenu scrollable */}
        <div
          className="flex-1 overflow-auto min-h-0"
          onWheel={(e) => e.stopPropagation()}
          onScroll={(e) => e.stopPropagation()}
        >
          <pre className="text-sm text-gray-700 dark:text-gray-300 font-mono whitespace-pre-wrap break-words select-text cursor-text">
            {formatJsonData(jsonData)}
          </pre>
        </div>
      </div>
    </div>
  );

  return createPortal(popoverContent, document.body);
}
