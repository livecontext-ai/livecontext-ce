'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import {
  Play,
  RotateCcw,
  Check,
  X,
  SlidersHorizontal,
  Eye,
  Copy,
  Bug,
  Pin,
  PinOff,
  Monitor,
  ClipboardCopy,
  ClipboardPaste,
  Network,
  Unlink,
  StickyNote,
  Trash2,
  Plus,
  BoxSelect,
  Focus,
  Wand2,
} from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useNodeExecutionStatus } from '../contexts/StepByStepContext';
import { deriveNodeContextFlags, useNodeContextualButtons } from '../hooks/useNodeContextualButtons';
import { useTriggerPinDisplay, requestTriggerPin } from '../hooks/useTriggerPin';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { findNodeClassById } from '../nodes/nodeClasses';

/** Operations the node menu delegates back to the canvas (all operate on raw graph state). */
export interface NodeContextMenuActions {
  openSettings: (nodeId: string) => void;
  duplicate: (nodeId: string) => void;
  copy: (nodeId: string) => void;
  selectDownstream: (nodeId: string) => void;
  disconnect: (nodeId: string) => void;
  addNote: (nodeId: string) => void;
  deleteNode: (nodeId: string) => void;
}

/** Operations the pane (empty-canvas) menu delegates back to the canvas. */
export interface PaneContextMenuActions {
  addNode: () => void;
  paste: () => void;
  selectAll: () => void;
  autoLayout: () => void;
  fitView: () => void;
}

const isMacPlatform = (): boolean =>
  typeof navigator !== 'undefined' && /mac|iphone|ipad|ipod/i.test(navigator.platform || navigator.userAgent || '');

/* ----------------------------------------------------------------------------
 * Themed primitives
 * ------------------------------------------------------------------------- */

interface ContextMenuShellProps {
  x: number;
  y: number;
  onClose: () => void;
  children: React.ReactNode;
  /** Accessible label for the menu container. */
  ariaLabel?: string;
}

/**
 * Floating, theme-matched menu container. Portals to <body>, positions at the
 * cursor (flipped to stay inside the viewport), and closes on outside
 * mousedown, Escape, wheel/scroll, or resize.
 */
function ContextMenuShell({ x, y, onClose, children, ariaLabel }: ContextMenuShellProps) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [pos, setPos] = React.useState({ x, y });
  const [ready, setReady] = React.useState(false);

  React.useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const pad = 8;
    let nx = x;
    let ny = y;
    if (x + rect.width + pad > window.innerWidth) nx = Math.max(pad, window.innerWidth - rect.width - pad);
    if (y + rect.height + pad > window.innerHeight) ny = Math.max(pad, window.innerHeight - rect.height - pad);
    setPos({ x: nx, y: ny });
    setReady(true);
  }, [x, y]);

  React.useEffect(() => {
    const onPointerDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as globalThis.Node)) onClose();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    const onScroll = () => onClose();
    // capture: react before the canvas opens a fresh menu / before nodes move.
    window.addEventListener('mousedown', onPointerDown, true);
    window.addEventListener('keydown', onKeyDown, true);
    window.addEventListener('wheel', onScroll, true);
    window.addEventListener('resize', onClose);
    return () => {
      window.removeEventListener('mousedown', onPointerDown, true);
      window.removeEventListener('keydown', onKeyDown, true);
      window.removeEventListener('wheel', onScroll, true);
      window.removeEventListener('resize', onClose);
    };
  }, [onClose]);

  return createPortal(
    <div
      ref={ref}
      role="menu"
      aria-label={ariaLabel}
      data-testid="canvas-context-menu"
      onContextMenu={(e) => e.preventDefault()}
      className="fixed z-[10000] min-w-[15rem] max-w-[20rem] select-none rounded-xl border border-slate-200 bg-white/95 p-1.5 text-slate-700 shadow-xl backdrop-blur-sm dark:border-slate-700 dark:bg-gray-800/95 dark:text-slate-200"
      style={{ left: pos.x, top: pos.y, visibility: ready ? 'visible' : 'hidden' }}
    >
      {children}
    </div>,
    document.body,
  );
}

