// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { RefObject } from 'react';
import type { XYPosition } from 'reactflow';
import { useCanvasKeyboardShortcuts, type CanvasShortcutActions } from '../useCanvasKeyboardShortcuts';

const PASTE_POS: XYPosition = { x: 42, y: 99 };

function makeActions(): CanvasShortcutActions {
  return {
    copySelection: vi.fn(),
    paste: vi.fn(),
    duplicateSelection: vi.fn(),
    selectAll: vi.fn(),
    deleteSelection: vi.fn(),
  };
}

interface Overrides {
  enabled?: boolean;
  isLocked?: boolean;
  pointerOver?: boolean;
  selectionCount?: number;
  nodeCount?: number;
  hasClipboard?: boolean;
  actions?: CanvasShortcutActions | undefined;
}

function setup(o: Overrides = {}) {
  const actions = o.actions === undefined && !('actions' in o) ? makeActions() : o.actions;
  const pointerRef = { current: o.pointerOver ?? true };
  renderHook(() =>
    useCanvasKeyboardShortcuts({
      enabled: o.enabled ?? true,
      isLocked: o.isLocked ?? false,
      isPointerOverCanvasRef: pointerRef as RefObject<boolean>,
      getSelectionCount: () => o.selectionCount ?? 1,
      getNodeCount: () => o.nodeCount ?? 2,
      hasClipboard: () => o.hasClipboard ?? true,
      getPastePosition: () => PASTE_POS,
      actions,
    }),
  );
  return { actions, pointerRef };
}

function press(key: string, opts: Partial<KeyboardEventInit> & { target?: Element } = {}) {
  const { target, ...init } = opts;
  const node = target ?? document.body;
  node.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
}

let input: HTMLInputElement;
beforeEach(() => {
  input = document.createElement('input');
  document.body.appendChild(input);
});
afterEach(() => {
  input.remove();
});

describe('useCanvasKeyboardShortcuts', () => {
  it('copies the selection on Ctrl+C (and Cmd+C)', () => {
    const { actions } = setup();
    press('c', { ctrlKey: true });
    expect(actions!.copySelection).toHaveBeenCalledTimes(1);
    press('c', { metaKey: true });
    expect(actions!.copySelection).toHaveBeenCalledTimes(2);
  });

  it('does not copy when there is no selection', () => {
    const { actions } = setup({ selectionCount: 0 });
    press('c', { ctrlKey: true });
    expect(actions!.copySelection).not.toHaveBeenCalled();
  });

  it('pastes at the cursor position on Ctrl+V', () => {
    const { actions } = setup();
    press('v', { ctrlKey: true });
    expect(actions!.paste).toHaveBeenCalledWith(PASTE_POS);
  });

  it('does not paste when locked or when the clipboard is empty', () => {
    const locked = setup({ isLocked: true });
    press('v', { ctrlKey: true });
    expect(locked.actions!.paste).not.toHaveBeenCalled();

    const empty = setup({ hasClipboard: false });
    press('v', { ctrlKey: true });
    expect(empty.actions!.paste).not.toHaveBeenCalled();
  });

  it('duplicates the selection on Ctrl+D, except when locked', () => {
    const { actions } = setup();
    press('d', { ctrlKey: true });
    expect(actions!.duplicateSelection).toHaveBeenCalledTimes(1);

    const locked = setup({ isLocked: true });
    press('d', { ctrlKey: true });
    expect(locked.actions!.duplicateSelection).not.toHaveBeenCalled();
  });

  it('selects all on Ctrl+A when nodes exist', () => {
    const { actions } = setup();
    press('a', { ctrlKey: true });
    expect(actions!.selectAll).toHaveBeenCalledTimes(1);

    const empty = setup({ nodeCount: 0 });
    press('a', { ctrlKey: true });
    expect(empty.actions!.selectAll).not.toHaveBeenCalled();
  });

  it('deletes the selection on Delete and Backspace, except when locked', () => {
    const del = setup();
    press('Delete');
    expect(del.actions!.deleteSelection).toHaveBeenCalledTimes(1);

    const back = setup();
    press('Backspace');
    expect(back.actions!.deleteSelection).toHaveBeenCalledTimes(1);

    const locked = setup({ isLocked: true });
    press('Delete');
    expect(locked.actions!.deleteSelection).not.toHaveBeenCalled();
  });

  it('ignores shortcuts while the pointer is not over the canvas', () => {
    const { actions } = setup({ pointerOver: false });
    press('c', { ctrlKey: true });
    press('Delete');
    expect(actions!.copySelection).not.toHaveBeenCalled();
    expect(actions!.deleteSelection).not.toHaveBeenCalled();
  });

  it('ignores shortcuts typed into a text field', () => {
    const { actions } = setup();
    press('Delete', { target: input });
    press('c', { ctrlKey: true, target: input });
    expect(actions!.deleteSelection).not.toHaveBeenCalled();
    expect(actions!.copySelection).not.toHaveBeenCalled();
  });

  it('is a no-op when disabled', () => {
    const { actions } = setup({ enabled: false });
    press('Delete');
    expect(actions!.deleteSelection).not.toHaveBeenCalled();
  });

  it('detaches the listener on unmount', () => {
    const actions = makeActions();
    const { unmount } = renderHook(() =>
      useCanvasKeyboardShortcuts({
        enabled: true,
        isLocked: false,
        isPointerOverCanvasRef: { current: true } as RefObject<boolean>,
        getSelectionCount: () => 1,
        getNodeCount: () => 1,
        hasClipboard: () => true,
        getPastePosition: () => PASTE_POS,
        actions,
      }),
    );
    unmount();
    press('Delete');
    expect(actions.deleteSelection).not.toHaveBeenCalled();
  });
});
