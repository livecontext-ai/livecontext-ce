'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Copy, Trash2 } from 'lucide-react';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { NodePlayButton, deriveNodeStatus, type TriggerButtonVariant } from '../NodePlayButton';

// Shared button style (same as agent/subworkflow/interface persistent buttons).
// Exported so other persistent node buttons (e.g. the fleet trigger buttons) render
// the EXACT same round button as the workflow node bottom bar.
export const BTN_CLS = 'relative inline-flex items-center justify-center h-7 w-7 rounded-full bg-white dark:bg-gray-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 hover:scale-110 shadow-md transition-all duration-200 overflow-hidden';

export function ShimmerOverlay({ color }: { color: string }) {
  return (
    <span
      className="absolute inset-0 rounded-full pointer-events-none"
      style={{
        background: `linear-gradient(90deg, transparent 0%, ${color} 50%, transparent 100%)`,
        backgroundSize: '200% 100%',
        animation: 'shimmer-scan 2.5s ease-in-out infinite',
      }}
    />
  );
}

interface BottomButton {
  key: string;
  icon: React.ReactNode;
  title: string;
  onClick: (e: React.MouseEvent) => void;
  shimmer?: boolean;
  shimmerColor?: string;
}

interface PlayButtonConfig {
  nodeId: string;
  variant: TriggerButtonVariant;
  isAutoMode: boolean;
  isTriggerNode: boolean;
  stepByStepStatus: {
    isStepByStepMode: boolean;
    isReady: boolean;
    canExecute: boolean;
    isExecuting: boolean;
    isRerunning: boolean;
    isRunning: boolean;
    isFailed: boolean;
    isSkipped: boolean;
    isCompleted: boolean;
    canRerun: boolean;
    executeStep: () => void;
    rerunStep: () => void;
  };
}

export interface HoverActionsConfig {
  onDelete?: () => void;
  onDuplicate?: () => void;
}

export interface BarHoverConfig {
  /** Whether the node is currently hovered (from useHoverVisibility) */
  isVisible: boolean;
  /** Keeps the hover-visibility alive while the pointer is over the bar */
  onHover?: () => void;
}

interface NodeBottomBarProps {
  /** Border color synced with node status */
  borderColor: string;
  /** Whether the node is currently running (for shimmer) */
  isRunning: boolean;
  /** Static contextual buttons (agent config/conversation, subworkflow, interface) */
  buttons?: BottomButton[];
  /** Play/rerun button config rendered via NodePlayButton with position="bottom-center" */
  playButton?: PlayButtonConfig;
  /** Extra top offset (e.g. when pagination is visible below interface nodes) */
  extraTopOffset?: boolean;
  /** Slot rendered before the standard buttons (e.g. trigger pin button) */
  leadingSlot?: React.ReactNode;
  /** Slot rendered after the play button (e.g. edit-mode trigger launcher menu) */
  trailingSlot?: React.ReactNode;
  /**
   * Node hover state (from useHoverVisibility). When provided, the WHOLE bar -
   * contextual buttons, play, slots and delete/duplicate - is revealed only
   * while the node is hovered, so every bottom button shares the same
   * hover-to-reveal logic. JS-driven (not CSS group-hover) on purpose: the bar
   * sits outside the node's box, so it needs the useHoverVisibility grace
   * delay to stay interactive while the pointer crosses the 8px gap.
   * Omitted → always visible.
   */
  hover?: BarHoverConfig;
  /**
   * Edit-mode delete / duplicate actions rendered at the end of the row with
   * the same round-button style as the other buttons; hidden entirely in
   * run / preview-only mode. Revealed with the rest of the bar via `hover`.
   */
  hoverActions?: HoverActionsConfig;
}

/**
 * Centralized bottom bar for node buttons.
 * Renders all contextual + execution buttons in a single centered row below
 * the node, revealed on node hover when `hover` is provided.
 */