interface ContextMenuItemProps {
  icon: React.ReactNode;
  label: string;
  shortcut?: string;
  onClick: (e: React.MouseEvent) => void;
  variant?: 'default' | 'danger';
}

function ContextMenuItem({ icon, label, shortcut, onClick, variant = 'default' }: ContextMenuItemProps) {
  const tone =
    variant === 'danger'
      ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20'
      : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700/60';
  return (
    <button
      type="button"
      role="menuitem"
      onClick={(e) => {
        e.stopPropagation();
        onClick(e);
      }}
      className={`group flex w-full items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left text-sm transition-colors ${tone}`}
    >
      <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center text-slate-500 group-hover:text-current dark:text-slate-400">
        {icon}
      </span>
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {shortcut && (
        <span className="ml-auto shrink-0 pl-4 text-xs text-slate-400 dark:text-slate-500">{shortcut}</span>
      )}
    </button>
  );
}

function ContextMenuSeparator() {
  return <div className="my-1 h-px bg-slate-200 dark:bg-slate-700/70" role="separator" />;
}

/* ----------------------------------------------------------------------------
 * Node context menu
 * ------------------------------------------------------------------------- */

interface NodeContextMenuProps {
  node: Node<BuilderNodeData>;
  x: number;
  y: number;
  isRunMode: boolean;
  isPreviewOnly: boolean;
  hasDownstream: boolean;
  hasConnections: boolean;
  actions: NodeContextMenuActions;
  onClose: () => void;
}

/**
 * Right-click menu for a single node. Dynamically a superset of the node's
 * bottom-bar buttons, gated per node type / mode exactly like NodeBottomBar:
 * run actions (execute / re-run / approve, run mode), the edit-mode trigger
 * launcher (Run / Step-by-step), the trigger pin-as-production affordance, the
 * same contextual side-panel buttons (agent / table / files / sub-workflow),
 * the run-mode "View interface" action, and graph-edit operations (edit mode).
 */
