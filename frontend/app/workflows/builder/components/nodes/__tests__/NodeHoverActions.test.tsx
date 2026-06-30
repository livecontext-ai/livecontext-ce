// @vitest-environment jsdom
/**
 * NodeBottomBar hover behavior: the WHOLE bar (persistent contextual buttons,
 * play, slots, delete/duplicate) is revealed only while the node is hovered
 * (`hover` prop from useHoverVisibility) - every bottom button shares the same
 * hover-to-reveal logic. Delete/duplicate (`hoverActions`) additionally hide in
 * run / preview-only mode.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';
import { NodeBottomBar, BTN_CLS } from '../NodeBottomBar';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const messages: Record<string, string> = {
      deleteNode: 'Delete node',
      duplicateNode: 'Duplicate node',
    };
    return messages[key] ?? key;
  },
}));

// Mutable mode so each test can flip edit / run / preview-only.
const mode = { isRunMode: false, isPreviewOnly: false };
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mode,
}));

afterEach(() => cleanup());
beforeEach(() => {
  mode.isRunMode = false;
  mode.isPreviewOnly = false;
});

const actions = (overrides: Partial<{ onDelete: () => void; onDuplicate: () => void }> = {}) => ({
  onDelete: vi.fn(),
  onDuplicate: vi.fn(),
  ...overrides,
});

const tableButton = { key: 'table', icon: <span>T</span>, title: 'processed_emails', onClick: vi.fn() };

describe('NodeBottomBar hover-to-reveal (all buttons share the same hover logic)', () => {
  it('renders the delete and duplicate buttons in edit mode with the SAME round style as the other buttons (BTN_CLS + status border)', () => {
    const { getByTitle } = render(
      <NodeBottomBar borderColor="rgb(239, 68, 68)" isRunning={false} hover={{ isVisible: true }} hoverActions={actions()} />,
    );
    for (const title of ['Delete node', 'Duplicate node']) {
      const btn = getByTitle(title);
      BTN_CLS.split(/\s+/).forEach((cls) => expect(btn.className).toContain(cls));
      expect(btn.style.borderColor).toBe('rgb(239, 68, 68)');
      expect(btn.style.borderWidth).toBe('2px');
    }
  });

  it('renders the bar when ONLY delete/duplicate exist (no persistent buttons / play / slots)', () => {
    const { getByTitle } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} hover={{ isVisible: true }} hoverActions={actions()} />,
    );
    expect(getByTitle('Delete node')).toBeTruthy();
  });

  it('hides the WHOLE bar until the node is hovered - persistent buttons included (opacity 0, every button click-through)', () => {
    const { getByTitle, container } = render(
      <NodeBottomBar
        borderColor="#10b981"
        isRunning={false}
        buttons={[tableButton]}
        hover={{ isVisible: false }}
        hoverActions={actions()}
      />,
    );
    const row = container.firstChild as HTMLElement;
    expect(row.style.opacity).toBe('0');
    expect(row.className).toContain('pointer-events-none');
    for (const title of ['processed_emails', 'Delete node', 'Duplicate node']) {
      expect(getByTitle(title).className).toContain('pointer-events-none');
      expect(getByTitle(title).className).not.toContain('pointer-events-auto');
    }
  });

  it('reveals the whole bar on hover - every button visible and interactive with the same logic', () => {
    const { getByTitle, container } = render(
      <NodeBottomBar
        borderColor="#10b981"
        isRunning={false}
        buttons={[tableButton]}
        hover={{ isVisible: true }}
        hoverActions={actions()}
      />,
    );
    const row = container.firstChild as HTMLElement;
    expect(row.style.opacity).toBe('1');
    for (const title of ['processed_emails', 'Delete node', 'Duplicate node']) {
      expect(getByTitle(title).className).toContain('pointer-events-auto');
    }
  });

  it('stays always visible when no hover config is provided (back-compat default)', () => {
    const { getByTitle, container } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} buttons={[tableButton]} />,
    );
    const row = container.firstChild as HTMLElement;
    expect(row.style.opacity).toBe('1');
    expect(getByTitle('processed_emails').className).toContain('pointer-events-auto');
  });

  it('does NOT render delete/duplicate in run mode (persistent buttons keep rendering)', () => {
    mode.isRunMode = true;
    const { queryByTitle, getByTitle } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} buttons={[tableButton]} hover={{ isVisible: true }} hoverActions={actions()} />,
    );
    expect(queryByTitle('Delete node')).toBeNull();
    expect(queryByTitle('Duplicate node')).toBeNull();
    expect(getByTitle('processed_emails')).toBeTruthy();
  });

  it('does NOT render delete/duplicate in preview-only mode', () => {
    mode.isPreviewOnly = true;
    const { queryByTitle } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} hover={{ isVisible: true }} hoverActions={actions()} />,
    );
    expect(queryByTitle('Delete node')).toBeNull();
  });

  it('keeps every button in ONE centered in-flow row (no one-sided absolute extension)', () => {
    const { getByTitle, container } = render(
      <NodeBottomBar
        borderColor="#10b981"
        isRunning={false}
        buttons={[tableButton]}
        hover={{ isVisible: true }}
        hoverActions={actions()}
      />,
    );
    const row = container.firstChild as HTMLElement;
    expect(row.style.transform).toBe('translateX(-50%)');
    // Delete/duplicate are direct flex children of the centered row, like the
    // persistent buttons - not wrapped in an absolutely-positioned extension.
    expect(getByTitle('Delete node').parentElement).toBe(row);
    expect(getByTitle('processed_emails').parentElement).toBe(row);
    expect(row.className).not.toContain('left-full');
  });

  it('fires onDelete / onDuplicate without letting the click bubble to the node', () => {
    const onDelete = vi.fn();
    const onDuplicate = vi.fn();
    const onParentClick = vi.fn();
    const { getByTitle } = render(
      <div onClick={onParentClick}>
        <NodeBottomBar borderColor="#10b981" isRunning={false} hover={{ isVisible: true }} hoverActions={actions({ onDelete, onDuplicate })} />
      </div>,
    );
    fireEvent.click(getByTitle('Delete node'));
    fireEvent.click(getByTitle('Duplicate node'));
    expect(onDelete).toHaveBeenCalledTimes(1);
    expect(onDuplicate).toHaveBeenCalledTimes(1);
    expect(onParentClick).not.toHaveBeenCalled();
  });

  it('keeps the hover visibility alive while the pointer is over the bar (onHover on mouse-enter)', () => {
    const onHover = vi.fn();
    const { container } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} hover={{ isVisible: true, onHover }} hoverActions={actions()} />,
    );
    fireEvent.mouseEnter(container.firstChild as HTMLElement);
    expect(onHover).toHaveBeenCalled();
  });

  it('renders only the handlers that are provided (delete without duplicate)', () => {
    const { getByTitle, queryByTitle } = render(
      <NodeBottomBar
        borderColor="#10b981"
        isRunning={false}
        hover={{ isVisible: true }}
        hoverActions={{ onDelete: vi.fn() }}
      />,
    );
    expect(getByTitle('Delete node')).toBeTruthy();
    expect(queryByTitle('Duplicate node')).toBeNull();
  });

  it('renders nothing at all when there is no persistent content and no delete/duplicate handlers', () => {
    const { container } = render(
      <NodeBottomBar borderColor="#10b981" isRunning={false} hover={{ isVisible: true }} hoverActions={{}} />,
    );
    expect(container.firstChild).toBeNull();
  });
});
