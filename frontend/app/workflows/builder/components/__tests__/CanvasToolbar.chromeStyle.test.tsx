// @vitest-environment jsdom
/**
 * The bottom toolbar is one of the three canvas-chrome surfaces that moved off
 * the pill look onto the shared Button system. What has to hold, in BOTH modes:
 *
 *  - every control is the shared `canvasChromeCompactButtonClass`, not a local copy;
 *  - the two stateful controls (lock, settings) express their state through it;
 *  - nothing round is left behind;
 *  - the card is bounded by the CANVAS and scrolls, so a phone-width canvas can
 *    still reach every control instead of having it clipped off the edge.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
// Radix's Select cannot open in jsdom; only its trigger matters here.
vi.mock('@/components/ui/select', async () => {
  const react = await import('react');
  return {
    Select: ({ children }: { children: React.ReactNode }) => react.createElement('div', null, children),
    SelectTrigger: ({ children, ...props }: { children: React.ReactNode }) =>
      react.createElement('button', { type: 'button', ...props }, children),
    SelectContent: () => null,
    SelectItem: () => null,
  };
});
// Keep the Panel's className: it carries the width bound under test.
vi.mock('reactflow', () => ({
  Panel: ({ children, className }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="canvas-toolbar-panel" className={className}>{children}</div>
  ),
}));
// A RUN canvas. The file-strip toggle takes itself off an editing toolbar, and
// without a provider the mode hook answers "edit" - which would leave the style
// assertion below with nothing to measure.
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isEditMode: false }) }));
vi.mock('../CanvasRunTriggerButton', () => ({ CanvasRunTriggerButton: () => null }));
// The relations menu renders only when the workflow HAS a neighbour, and resolving that
// is a network read. Pin one parent so the trigger exists and its style can be measured.
vi.mock('@/components/workflow/relations/useWorkflowRelations', () => ({
  useWorkflowRelations: () => ({
    relations: { parents: [{ id: 'wf-parent', name: 'Parent', resolved: true }], children: [] },
    isLoading: false,
  }),
}));
vi.mock('../nodes/TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));

import {
  CANVAS_CHROME_CARD_PADDING_PX,
  CANVAS_CHROME_TOP_ROW_HEIGHT_PX,
  canvasChromeCompactButtonClass,
  canvasChromeSurfaceClass,
} from '@/components/ui/canvas-chrome';
import { CanvasToolbar } from '../CanvasToolbar';
import { FileStripExpansionProvider } from '@/contexts/FileStripExpansionContext';
import { __resetRunCameraFollowForTests } from '../../services/runCameraFollowStore';

const baseProps = () => ({
  isRunMode: false,
  canUndo: true,
  canRedo: true,
  isInteractive: true,
  isLockFocused: false,
  cursorMode: 'pan' as const,
  isSettingsOpen: false,
  nodes: [],
  showRunControls: false,
  // ON by default: the relations trigger is a plain square chrome control like the rest,
  // so it belongs INSIDE the "every control shares the style" invariant below rather than
  // in an exception beside it (the mocked hook above gives it a relation to show).
  showRelations: true,
  workflowId: 'wf-1',
  onUndo: vi.fn(),
  onRedo: vi.fn(),
  onZoomIn: vi.fn(),
  onZoomOut: vi.fn(),
  onFitView: vi.fn(),
  onAutoLayout: vi.fn(),
  onToggleInteractivity: vi.fn(),
  onCursorModeChange: vi.fn(),
  onToggleSettings: vi.fn(),
});

/** The toolbar card - the Panel's only child. */
const card = (container: HTMLElement) =>
  container.querySelector('[data-testid="canvas-toolbar-panel"] > div') as HTMLElement;

/** Every icon control except the cursor-mode select (a Radix trigger). */
const iconButtons = (container: HTMLElement) =>
  Array.from(container.querySelectorAll<HTMLElement>('button[title]')).filter(
    (b) => b.getAttribute('data-testid') !== 'canvas-cursor-mode-select',
  );