export function NodeContextMenu({
  node,
  x,
  y,
  isRunMode,
  isPreviewOnly,
  hasDownstream,
  hasConnections,
  actions,
  onClose,
}: NodeContextMenuProps) {
  const t = useTranslations('workflowBuilder.contextMenu');
  const tCanvas = useTranslations('workflowBuilder.canvas');
  const data = node.data;
  const nodeId = node.id;
  const editable = !isRunMode && !isPreviewOnly;
  const mod = isMacPlatform() ? '⌘' : 'Ctrl';
  const { workflowId } = useWorkflowMode();

  const exec = useNodeExecutionStatus(nodeId, {
    label: data.label,
    kind: data.kind,
    crudOperation: (data as unknown as { dataSourceData?: { crudOperation?: string } }).dataSourceData?.crudOperation,
  });

  const nodeClass = findNodeClassById(data.id || '');
  const flags = deriveNodeContextFlags(data, nodeClass?.id || data.id);
  const contextualButtons = useNodeContextualButtons({
    data,
    nodeUiId: nodeId,
    isRunMode,
    flags,
    includeFiles: true,
    currentFile: null,
  });
  // Same read-only pin state the bottom-bar pin button shows; the actual flow
  // runs on the mounted button (it owns the confirmation modal) via an event.
  const pinDisplay = useTriggerPinDisplay();
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: launching a
  // run auto-saves the plan and the backend 403s VIEWER, so the run entries hide
  // (useWorkflowExecution also no-ops the dispatched events).
  const canMutate = useCanMutateInCurrentOrg();

  const run = (fn: () => void) => () => {
    fn();
    onClose();
  };

  // Interface id for the run-mode "View interface" action - mirrors FlowNode's
  // resolution (explicit interfaceData id, else parsed from the node-class id).
  const interfaceId: string | undefined = React.useMemo(() => {
    const explicit = (data as unknown as { interfaceData?: { interfaceId?: string } }).interfaceData?.interfaceId;
    if (explicit) return explicit;
    const nid = data.id || '';
    return nid.startsWith('interface-') && nid !== 'interface'
      ? nid.replace('interface-', '').replace(/--\d+$/, '')
      : undefined;
  }, [data]);

  // Run-action group (run mode), edit-mode trigger launcher, and the trigger
  // pin/unpin affordance - the menu mirrors the buttons that render under the
  // node, gated per node type / mode exactly like NodeBottomBar.
  const hasRunActions = exec.canExecute || exec.canRerun || exec.pendingSignalCount > 0;
  const showLauncher = editable && flags.isTriggerNode && canMutate;
  const showPin = !isPreviewOnly && flags.isTriggerNode && !!workflowId && pinDisplay.shouldRender;
  const showViewInterface = isRunMode && flags.isInterfaceNode && !!interfaceId;
  const hasTopGroup = hasRunActions || showLauncher || showPin;

  return (
    <ContextMenuShell x={x} y={y} onClose={onClose} ariaLabel={t('nodeMenuLabel')}>
      {/* Run actions - only when the live run state permits them */}
      {exec.canExecute && (
        <ContextMenuItem icon={<Play className="h-3.5 w-3.5" fill="currentColor" />} label={t('executeStep')} onClick={run(exec.executeStep)} />
      )}
      {exec.canRerun && (
        <ContextMenuItem icon={<RotateCcw className="h-3.5 w-3.5" />} label={t('rerunStep')} onClick={run(() => exec.rerunStep())} />
      )}
      {exec.pendingSignalCount > 0 && (
        <>
          <ContextMenuItem icon={<Check className="h-3.5 w-3.5" />} label={t('approve')} onClick={run(() => exec.resolveApproval('APPROVED'))} />
          <ContextMenuItem icon={<X className="h-3.5 w-3.5" />} label={t('reject')} variant="danger" onClick={run(() => exec.resolveApproval('REJECTED'))} />
        </>
      )}

      {/* Edit-mode trigger launcher - mirrors the Play launcher under the node
          (Auto run + Step-by-step debug). Triggers only; non-triggers have none. */}
      {showLauncher && (
        <>
          <ContextMenuItem
            icon={<Play className="h-3.5 w-3.5" fill="currentColor" />}
            label={tCanvas('runAuto')}
            onClick={run(() => window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { startFromNode: nodeId } })))}
          />
          <ContextMenuItem
            icon={<Bug className="h-3.5 w-3.5" />}
            label={tCanvas('runStepByStep')}
            onClick={run(() => window.dispatchEvent(new CustomEvent('workflowStartStepByStep', { detail: { startFromNode: nodeId } })))}
          />
        </>
      )}

      {/* Trigger pin / unpin (set as production) - same affordance as the pin
          button under the trigger node; the flow runs on that mounted button. */}
      {showPin && (
        <ContextMenuItem
          icon={pinDisplay.isAlreadyPinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
          label={pinDisplay.buttonTitle}
          onClick={run(() => requestTriggerPin(workflowId, nodeId))}
        />
      )}

      {hasTopGroup && <ContextMenuSeparator />}

      {/* Configure / inspect - available in every mode */}
      <ContextMenuItem
        icon={isRunMode || isPreviewOnly ? <Eye className="h-3.5 w-3.5" /> : <SlidersHorizontal className="h-3.5 w-3.5" />}
        label={isRunMode || isPreviewOnly ? t('viewDetails') : t('openSettings')}
        onClick={run(() => actions.openSettings(nodeId))}
      />
      {contextualButtons.map((button) => (
        <ContextMenuItem
          key={button.key}
          icon={button.icon}
          label={button.title}
          onClick={(e) => {
            button.onClick(e);
            onClose();
          }}
        />
      ))}

      {/* Run-mode interface node - open the rendered interface (same as the
          "View interface" button under the node in run mode). */}
      {showViewInterface && (
        <ContextMenuItem
          icon={<Monitor className="h-3.5 w-3.5" />}
          label={t('viewInterface')}
          onClick={run(() => window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', { detail: { interfaceId } })))}
        />
      )}

      {/* Edit operations - hidden in run / preview-only mode */}
      {editable && (
        <>
          <ContextMenuSeparator />
          <ContextMenuItem icon={<Copy className="h-3.5 w-3.5" />} label={t('duplicate')} shortcut={`${mod}D`} onClick={run(() => actions.duplicate(nodeId))} />
          <ContextMenuItem icon={<ClipboardCopy className="h-3.5 w-3.5" />} label={t('copy')} shortcut={`${mod}C`} onClick={run(() => actions.copy(nodeId))} />
          {hasDownstream && (
            <ContextMenuItem icon={<Network className="h-3.5 w-3.5" />} label={t('selectDownstream')} onClick={run(() => actions.selectDownstream(nodeId))} />
          )}
          {hasConnections && (
            <ContextMenuItem icon={<Unlink className="h-3.5 w-3.5" />} label={t('disconnectAll')} onClick={run(() => actions.disconnect(nodeId))} />
          )}
          <ContextMenuItem icon={<StickyNote className="h-3.5 w-3.5" />} label={t('addNote')} onClick={run(() => actions.addNote(nodeId))} />
          <ContextMenuSeparator />
          <ContextMenuItem icon={<Trash2 className="h-3.5 w-3.5" />} label={t('delete')} shortcut={t('shortcutDelete')} variant="danger" onClick={run(() => actions.deleteNode(nodeId))} />
        </>
      )}
    </ContextMenuShell>
  );
}

/* ----------------------------------------------------------------------------
 * Pane (empty canvas) context menu
 * ------------------------------------------------------------------------- */

interface PaneContextMenuProps {
  x: number;
  y: number;
  editable: boolean;
  canPaste: boolean;
  hasNodes: boolean;
  actions: PaneContextMenuActions;
  onClose: () => void;
}

/**
 * Right-click menu for the empty canvas: add a node, paste, select all, run
 * auto-layout, and fit the view. Mutating items are hidden in run / preview
 * mode; view items (select all, fit view) stay available.
 */
export function PaneContextMenu({ x, y, editable, canPaste, hasNodes, actions, onClose }: PaneContextMenuProps) {
  const t = useTranslations('workflowBuilder.contextMenu');
  const mod = isMacPlatform() ? '⌘' : 'Ctrl';

  const run = (fn: () => void) => () => {
    fn();
    onClose();
  };

  return (
    <ContextMenuShell x={x} y={y} onClose={onClose} ariaLabel={t('paneMenuLabel')}>
      {editable && (
        <ContextMenuItem icon={<Plus className="h-3.5 w-3.5" />} label={t('addNode')} onClick={run(actions.addNode)} />
      )}
      {editable && canPaste && (
        <ContextMenuItem icon={<ClipboardPaste className="h-3.5 w-3.5" />} label={t('paste')} shortcut={`${mod}V`} onClick={run(actions.paste)} />
      )}
      {hasNodes && (
        <>
          {editable && <ContextMenuSeparator />}
          <ContextMenuItem icon={<BoxSelect className="h-3.5 w-3.5" />} label={t('selectAll')} shortcut={`${mod}A`} onClick={run(actions.selectAll)} />
          {editable && (
            <ContextMenuItem icon={<Wand2 className="h-3.5 w-3.5" />} label={t('autoLayout')} onClick={run(actions.autoLayout)} />
          )}
          <ContextMenuItem icon={<Focus className="h-3.5 w-3.5" />} label={t('fitView')} onClick={run(actions.fitView)} />
        </>
      )}
    </ContextMenuShell>
  );
}

/* ----------------------------------------------------------------------------
 * Default export: a thin variant dispatcher so the whole module (and its heavy
 * side-panel-content import chain) can be React.lazy-loaded on first right-click
 * rather than eagerly with the canvas.
 * ------------------------------------------------------------------------- */

type CanvasContextMenuProps =
  | ({ variant: 'node' } & NodeContextMenuProps)
  | ({ variant: 'pane' } & PaneContextMenuProps);

export default function CanvasContextMenu(props: CanvasContextMenuProps) {
  if (props.variant === 'node') {
    return <NodeContextMenu {...props} />;
  }
  return <PaneContextMenu {...props} />;
}
