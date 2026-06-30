'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { Play, Bug } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import type { TriggerButtonVariant } from '../NodePlayButton';

interface TriggerEditLaunchButtonProps {
  /** Node id used as the entry point for the step-by-step execution */
  nodeId: string;
  /** Trigger sub-type - drives the icon and shimmer color */
  variant: TriggerButtonVariant;
  /** Border color synced with node status (same pattern as NodeBottomBar buttons) */
  borderColor: string;
}

const BTN_CLS = 'relative inline-flex items-center justify-center h-7 w-7 rounded-full bg-white dark:bg-gray-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 hover:scale-110 shadow-md transition-all duration-200 overflow-hidden';

// Distinct shimmer color per trigger type - mirrors NodePlayButton's palette.
const SHIMMER_BY_VARIANT: Record<TriggerButtonVariant, string> = {
  play: 'rgba(34, 197, 94, 0.35)',
  lightning: 'rgba(245, 158, 11, 0.35)',
  message: 'rgba(59, 130, 246, 0.35)',
  form: 'rgba(217, 70, 239, 0.35)',
  webhook: 'rgba(99, 102, 241, 0.35)',
  schedule: 'rgba(6, 182, 212, 0.35)',
  workflow: 'rgba(16, 185, 129, 0.35)',
  table: 'rgba(249, 115, 22, 0.35)',
  error: 'rgba(239, 68, 68, 0.35)',
};

/**
 * Edit-mode launcher rendered at the bottom of every trigger node.
 *
 * <p>Clicking it opens a small portal menu offering two start modes:
 * <ul>
 *   <li><b>Auto</b> - dispatches {@code workflowViewStart} (full automatic run).</li>
 *   <li><b>Step-by-step</b> - dispatches {@code workflowStartStepByStep} with
 *       {@code startFromNode}, entering debug mode paused on this trigger.</li>
 * </ul>
 *
 * <p>The menu reuses the portal + outside-click + Escape + scroll-dismiss
 * pattern from TriggerPanel's overflow menu, so positioning survives the
 * draggable canvas and clipping parents.
 */
export function TriggerEditLaunchButton({ nodeId, variant, borderColor }: TriggerEditLaunchButtonProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const [open, setOpen] = React.useState(false);
  const [rect, setRect] = React.useState<{ left: number; top: number } | null>(null);
  const buttonRef = React.useRef<HTMLButtonElement>(null);
  const panelRef = React.useRef<HTMLDivElement>(null);
  const [mounted, setMounted] = React.useState(false);

  React.useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  const toggle = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const btn = buttonRef.current;
    if (!btn) return;
    if (open) { setOpen(false); return; }
    const r = btn.getBoundingClientRect();
    // Anchor the menu just below the button, centered on its horizontal axis.
    setRect({ left: r.left + r.width / 2, top: r.bottom + 6 });
    setOpen(true);
  }, [open]);

  React.useEffect(() => {
    if (!open) return;
    // Capture phase so we run BEFORE React Flow's pane handlers can stop
    // propagation - otherwise clicking the canvas wouldn't bubble to the
    // document listener and the menu would stay open.
    const onPointerDown = (e: Event) => {
      const target = e.target as Node;
      if (buttonRef.current?.contains(target)) return;
      if (panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const onDismiss = () => setOpen(false);
    document.addEventListener('mousedown', onPointerDown, true);
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onDismiss, true);
    window.addEventListener('resize', onDismiss);
    return () => {
      document.removeEventListener('mousedown', onPointerDown, true);
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onDismiss, true);
      window.removeEventListener('resize', onDismiss);
    };
  }, [open]);

  const startAuto = React.useCallback(() => {
    setOpen(false);
    // Auto mode: enter run mode and fire THIS trigger directly. handleStartEvent
    // fires only the selected trigger (no-payload types) when startFromNode is set;
    // the header run button sends no startFromNode and keeps firing all root triggers.
    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { startFromNode: nodeId } }));
  }, [nodeId]);

  const startStepByStep = React.useCallback(() => {
    setOpen(false);
    window.dispatchEvent(new CustomEvent('workflowStartStepByStep', {
      detail: { startFromNode: nodeId },
    }));
  }, [nodeId]);

  const borderStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor };
  const shimmerColor = SHIMMER_BY_VARIANT[variant] ?? SHIMMER_BY_VARIANT.play;

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        onMouseDown={(e) => e.stopPropagation()}
        className={cn(BTN_CLS, 'cursor-pointer nodrag nopan', open && 'ring-2 ring-[var(--accent-primary)] ring-offset-1')}
        style={borderStyle}
        title={t('runWorkflow')}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <span
          className="absolute inset-0 rounded-full pointer-events-none"
          style={{
            background: `linear-gradient(90deg, transparent 0%, ${shimmerColor} 50%, transparent 100%)`,
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 4s ease-in-out infinite',
          }}
        />
        <span className="relative z-10">
          <Play className="h-3.5 w-3.5" strokeWidth={2} fill="currentColor" />
        </span>
      </button>

      {mounted && open && rect && createPortal(
        <div
          ref={panelRef}
          role="menu"
          className="fixed z-[9999] min-w-[200px] bg-theme-primary rounded-2xl p-2 border border-gray-300/70 dark:border-gray-600/70 shadow-2xl animate-in fade-in-0 zoom-in-95 duration-150 nodrag nopan"
          style={{ left: rect.left, top: rect.top, transform: 'translateX(-50%)' }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <div className="space-y-1">
            <button
              role="menuitem"
              onClick={startAuto}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Play className="h-4 w-4 flex-shrink-0" strokeWidth={2} fill="currentColor" />
              <span className="text-sm flex-1 text-left truncate">{t('runAuto')}</span>
            </button>
            <button
              role="menuitem"
              onClick={startStepByStep}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Bug className="h-4 w-4 flex-shrink-0" strokeWidth={2} />
              <span className="text-sm flex-1 text-left truncate">{t('runStepByStep')}</span>
            </button>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