// The file-strip toggle reads a stored preference and paints itself active when it
// says "expanded", so every case here starts from "nothing stored" to compare against
// the inactive shared class.
beforeEach(() => {
  // The run-follow toggle reads a MODULE store that caches after its first read, so
  // clearing localStorage alone would not put it back to its default here.
  __resetRunCameraFollowForTests();
  window.localStorage.clear();
});

describe('CanvasToolbar - canvas chrome style', () => {
  it('draws the card as the shared square chrome surface', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    for (const token of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(card(container).className, `card lost ${token}`).toContain(token);
    }
  });

  it('gives every control the shared chrome button style, not a local copy', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const buttons = iconButtons(container);
    // Undo, redo, zoom in/out, fit, run-follow, auto-layout, lock, settings, relations,
    // AI assistant. The run-follow toggle sits in the Focus group and uses the shared
    // class unchanged, so it belongs in this count. It renders because the
    // WorkflowModeContext MOCK at the top of this file reports isEditMode false, not
    // because of baseProps, which sets isRunMode false.
    // The file-strip toggle is NOT among them: it renders nothing without a
    // FileStripExpansionProvider, and it is the one control with a documented
    // deviation from the shared class, so it gets its own case below rather than
    // silently escaping this one.
    expect(buttons).toHaveLength(11);
    // None of them is in its active state under these props.
    for (const button of buttons) {
      expect(button.className, `${button.getAttribute('title')} is off the shared style`)
        .toBe(canvasChromeCompactButtonClass(false));
    }
  });

  it('holds the file-strip toggle to the same style, minus its one documented deviation', () => {
    // It carries two glyphs, so it gives up the square width - and nothing else.
    // Spelled out here because the invariant above cannot see it: without a
    // provider it renders nothing, which would let a hand-rolled className through.
    const { container } = render(
      <FileStripExpansionProvider>
        <CanvasToolbar {...baseProps()} />
      </FileStripExpansionProvider>,
    );
    const toggle = container.querySelector<HTMLElement>('[data-testid="canvas-toggle-all-files"]');

    expect(toggle).not.toBeNull();
    expect(toggle!.className).toBe(canvasChromeCompactButtonClass(false, 'w-auto gap-0.5 px-1.5'));
  });

  it('draws the relations trigger as a plain square control, with no count painted on it', () => {
    // It used to carry the relation count, which read as a notification badge - a thing that is
    // unread and wants clearing. It is neither. The number lives in the accessible name instead,
    // and the button is the same square as every other one.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const trigger = container.querySelector<HTMLElement>('[data-testid="canvas-toolbar-relations"]');

    expect(trigger).not.toBeNull();
    expect(trigger!.className).toBe(canvasChromeCompactButtonClass(false));
    expect(trigger!.textContent).toBe('');
    expect(trigger!.getAttribute('aria-label')).toBeTruthy();
  });

  it('puts the relations trigger in the settings group, with no divider between the two', () => {
    // Both are about THIS workflow rather than about the view, so they share one group; a
    // separator between them would draw a boundary that is not there.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const trigger = container.querySelector<HTMLElement>('[data-testid="canvas-toolbar-relations"]');
    const settings = screen.getByTitle('settings');

    expect(trigger!.parentElement).toBe(settings.parentElement);
    // One divider on the group's right edge, and none inside it.
    expect(settings.parentElement!.className).toContain('border-r');
    expect(trigger!.previousElementSibling).toBe(settings);
  });

  it('keeps the run-follow toggle on the toolbar in BOTH modes, beside Focus', () => {
    // A run-only feature whose only control disappears during a run is unusable, and
    // counting buttons cannot see it: wrapping the View-controls group in a run-mode
    // condition leaves every count unchanged. The toggle removes ITSELF in edit mode
    // (it reads the mode directly), which is why the toolbar must not decide for it.
    for (const isRunMode of [false, true]) {
      const { container, unmount } = render(<CanvasToolbar {...baseProps()} isRunMode={isRunMode} />);
      const toggle = container.querySelector('[data-testid="canvas-toggle-run-follow"]');
      expect(toggle, `run mode: ${isRunMode}`).not.toBeNull();
      // Beside Focus, not adrift in another group.
      expect(toggle!.previousElementSibling?.getAttribute('title')).toBe('fitView');
      unmount();
    }
  });

  it('offers the relations menu in BOTH modes, and never in a preview', () => {
    // "What calls this?" is asked while reading a run as much as while editing, so the control
    // is not run/edit gated - only `showRelations` (false in the marketplace preview) takes it away.
    for (const isRunMode of [false, true]) {
      const { container, unmount } = render(<CanvasToolbar {...baseProps()} isRunMode={isRunMode} />);
      expect(container.querySelector('[data-testid="canvas-toolbar-relations"]'), `run mode: ${isRunMode}`).not.toBeNull();
      unmount();
    }
    const { container } = render(<CanvasToolbar {...baseProps()} showRelations={false} />);
    expect(container.querySelector('[data-testid="canvas-toolbar-relations"]')).toBeNull();
  });

  it('leaves nothing round behind, in either mode', () => {
    for (const isRunMode of [false, true]) {
      const { container, unmount } = render(<CanvasToolbar {...baseProps()} isRunMode={isRunMode} showRunControls={isRunMode} />);
      expect(container.innerHTML, `run mode: ${isRunMode}`).not.toContain('rounded-full');
      unmount();
    }
  });

  it('paints the lock button as active only while the lock is focused', () => {
    const { rerender } = render(<CanvasToolbar {...baseProps()} />);
    const lock = () => screen.getByTitle('disableInteractivity');
    expect(lock().className).toBe(canvasChromeCompactButtonClass(false));

    rerender(<CanvasToolbar {...baseProps()} isLockFocused />);
    expect(lock().className).toBe(canvasChromeCompactButtonClass(true));
  });

  it('paints the settings button as active only while the settings panel is open', () => {
    const { rerender } = render(<CanvasToolbar {...baseProps()} />);
    const settings = () => screen.getByTitle('settings');
    expect(settings().className).toBe(canvasChromeCompactButtonClass(false));

    rerender(<CanvasToolbar {...baseProps()} isSettingsOpen />);
    expect(settings().className).toBe(canvasChromeCompactButtonClass(true));
  });

  it('bounds its width by the CANVAS, never the viewport', () => {
    // `100vw` ignored the side panel: with one open the toolbar could be wider
    // than the canvas it belongs to and run underneath the panel.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const panel = screen.getByTestId('canvas-toolbar-panel');
    expect(panel.className).toContain('max-w-[calc(100%-1rem)]');
    expect(container.innerHTML).not.toContain('100vw');
  });

  it('scrolls horizontally rather than clipping controls on a narrow canvas', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    expect(card(container).className).toContain('overflow-x-auto');
    expect(card(container).className).toContain('max-w-full');
  });

  it('keeps the cursor-mode select at the same height and shape as the buttons', () => {
    // It is a Radix trigger, so it cannot take the helper directly - which is
    // exactly why its geometry has to be pinned against the helper's. A taller
    // select would push the card past the shared chrome height on its own.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const select = screen.getByTestId('canvas-cursor-mode-select');
    expect(select.className).toContain('h-7');
    expect(select.className).toContain('min-h-7');
    expect(select.className).not.toContain('h-9');
    expect(select.className).toContain('rounded-xl');
    expect(select.className).not.toContain('rounded-full');
    expect(card(container).className).toContain('items-center');
  });

  it('lands on the shared chrome height, like the mode toggle and the run bar', () => {
    // p-1 (4+4) around 28px controls = 36px. The toolbar used to be a 48px slab
    // beside a 36px run bar, which is what made it read as a different size.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    expect(card(container).className).toContain('p-1');
    expect(card(container).className).not.toContain('py-1.5');
    expect(CANVAS_CHROME_CARD_PADDING_PX * 2 + 28).toBe(CANVAS_CHROME_TOP_ROW_HEIGHT_PX);
    for (const button of iconButtons(container)) {
      expect(button.className, `${button.getAttribute('title')} is not a compact control`).toContain('h-7');
    }
  });
});
