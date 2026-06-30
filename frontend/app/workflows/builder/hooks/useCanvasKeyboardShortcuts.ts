'use client';

import * as React from 'react';
import type { XYPosition } from 'reactflow';

export interface CanvasShortcutActions {
  copySelection: () => void;
  paste: (position: XYPosition | null) => void;
  duplicateSelection: () => void;
  selectAll: () => void;
  deleteSelection: () => void;
}

export interface CanvasKeyboardShortcutsParams {
  /** Master switch (the context-menu actions must be wired). */
  enabled: boolean;
  /** Run / preview mode: mutating shortcuts (paste / duplicate / delete) are disabled. */
  isLocked: boolean;
  /** Only act while the pointer is over the canvas, so shortcuts never hijack the rest of the app. */
  isPointerOverCanvasRef: React.RefObject<boolean>;
  getSelectionCount: () => number;
  getNodeCount: () => number;
  hasClipboard: () => boolean;
  getPastePosition: () => XYPosition | null;
  actions: CanvasShortcutActions | undefined;
}

/** True when the keydown originated in a text field or the inspector panel. */
function isEditingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  return !!el?.closest?.('input, textarea, select, [contenteditable="true"], [data-inspector-panel]');
}

/**
 * Canvas-scoped keyboard shortcuts (copy / paste / duplicate / select all /
 * delete). Extracted from BuilderCanvas so the gating logic is unit-testable in
 * isolation. The window listener is attached once; the latest params are read
 * through a ref so the handler identity stays stable.
 *
 * The canvas owns Delete/Backspace (ReactFlow's built-in `deleteKeyCode` is
 * disabled at the call site) so deletion happens exactly once, through the same
 * path as the menu.
 */
export function useCanvasKeyboardShortcuts(params: CanvasKeyboardShortcutsParams): void {
  const paramsRef = React.useRef(params);
  React.useEffect(() => {
    paramsRef.current = params;
  });

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const p = paramsRef.current;
      if (!p.enabled || !p.actions) return;
      if (!p.isPointerOverCanvasRef.current) return;
      if (isEditingTarget(event.target)) return;

      const mod = event.ctrlKey || event.metaKey;
      const key = event.key.toLowerCase();
      const hasSelection = p.getSelectionCount() > 0;

      if (mod && key === 'c') {
        if (!hasSelection) return;
        event.preventDefault();
        p.actions.copySelection();
      } else if (mod && key === 'v') {
        if (p.isLocked || !p.hasClipboard()) return;
        event.preventDefault();
        p.actions.paste(p.getPastePosition());
      } else if (mod && key === 'd') {
        if (p.isLocked || !hasSelection) return;
        event.preventDefault();
        p.actions.duplicateSelection();
      } else if (mod && key === 'a') {
        if (p.getNodeCount() === 0) return;
        event.preventDefault();
        p.actions.selectAll();
      } else if (key === 'delete' || key === 'backspace') {
        if (p.isLocked || !hasSelection) return;
        event.preventDefault();
        p.actions.deleteSelection();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);
}
