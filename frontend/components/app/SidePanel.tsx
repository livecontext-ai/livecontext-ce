'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { X, GripVertical, Pin, MoreVertical, ExternalLink, Trash2, PanelRightOpen } from 'lucide-react';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { TooltipProvider } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';
import { useMouseResize } from '@/hooks/useMouseResize';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { PanelResizeHandle } from '@/components/ui/PanelResizeHandle';
import { AddTabPicker } from '@/components/app/AddTabPicker';
import { useSharedConversation } from '@/contexts/SharedConversationContext';
import { orchestratorApi } from '@/lib/api';

/** Derive a full-page URL from a tab ID, or null if the resource has no dedicated page. */
function getTabResourceUrl(tabId: string): string | null {
  // workflow_run tabs: "workflow-run-{workflowId}-{runId}" → must be checked BEFORE generic workflow-
  if (tabId.startsWith('workflow-run-')) {
    // Extract workflowId and runId from "workflow-run-{workflowId}-{runId}"
    const rest = tabId.slice('workflow-run-'.length);
    // workflowId is a UUID (36 chars), runId is the remainder after the next dash
    const uuidLen = 36;
    const workflowId = rest.slice(0, uuidLen);
    const runId = rest.slice(uuidLen + 1); // skip the dash separator
    if (workflowId && runId) {
      return `/app/workflow/${workflowId}/run/${runId}`;
    }
    return `/app/workflow/${workflowId}`;
  }
  if (tabId.startsWith('workflow-')) return `/app/workflow/${tabId.slice('workflow-'.length)}`;
  if (tabId.startsWith('interface-')) return `/app/interface/${tabId.slice('interface-'.length)}`;
  if (tabId.startsWith('application-')) return `/app/applications/${tabId.slice('application-'.length)}`;
  if (tabId.startsWith('datasource-')) return `/app/data/${tabId.slice('datasource-'.length)}`;
  if (tabId.startsWith('agent-')) return `/app/agent`;
  return null;
}

/**
 * SidePanel - the unified right panel for the entire app.
 *
 * Lives in the app layout as a flex sibling of the main content area.
 * When open, the main area naturally shrinks (no marginRight hacks).
 * Content is lazy-rendered: nothing is mounted until the panel opens.
 *
 * Tabs are managed via SidePanelContext - each page registers its own tabs.
 */