export function NodeBottomBar({ borderColor, isRunning, buttons, playButton, extraTopOffset, leadingSlot, trailingSlot, hover, hoverActions }: NodeBottomBarProps) {
  const t = useTranslations('workflowBuilder.nodes');
  const { isRunMode, isPreviewOnly } = useWorkflowMode();

  const hasButtons = (buttons && buttons.length > 0);
  const showPlay = !!playButton;
  const hasPersistent = hasButtons || showPlay || !!leadingSlot || !!trailingSlot;

  // Delete/duplicate are edit-only (same gating as the legacy top-hover buttons).
  const showHoverActions = !!hoverActions && !isRunMode && !isPreviewOnly
    && !!(hoverActions.onDelete || hoverActions.onDuplicate);

  if (!hasPersistent && !showHoverActions) return null;

  const isRevealed = hover ? hover.isVisible : true;
  // Children re-enable pointer events only while revealed: a child with an
  // unconditional pointer-events-auto would stay clickable through the
  // invisible (opacity-0) bar.
  const interactiveCls = isRevealed ? 'pointer-events-auto' : 'pointer-events-none';
  const borderStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor };

  return (
    // pointer-events-none on the row so the gaps between buttons (and the row
    // itself) never swallow canvas clicks; each interactive child re-enables
    // its own pointer events while the bar is revealed.
    <div
      className="absolute left-1/2 z-10 flex gap-1.5 nodrag nopan pointer-events-none transition-opacity duration-200 ease-out"
      style={{
        transform: 'translateX(-50%)',
        top: extraTopOffset ? 'calc(100% + 40px)' : 'calc(100% + 8px)',
        opacity: isRevealed ? 1 : 0,
      }}
      onMouseEnter={hover?.onHover}
    >
      {leadingSlot && <div className={`${interactiveCls} flex gap-1.5`}>{leadingSlot}</div>}

      {/* Static contextual buttons */}
      {buttons?.map(({ key, icon, title, onClick, shimmer, shimmerColor }) => (
        <button
          key={key}
          onClick={(e) => { e.stopPropagation(); onClick(e); }}
          className={`${BTN_CLS} ${interactiveCls}`}
          style={borderStyle}
          title={title}
        >
          {(shimmer ?? isRunning) && <ShimmerOverlay color={shimmerColor ?? 'rgba(59, 130, 246, 0.3)'} />}
          <span className="relative z-10">{icon}</span>
        </button>
      ))}

      {/* Play/rerun button */}
      {playButton && (
        <div className={`${interactiveCls} flex gap-1.5`}>
          <NodePlayButton
            nodeId={playButton.nodeId}
            status={playButton.isTriggerNode && playButton.stepByStepStatus.isReady ? 'ready' : deriveNodeStatus(playButton.stepByStepStatus)}
            canExecute={playButton.stepByStepStatus.canExecute}
            isLoading={playButton.stepByStepStatus.isExecuting || playButton.stepByStepStatus.isRerunning}
            onExecute={() => playButton.stepByStepStatus.executeStep()}
            onRerun={!playButton.isTriggerNode && playButton.stepByStepStatus.canRerun ? () => playButton.stepByStepStatus.rerunStep() : undefined}
            variant={playButton.variant}
            isAutoMode={playButton.isAutoMode}
            position="bottom-center"
            borderColor={borderColor}
          />
        </div>
      )}

      {trailingSlot && <div className={`${interactiveCls} flex gap-1.5`}>{trailingSlot}</div>}

      {/* Edit-mode delete/duplicate - same style, at the end of the centered row */}
      {showHoverActions && (
        <>
          {hoverActions!.onDelete && (
            <button
              onClick={(e) => { e.stopPropagation(); hoverActions!.onDelete!(); }}
              className={`${BTN_CLS} ${interactiveCls}`}
              style={borderStyle}
              title={t('deleteNode')}
            >
              <span className="relative z-10"><Trash2 className="h-3 w-3" strokeWidth={2} /></span>
            </button>
          )}
          {hoverActions!.onDuplicate && (
            <button
              onClick={(e) => { e.stopPropagation(); hoverActions!.onDuplicate!(); }}
              className={`${BTN_CLS} ${interactiveCls}`}
              style={borderStyle}
              title={t('duplicateNode')}
            >
              <span className="relative z-10"><Copy className="h-3 w-3" strokeWidth={2} /></span>
            </button>
          )}
        </>
      )}
    </div>
  );
}
