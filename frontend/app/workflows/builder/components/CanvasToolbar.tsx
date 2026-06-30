'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Panel } from 'reactflow';
import { ZoomIn, ZoomOut, Focus, Lock, Unlock, MousePointer2, Undo2, Redo2, Settings, Wand2, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface CanvasToolbarProps {
  isRunMode: boolean;
  canUndo: boolean;
  canRedo: boolean;
  isInteractive: boolean;
  isLockFocused: boolean;
  isBoxSelectionEnabled: boolean;
  isSettingsOpen: boolean;
  onUndo?: () => void;
  onRedo?: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onAutoLayout: () => void;
  onToggleInteractivity: () => void;
  onToggleBoxSelection: () => void;
  onToggleSettings: () => void;
}

export function CanvasToolbar({
  isRunMode,
  canUndo,
  canRedo,
  isInteractive,
  isLockFocused,
  isBoxSelectionEnabled,
  isSettingsOpen,
  onUndo,
  onRedo,
  onZoomIn,
  onZoomOut,
  onFitView,
  onAutoLayout,
  onToggleInteractivity,
  onToggleBoxSelection,
  onToggleSettings,
}: CanvasToolbarProps) {
  const t = useTranslations('workflowBuilder.canvas');
  return (
    <Panel position="bottom-center" className="mb-6">
      <div className="flex items-center gap-1 rounded-full bg-white/95 dark:bg-gray-800/95 px-2 sm:px-3 py-2 backdrop-blur border-0 max-w-[calc(100vw-32px)] overflow-x-auto" style={{ scrollbarWidth: 'none' }}>
        {/* Undo/Redo - only in edit mode */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
            <Button
              onClick={onUndo}
              disabled={!canUndo}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={t('undoTooltip')}
            >
              <Undo2 className="h-4 w-4" />
            </Button>
            <Button
              onClick={onRedo}
              disabled={!canRedo}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={t('redoTooltip')}
            >
              <Redo2 className="h-4 w-4" />
            </Button>
          </div>
        )}

        {/* Zoom controls */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onZoomIn}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('zoomIn')}
          >
            <ZoomIn className="h-4 w-4" />
          </Button>
          <Button
            onClick={onZoomOut}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('zoomOut')}
          >
            <ZoomOut className="h-4 w-4" />
          </Button>
        </div>

        {/* View controls */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onFitView}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('fitView')}
          >
            <Focus className="h-4 w-4" />
          </Button>
          <Button
            onClick={onAutoLayout}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('autoLayout')}
          >
            <Wand2 className="h-4 w-4" />
          </Button>
        </div>

        {/* Interactivity lock */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onToggleInteractivity}
            variant={isLockFocused ? "default" : "ghost"}
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={isInteractive ? t('disableInteractivity') : t('enableInteractivity')}
          >
            {isInteractive ? (
              <Unlock className="h-4 w-4" />
            ) : (
              <Lock className="h-4 w-4" />
            )}
          </Button>
        </div>

        {/* Box selection - only in edit mode */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
            <Button
              onClick={onToggleBoxSelection}
              variant={isBoxSelectionEnabled ? "default" : "ghost"}
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={isBoxSelectionEnabled ? t('disableBoxSelection') : t('enableBoxSelection')}
            >
              <MousePointer2 className="h-4 w-4" />
            </Button>
          </div>
        )}

        {/* Settings */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onToggleSettings}
            variant={isSettingsOpen ? "default" : "ghost"}
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('settings')}
          >
            <Settings className="h-4 w-4" />
          </Button>
        </div>

        {/* AI Assistant */}
        <div className="flex items-center gap-1">
          <Button
            onClick={() => {
              window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
                detail: { toggle: true, view: 'chat' }
              }));
            }}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('openConversation')}
          >
            <Sparkles className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </Panel>
  );
}
