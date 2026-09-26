'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Panel, type Node } from 'reactflow';
import { ZoomIn, ZoomOut, Focus, Lock, Unlock, SquareDashedMousePointer, Hand, Undo2, Redo2, Settings, Wand2, Sparkles } from 'lucide-react';
import { canvasChromeCompactButtonClass, canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import {
  Select,
  SelectTrigger,
  SelectContent,
  SelectItem,
} from '@/components/ui/select';
import { WorkflowRelationsAutoMenu } from '@/components/workflow/relations/WorkflowRelationsAutoMenu';
import { requestOpenRelatedWorkflow } from '@/lib/sidePanel/openWorkflowBuilderTab';
import { CanvasFileStripToggleButton } from './CanvasFileStripToggleButton';
import { CanvasRunFollowToggleButton } from './CanvasRunFollowToggleButton';
import { CanvasRunTriggerButton } from './CanvasRunTriggerButton';
import { TriggerNodePinButton } from './nodes/TriggerNodePinButton';
import type { CanvasCursorMode } from '../hooks/useBoxSelection';
import type { BuilderNodeData } from '../types';

interface CanvasToolbarProps {
  isRunMode: boolean;
  canUndo: boolean;
  canRedo: boolean;
  isInteractive: boolean;
  isLockFocused: boolean;
  cursorMode: CanvasCursorMode;
  isSettingsOpen: boolean;
  /** Canvas nodes - feed the run-mode trigger picker. */
  nodes: Node<BuilderNodeData>[];
  /**
   * Show the run-mode controls (fire a trigger, pin the workflow as production).
   * False in the read-only marketplace preview, which can neither run nor pin.
   */
  showRunControls: boolean;
  /**
   * Offer the sub-workflow relations menu (which workflows call this one, which ones it calls).
   * False in the read-only marketplace preview and in snapshot views: those render someone else's
   * plan, whose neighbours are not workflows this viewer has, so every row would be a dead end.
   */
  showRelations: boolean;
  workflowId?: string;
  onUndo?: () => void;
  onRedo?: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onAutoLayout: () => void;
  onToggleInteractivity: () => void;
  onCursorModeChange: (mode: CanvasCursorMode) => void;
  onToggleSettings: () => void;
}

export function CanvasToolbar({
  isRunMode,
  canUndo,
  canRedo,
  isInteractive,
  isLockFocused,
  cursorMode,
  isSettingsOpen,
  nodes,
  showRunControls,
  showRelations,
  workflowId,
  onUndo,
  onRedo,
  onZoomIn,
  onZoomOut,
  onFitView,
  onAutoLayout,
  onToggleInteractivity,
  onCursorModeChange,
  onToggleSettings,
}: CanvasToolbarProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const cursorModeLabel = cursorMode === 'selection' ? t('cursorModeSelection') : t('cursorModePan');
  return (
    // The bound is against the CANVAS (100% of the pane), not the viewport: with
    // a side panel open the old `100vw` let the toolbar run wider than the canvas
    // it belongs to. reactflow centers the `bottom-center` Panel itself, so a
    // percentage bound keeps it centred AND inside its own pane at every width.
    <Panel position="bottom-center" className="mb-4 sm:mb-6 max-w-[calc(100%-1rem)]">
      <div
        // p-1 around 28px controls = the same 36px height as the mode toggle and
        // the run bar, so the whole canvas chrome reads as one size.
        className={`flex items-center justify-start gap-0.5 p-1 max-w-full overflow-x-auto [&::-webkit-scrollbar]:hidden ${canvasChromeSurfaceClass}`}
        // Firefox; the WebKit/Blink equivalent is the utility above.
        style={{ scrollbarWidth: 'none' }}
      >
        {/* Run controls - run mode only: fire a chosen trigger, and pin the
            viewed version as production while it is not the pinned one (the
            pin button hides itself once it is). */}
        {showRunControls && (
          // empty:hidden - both children gate themselves off (no fireable
          // trigger / already on the pinned run), and a bare separator with
          // padding would be left behind otherwise.
          <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1 empty:hidden" data-testid="canvas-toolbar-run-controls">
            <CanvasRunTriggerButton nodes={nodes} />
            {workflowId && <TriggerNodePinButton workflowId={workflowId} variant="toolbar" />}
          </div>
        )}

        {/* Cursor mode - only in edit mode. Persisted for every workflow, so the
            select reflects a user preference rather than per-graph state. */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
            <Select value={cursorMode} onValueChange={(value) => onCursorModeChange(value as CanvasCursorMode)}>
              <SelectTrigger
                // Not `canvasChromeCompactButtonClass`: a Radix trigger brings its
                // own border + surface, and only `border-0` reliably beats the
                // `border-theme` utility in its base (twMerge does not know that
                // custom class, so a `border-transparent` would just sit next to
                // it). The tokens below are the helper's, restated for that one
                // reason - shape, height, hover ladder and focus ring all match.
                className="h-7 min-h-7 w-auto shrink-0 gap-1 rounded-xl border-0 bg-transparent px-1.5 py-0 shadow-none text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)] hover:text-[var(--text-primary)] focus:ring-0 focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]"
                // The trigger shows an icon only, so the accessible name has to
                // carry the ACTIVE mode too - an aria-label overrides `title`.
                aria-label={`${t('cursorMode')}: ${cursorModeLabel}`}
                title={cursorModeLabel}
                data-testid="canvas-cursor-mode-select"
                // Same guards as the settings panel's selects: stop the canvas
                // pane from treating the dropdown interaction as a click on it.
                // (Box selection is already safe: its native mousedown listener
                // skips any target inside a button.)
                onClick={(e) => e.stopPropagation()}
                onMouseDown={(e) => e.stopPropagation()}
              >
                {cursorMode === 'selection'
                  ? <SquareDashedMousePointer className="h-4 w-4" />
                  : <Hand className="h-4 w-4" />}
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="pan">
                  <span className="flex items-center gap-2">
                    <Hand className="h-3.5 w-3.5" />
                    {t('cursorModePan')}
                  </span>
                </SelectItem>
                <SelectItem value="selection">
                  <span className="flex items-center gap-2">
                    <SquareDashedMousePointer className="h-3.5 w-3.5" />
                    {t('cursorModeSelection')}
                  </span>
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}

        {/* Undo/Redo - only in edit mode */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
            <button
              type="button"
              onClick={onUndo}
              disabled={!canUndo}
              className={canvasChromeCompactButtonClass()}
              title={t('undoTooltip')}
            >
              <Undo2 className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={onRedo}
              disabled={!canRedo}
              className={canvasChromeCompactButtonClass()}
              title={t('redoTooltip')}
            >
              <Redo2 className="h-4 w-4" />
            </button>
          </div>
        )}

        {/* Zoom controls */}
        <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
          <button
            type="button"
            onClick={onZoomIn}
            className={canvasChromeCompactButtonClass()}
            title={t('zoomIn')}
          >
            <ZoomIn className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onZoomOut}
            className={canvasChromeCompactButtonClass()}
            title={t('zoomOut')}
          >
            <ZoomOut className="h-4 w-4" />
          </button>
        </div>

        {/* View controls */}
        <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
          <button
            type="button"
            onClick={onFitView}
            className={canvasChromeCompactButtonClass()}
            title={t('fitView')}
          >
            <Focus className="h-4 w-4" />
          </button>
          {/* Same group as Focus on purpose: both are "put the camera somewhere
              useful". Focus frames the whole graph once, this one keeps framing
              what is happening: the running (or waiting) step in run mode, the
              nodes an agent adds in edit mode. */}
          <CanvasRunFollowToggleButton />
          <button
            type="button"
            onClick={onAutoLayout}
            className={canvasChromeCompactButtonClass()}
            title={t('autoLayout')}
          >
            <Wand2 className="h-4 w-4" />
          </button>
        </div>

        {/* Whether file previews hang open under the nodes. A remembered preference,
            so it stays put on a run that produced no file - that is still a moment
            worth stating it. It removes itself in EDIT mode, where nothing it acts on
            can exist. Reads both its state and that decision itself, so it needs no
            prop from here. */}
        <CanvasFileStripToggleButton />

        {/* Interactivity lock */}
        <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
          <button
            type="button"
            onClick={onToggleInteractivity}
            className={canvasChromeCompactButtonClass(isLockFocused)}
            title={isInteractive ? t('disableInteractivity') : t('enableInteractivity')}
          >
            {isInteractive ? (
              <Unlock className="h-4 w-4" />
            ) : (
              <Lock className="h-4 w-4" />
            )}
          </button>
        </div>

        {/* Settings, and beside it the sub-workflow relations menu - ONE group, no divider
            between them. Both are "about this workflow" rather than about the view, so a
            separator would have suggested a boundary that is not there.

            The relations menu is offered in BOTH modes: "what calls this?" is a question you ask
            while reading a run as much as while editing. It renders nothing when the workflow has
            no sub-workflow neighbour, so its mere presence is the indicator, and in run mode a
            pick goes through the view's own opener, which resolves the target's PINNED RUN first -
            the same route the sub-workflow button on a node takes. */}
        <div className="flex items-center gap-1 border-r border-[var(--border-color)] pr-1">
          <button
            type="button"
            onClick={onToggleSettings}
            className={canvasChromeCompactButtonClass(isSettingsOpen)}
            title={t('settings')}
          >
            <Settings className="h-4 w-4" />
          </button>
          {showRelations && workflowId && (
            <WorkflowRelationsAutoMenu
              workflowId={workflowId}
              variant="toolbar"
              side="top"
              align="center"
              data-testid="canvas-toolbar-relations"
              onOpen={isRunMode ? (ref) => requestOpenRelatedWorkflow(ref.id, ref.name, '', workflowId) : undefined}
            />
          )}
        </div>

        {/* AI Assistant */}
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => {
              window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
                detail: { toggle: true, view: 'chat' }
              }));
            }}
            className={canvasChromeCompactButtonClass()}
            title={t('openConversation')}
          >
            <Sparkles className="h-4 w-4" />
          </button>
        </div>
      </div>
    </Panel>
  );
}