export function SidePanel() {
  const t = useTranslations('common');
  const ctx = useSidePanelSafe();
  const isSharedMode = !!useSharedConversation();

  const [panelWidth, setPanelWidth] = useState(0);
  const panelRef = useRef<HTMLDivElement>(null);
  const isMobile = useMobileDetection();

  // Dock position preference (Settings > Preferences). 'bottom' only takes effect on
  // desktop - on mobile the panel keeps its fixed full-screen overlay regardless.
  const { position } = useSidePanelLayoutSafe();
  const isBottom = position === 'bottom' && !isMobile;

  // Dispatch fitView so the workflow canvas recenters after the panel size changes
  const dispatchFitView = useCallback(() => {
    window.dispatchEvent(new CustomEvent('workflowViewFitView', {
      detail: { animated: true },
    }));
  }, []);

  // Resize via shared hook - trigger fitView when manual resize ends. In bottom mode we
  // resize HEIGHT (y axis) from the top edge; otherwise WIDTH (x axis) from the left edge.
  const resizeOptions = React.useMemo(
    () => ({ onResizeEnd: dispatchFitView, axis: (isBottom ? 'y' : 'x') as 'x' | 'y', minWidth: isBottom ? 200 : undefined }),
    [dispatchFitView, isBottom],
  );
  const { isResizing, startResize, hasManuallyResizedRef } = useMouseResize(setPanelWidth, resizeOptions);

  // Lazy rendering: track whether content has been mounted at least once
  const [hasBeenOpened, setHasBeenOpened] = useState(false);

  const isOpen = ctx?.isOpen ?? false;
  const tabs = ctx?.tabs ?? [];
  const activeTabId = ctx?.activeTabId ?? null;

  const activeTab = tabs.find(t => t.id === activeTabId) || tabs[0] || null;

  // Mark as opened for lazy mounting
  useEffect(() => {
    if (isOpen && !hasBeenOpened) {
      setHasBeenOpened(true);
    }
  }, [isOpen, hasBeenOpened]);

  // Default size along the active axis. Right mode: width from the tab's preferredWidth
  // (or 35%), clamped to [320, 70%]. Bottom mode: height at 40% of the viewport, clamped
  // to [240, 70%] (preferredWidth is a width fraction, so it is not reused for height).
  const calculatePanelWidth = useCallback((preferred?: number) => {
    if (typeof window === 'undefined') return isBottom ? 360 : 384;
    if (isBottom) {
      const screenHeight = window.innerHeight;
      const maxHeight = Math.floor(screenHeight * 0.7);
      const minHeight = 240;
      return Math.max(minHeight, Math.min(maxHeight, Math.floor(screenHeight * 0.4)));
    }
    const screenWidth = window.innerWidth;
    if (screenWidth < 768) return screenWidth;
    const fraction = preferred || 0.35;
    const maxWidth = Math.floor(screenWidth * 0.7);
    const minWidth = 320;
    const calculated = Math.floor(screenWidth * fraction);
    return Math.max(minWidth, Math.min(maxWidth, calculated));
  }, [isBottom]);

  // Read latest panelWidth from a ref so the window-resize effect doesn't
  // re-attach on every drag tick.
  const panelWidthRef = useRef(panelWidth);
  panelWidthRef.current = panelWidth;

  // Constrain on window resize
  useEffect(() => {
    if (!isOpen) return;
    const handleResize = () => {
      if (isResizing) return; // never fight an active drag
      if (hasManuallyResizedRef.current) {
        const maxExtent = (isBottom ? window.innerHeight : window.innerWidth) * 0.7;
        if (panelWidthRef.current > maxExtent) setPanelWidth(maxExtent);
        return;
      }
      setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [isOpen, calculatePanelWidth, activeTab?.preferredWidth, isResizing, isBottom]);

  // Switching dock position (right <-> bottom) invalidates the stored px size, which is
  // axis-specific. Drop any manual resize and recompute the default for the new axis.
  useEffect(() => {
    hasManuallyResizedRef.current = false;
    if (isOpen) setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
    // Recompute only when the axis flips - not on every tab/size change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isBottom]);

  // Sync width with open/close
  useEffect(() => {
    if (isResizing) return; // never overwrite an active drag
    const targetWidth = calculatePanelWidth(activeTab?.preferredWidth);
    if (isOpen && panelWidthRef.current === 0) {
      setPanelWidth(targetWidth);
    } else if (!isOpen && panelWidthRef.current > 0) {
      setPanelWidth(0);
    }
  }, [isOpen, calculatePanelWidth, activeTab?.preferredWidth, isResizing]);

  // Resize when switching to a tab with a different preferredWidth (unless manually resized)
  useEffect(() => {
    if (!isOpen || hasManuallyResizedRef.current || isResizing) return;
    setPanelWidth(calculatePanelWidth(activeTab?.preferredWidth));
  }, [activeTabId, activeTab?.preferredWidth, isOpen, calculatePanelWidth, isResizing]);

  // Reset manual resize flag when panel closes
  useEffect(() => {
    if (!isOpen) {
      hasManuallyResizedRef.current = false;
    }
  }, [isOpen]);

  // Trigger fitView after open/close CSS transition ends
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const handleTransitionEnd = (e: TransitionEvent) => {
      if (e.propertyName === 'width' || e.propertyName === 'height') {
        dispatchFitView();
      }
    };
    panel.addEventListener('transitionend', handleTransitionEnd);
    return () => panel.removeEventListener('transitionend', handleTransitionEnd);
  }, [dispatchFitView]);

  const router = useRouter();

  // ── Drag-to-reorder state ──
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);
  const [dropTargetIndex, setDropTargetIndex] = useState<number | null>(null);

  // ── Tab context menu (3-dot) state ──
  const [openMenuTabId, setOpenMenuTabId] = useState<string | null>(null);

  // ── Delete confirmation modal state ──
  const [pendingDeleteTab, setPendingDeleteTab] = useState<{ id: string; label: string; handler: () => void } | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const tSidePanel = useTranslations('sidePanel');

  if (!ctx) return null;

  const { setActiveTab, removeTab, moveTab, close, isPeeking, dismissPeek, openTab } = ctx;

  const handleCloseTab = (tabId: string) => {
    const tab = tabs.find(t => t.id === tabId);
    if (tab?.pinned) return;
    const remaining = tabs.filter(t => t.id !== tabId);
    removeTab(tabId);
    if (remaining.length === 0) {
      close();
    }
  };

  /** Build the actual API delete + tab cleanup function for a tab */
  const buildDeleteAction = (tab: typeof tabs[number]): (() => Promise<void>) | undefined => {
    const id = tab.id;
    const closeTabAfterDelete = () => { removeTab(id); if (tabs.length <= 1) close(); };

    if (tab.onDelete) {
      return async () => { tab.onDelete!(); closeTabAfterDelete(); };
    }
    if (id.startsWith('workflow-')) {
      const resourceId = id.slice('workflow-'.length);
      return async () => { await orchestratorApi.deleteWorkflow(resourceId); closeTabAfterDelete(); };
    }
    if (id.startsWith('interface-')) {
      const resourceId = id.slice('interface-'.length);
      return async () => { await orchestratorApi.deleteInterface(resourceId); closeTabAfterDelete(); };
    }
    if (id.startsWith('datasource-')) {
      const resourceId = id.slice('datasource-'.length);
      return async () => { await orchestratorApi.deleteDataSource(resourceId); closeTabAfterDelete(); };
    }
    if (id.startsWith('agent-')) {
      const resourceId = id.slice('agent-'.length);
      return async () => { await orchestratorApi.deleteAgent(resourceId); closeTabAfterDelete(); };
    }
    return undefined;
  };

  /** Resolve the delete handler for a tab - opens confirmation modal instead of deleting immediately */
  const getTabDeleteHandler = (tab: typeof tabs[number]): (() => void) | undefined => {
    const action = buildDeleteAction(tab);
    if (!action) return undefined;
    return () => {
      setPendingDeleteTab({ id: tab.id, label: tab.label, handler: action });
    };
  };

  /** Resolve the i18n delete title based on tab ID prefix */
  const getDeleteTitle = (tabId: string): string => {
    if (tabId.startsWith('workflow-')) return tSidePanel('deleteWorkflowTitle');
    if (tabId.startsWith('interface-')) return tSidePanel('deleteInterfaceTitle');
    if (tabId.startsWith('datasource-')) return tSidePanel('deleteDatasourceTitle');
    if (tabId.startsWith('agent-')) return tSidePanel('deleteAgentTitle');
    return t('delete');
  };

  /** Resolve the i18n delete confirm message based on tab ID prefix */
  const getDeleteMessage = (tabId: string, label: string): string => {
    if (tabId.startsWith('workflow-')) return tSidePanel('deleteWorkflowConfirm', { name: label });
    if (tabId.startsWith('interface-')) return tSidePanel('deleteInterfaceConfirm', { name: label });
    if (tabId.startsWith('datasource-')) return tSidePanel('deleteDatasourceConfirm', { name: label });
    if (tabId.startsWith('agent-')) return tSidePanel('deleteAgentConfirm', { name: label });
    return tSidePanel('deleteWorkflowConfirm', { name: label });
  };

  const confirmDelete = async () => {
    if (!pendingDeleteTab) return;
    setIsDeleting(true);
    try {
      await pendingDeleteTab.handler();
    } finally {
      setIsDeleting(false);
      setPendingDeleteTab(null);
    }
  };

  // ── Drag handlers ──
  const handleDragStart = (e: React.DragEvent, index: number) => {
    setDraggedIndex(index);
    e.dataTransfer.effectAllowed = 'move';
    // Make the drag image semi-transparent
    if (e.currentTarget instanceof HTMLElement) {
      e.dataTransfer.setDragImage(e.currentTarget, e.nativeEvent.offsetX, e.nativeEvent.offsetY);
    }
  };

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (draggedIndex === null || draggedIndex === index) {
      setDropTargetIndex(null);
      return;
    }
    setDropTargetIndex(index);
  };

  const handleDrop = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (draggedIndex !== null && draggedIndex !== index) {
      moveTab(draggedIndex, index);
    }
    setDraggedIndex(null);
    setDropTargetIndex(null);
  };

  const handleDragEnd = () => {
    setDraggedIndex(null);
    setDropTargetIndex(null);
  };
  const currentWidth = isOpen && panelWidth === 0 ? calculatePanelWidth(activeTab?.preferredWidth) : panelWidth;
  const isVisible = currentWidth > 50;

  // Handle peek indicator click - open the panel with the pending tab
  const handlePeekClick = useCallback(() => {
    dismissPeek();
    if (activeTab) {
      openTab(activeTab);
    } else {
      ctx.open();
    }
  }, [dismissPeek, openTab, activeTab, ctx]);

  // Auto-dismiss peek when panel opens
  useEffect(() => {
    if (isOpen && isPeeking) {
      dismissPeek();
    }
  }, [isOpen, isPeeking, dismissPeek]);

  return (
    // 2s hover delay for every tooltip rendered inside the right side panel
    // (tab tooltips, inspector field hints, panel content tooltips). Descendant
    // <TooltipProvider> wrappers still override on a case-by-case basis.
    <TooltipProvider delayDuration={2000} skipDelayDuration={0}>
      {/* Mobile peek indicator - slides in from right edge when panel has deferred content */}
      {isMobile && isPeeking && !isOpen && (
        <button
          type="button"
          onClick={handlePeekClick}
          className="fixed right-0 top-1/2 -translate-y-1/2 z-[42] flex items-center gap-1.5 pl-3 pr-2 py-3 rounded-l-xl bg-theme-primary border border-r-0 border-theme shadow-lg animate-[peekSlideIn_0.4s_ease-out_forwards,peekPulse_2s_ease-in-out_0.5s_2]"
        >
          <PanelRightOpen className="h-4 w-4 text-theme-primary" />
          {activeTab && (
            <span className="text-xs font-medium text-theme-primary max-w-[80px] truncate">
              {activeTab.label}
            </span>
          )}
        </button>
      )}

      {/* Mobile overlay - below panel (z-[38]) but above main content */}
      {isMobile && isOpen && (
        <div className="fixed inset-0 bg-black/50 z-[38] md:hidden" onClick={close} />
      )}

      {/* Resize handle - left edge (right dock) or top edge (bottom dock) */}
      {!isMobile && isOpen && isVisible && (
        <PanelResizeHandle
          panelWidth={currentWidth}
          isResizing={isResizing}
          onResizeStart={startResize}
          orientation={isBottom ? 'bottom' : 'right'}
        />
      )}

      {/* Full-viewport overlay during resize - neutralizes iframes / ReactFlow
       *  / any child element that would otherwise swallow mousemove/mouseup
       *  and leave the panel stuck to the cursor. */}
      {isResizing && (
        <div
          className="fixed inset-0 z-[99]"
          style={{ cursor: isBottom ? 'ns-resize' : 'ew-resize' }}
          aria-hidden="true"
        />
      )}

      {/* Panel container - flex sibling on desktop, fixed overlay on mobile */}
      <div
        ref={panelRef}
        className={cn(
          'bg-theme-primary overflow-hidden flex-shrink-0 border-theme',
          isBottom ? 'w-full border-t' : 'border-l',
          isMobile && 'fixed right-0 top-0 h-full z-[40]',
        )}
        style={{
          // Bottom dock resizes HEIGHT (full width); right dock resizes WIDTH (full height).
          ...(isBottom
            ? { height: `${currentWidth}px`, transition: isResizing ? 'none' : 'height 0.3s ease-in-out' }
            : { width: `${currentWidth}px`, transition: isResizing ? 'none' : 'width 0.3s ease-in-out' }),
          // Safe area insets for notch devices
          ...(isMobile ? {
            paddingTop: 'env(safe-area-inset-top, 0px)',
            paddingBottom: 'env(safe-area-inset-bottom, 0px)',
          } : {}),
        }}
      >
        {/* Render panel internals when visible OR when keepMounted tabs exist.
         *  keepMounted tabs stay in the React tree at all times (SSE, ReactFlow, etc.)
         *  - only their CSS visibility is toggled. */}
        {((hasBeenOpened && isVisible) || tabs.some(t => t.keepMounted)) && (
          <div className="h-full flex flex-col relative min-w-0 overflow-hidden">
            {/* Tab bar - only when panel is visible */}
            {isVisible && (
              <div className="flex-shrink-0 border-b border-theme">
                <div className="flex items-stretch h-14 pt-1.5">
                  {/* Scrollable tab area */}
                  <div className="flex-1 min-w-0 flex items-stretch gap-0 pl-2 overflow-x-auto overflow-y-hidden">
                    {tabs.map((tab, index) => {
                      const isActive = tab.id === activeTabId;
                      const isDragged = draggedIndex === index;
                      const isDropTarget = dropTargetIndex === index;
                      return (<React.Fragment key={tab.id}>
                        <button
                          type="button"
                          draggable={tabs.length > 1}
                          onClick={() => setActiveTab(tab.id)}
                          onDragStart={(e) => handleDragStart(e, index)}
                          onDragOver={(e) => handleDragOver(e, index)}
                          onDrop={(e) => handleDrop(e, index)}
                          onDragEnd={handleDragEnd}
                          className={cn(
                            'group relative min-w-0 max-w-[200px] flex items-center gap-1.5 pl-3 pr-6 text-sm transition-colors whitespace-nowrap flex-shrink-0',
                            isActive
                              ? 'text-theme-primary font-medium bg-theme-primary rounded-t-xl -mb-px z-10 shadow-[0_-1px_3px_rgba(0,0,0,0.06)] browser-tab-active'
                              : 'text-theme-secondary hover:text-theme-primary rounded-t-lg mx-0.5 hover:bg-theme-secondary/40',
                            isDragged && 'opacity-40',
                          )}
                          style={isDropTarget ? {
                            boxShadow: draggedIndex !== null && draggedIndex < index
                              ? 'inset -2px 0 0 0 var(--color-primary, #6366f1)'
                              : 'inset 2px 0 0 0 var(--color-primary, #6366f1)',
                          } : undefined}
                        >
                          {/* Shimmer effect */}
                          {tab.shimmer && !isActive && (
                            <span
                              className="absolute inset-0 rounded-t-lg pointer-events-none overflow-hidden"
                              style={{
                                backgroundImage: `linear-gradient(90deg, transparent 0%, ${
                                  tab.shimmerColor || 'rgba(59, 130, 246, 0.15)'
                                } 50%, transparent 100%)`,
                                backgroundSize: '200% 100%',
                                animation: 'shimmer-scan 4s ease-in-out infinite',
                              }}
                            />
                          )}
                          {/* Drag handle - overlays on hover, takes no space */}
                          {tabs.length > 1 && (
                            <GripVertical className="h-3 w-3 absolute left-1/2 -translate-x-1/2 top-0.5 rotate-90 opacity-0 group-hover:opacity-50 cursor-grab transition-opacity" />
                          )}
                          <span className="flex-shrink-0">{tab.icon}</span>
                          <span className="truncate">{tab.label}</span>
                          {(() => {
                            const deleteHandler = tab.pinned || isSharedMode ? undefined : getTabDeleteHandler(tab);
                            const hasNavUrl = !isSharedMode && getTabResourceUrl(tab.id);
                            const showMenu = !tab.pinned && (hasNavUrl || deleteHandler);

                            if (tab.pinned) {
                              return <Pin className="h-2.5 w-2.5 ml-0.5 text-slate-400 dark:text-slate-500 rotate-45 flex-shrink-0" />;
                            }

                            if (showMenu) {
                              return (
                                <Popover open={openMenuTabId === tab.id} onOpenChange={(open) => setOpenMenuTabId(open ? tab.id : null)}>
                                  <PopoverTrigger asChild>
                                    <span
                                      role="button"
                                      tabIndex={0}
                                      onClick={(e) => e.stopPropagation()}
                                      className="absolute right-1 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 focus:opacity-100 active:opacity-100 hover:bg-theme-secondary rounded p-1 transition-opacity z-20"
                                    >
                                      <MoreVertical className="h-3 w-3" />
                                    </span>
                                  </PopoverTrigger>
                                  <PopoverContent
                                    align="start"
                                    sideOffset={5}
                                    className="w-auto min-w-[160px] p-1.5 bg-theme-primary rounded-xl border border-theme shadow-lg"
                                    onClick={(e) => e.stopPropagation()}
                                  >
                                    {hasNavUrl && (
                                      <button
                                        type="button"
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          setOpenMenuTabId(null);
                                          router.push(getTabResourceUrl(tab.id)!);
                                        }}
                                        className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                                      >
                                        <ExternalLink className="h-3.5 w-3.5" />
                                        <span>{t('goToPage')}</span>
                                      </button>
                                    )}
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        setOpenMenuTabId(null);
                                        handleCloseTab(tab.id);
                                      }}
                                      className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                                    >
                                      <X className="h-3.5 w-3.5" />
                                      <span>{t('close')}</span>
                                    </button>
                                    {deleteHandler && (
                                      <button
                                        type="button"
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          setOpenMenuTabId(null);
                                          deleteHandler();
                                        }}
                                        className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30"
                                      >
                                        <Trash2 className="h-3.5 w-3.5" />
                                        <span>{t('delete')}</span>
                                      </button>
                                    )}
                                  </PopoverContent>
                                </Popover>
                              );
                            }

                            return (
                              <span
                                role="button"
                                tabIndex={0}
                                onClick={(e) => { e.stopPropagation(); handleCloseTab(tab.id); }}
                                onKeyDown={(e) => { if (e.key === 'Enter') { e.stopPropagation(); handleCloseTab(tab.id); } }}
                                className="absolute right-1 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 focus:opacity-100 active:opacity-100 hover:bg-theme-secondary rounded p-1 transition-opacity z-20"
                              >
                                <X className="h-3 w-3" />
                              </span>
                            );
                          })()}
                        </button>
                      </React.Fragment>);
                    })}
                    {/* Add tab - sticky so it stays visible when tabs overflow (hidden in shared mode) */}
                    {!isSharedMode && (
                      <div className="sticky right-0 flex items-center self-stretch flex-shrink-0 pl-0.5 bg-theme-primary z-20">
                        <AddTabPicker variant="tab-bar" />
                      </div>
                    )}
                  </div>
                  {/* Close panel button - always visible outside scroll area */}
                  <div className="flex items-center flex-shrink-0 pr-1.5">
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={close}
                      title={t('close')}
                      className="w-7 h-7"
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                </div>

                {/* Sub-header for active tab (e.g. model selector) */}
                {activeTab?.subHeader}
              </div>
            )}

            {/* Tab content area */}
            <div className="flex-1 min-h-0 flex flex-col overflow-hidden">
              {/* keepMounted tabs: always in DOM, visibility toggled via display */}
              {tabs.filter(t => t.keepMounted).map(tab => (
                <div
                  key={tab.id}
                  className="flex-1 min-h-0 flex flex-col"
                  style={{ display: (isVisible && tab.id === activeTabId) ? undefined : 'none' }}
                >
                  {tab.content}
                </div>
              ))}
              {/* Non-keepMounted active tab: only when panel is visible. Keyed
                  by tab id so switching between two app tabs (each id is
                  runId-scoped) REMOUNTS the content instead of reconciling one
                  instance in place - otherwise the previous app's per-instance
                  state (e.g. ApplicationTabContent's viewed epoch) bleeds into
                  the next app and renders the wrong/empty epoch. */}
              {isVisible && activeTab && !activeTab.keepMounted && (
                <div key={activeTab.id} className="flex-1 min-h-0 flex flex-col">
                  {activeTab.content}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Delete confirmation modal */}
      <BulkDeleteModal
        isOpen={!!pendingDeleteTab}
        title={pendingDeleteTab ? getDeleteTitle(pendingDeleteTab.id) : ''}
        message={pendingDeleteTab ? getDeleteMessage(pendingDeleteTab.id, pendingDeleteTab.label) : ''}
        confirmLabel={t('delete')}
        cancelLabel={t('cancel')}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDeleteTab(null)}
        isConfirming={isDeleting}
      />
    </TooltipProvider>
  );
}
