// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const messages: Record<string, string> = {
      deleteNode: 'Delete node',
      duplicateNode: 'Duplicate node',
    };
    return messages[key] ?? key;
  },
}));

const mode = { isRunMode: false, isPreviewOnly: false };
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mode,
}));

// shared.tsx pulls UI/provider helpers unrelated to NodeActionButtons - stub them.
vi.mock('@/components/ThemeProvider', () => ({ useTheme: () => ({ theme: 'light', resolvedTheme: 'light' }) }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/hooks/useModels', () => ({ getEffectiveDefaultProvider: () => 'openai' }));
vi.mock('@/lib/ai-providers/providerIcons', () => ({ getProviderIconSlug: () => 'openai' }));

import { NodeActionButtons } from '../shared';
import { BTN_CLS } from '../NodeBottomBar';

afterEach(() => cleanup());
beforeEach(() => {
  mode.isRunMode = false;
  mode.isPreviewOnly = false;
});

describe('NodeActionButtons placement (hover delete / duplicate moved BELOW the node)', () => {
  it('positions the hover buttons below the node (top: calc(100% + 8px)), not above it', () => {
    const { getByTitle } = render(
      <NodeActionButtons isVisible onDelete={vi.fn()} onDuplicate={vi.fn()} />,
    );
    const container = getByTitle('Delete node').parentElement as HTMLElement;
    expect(container.style.top).toBe('calc(100% + 8px)');
    // The legacy top placement translated the row above the node - gone.
    expect(container.style.transform).toBe('translateX(-50%)');
    expect(container.style.transform).not.toContain('-100%');
  });

  it('styles the buttons exactly like the persistent bottom-bar buttons (BTN_CLS + 2px var(--border-color) border)', () => {
    const { getByTitle } = render(
      <NodeActionButtons isVisible onDelete={vi.fn()} onDuplicate={vi.fn()} />,
    );
    for (const title of ['Delete node', 'Duplicate node']) {
      const btn = getByTitle(title);
      BTN_CLS.split(/\s+/).forEach((cls) => expect(btn.className).toContain(cls));
      expect(btn.style.borderWidth).toBe('2px');
      expect(btn.style.borderColor).toBe('var(--border-color)');
    }
  });

  it('still hides the edit buttons entirely in run mode', () => {
    mode.isRunMode = true;
    const { container } = render(
      <NodeActionButtons isVisible onDelete={vi.fn()} onDuplicate={vi.fn()} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('still hides the edit buttons entirely in preview-only mode', () => {
    mode.isPreviewOnly = true;
    const { container } = render(
      <NodeActionButtons isVisible onDelete={vi.fn()} onDuplicate={vi.fn()} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('keeps the row transparent and click-through until hovered', () => {
    const { getByTitle } = render(
      <NodeActionButtons isVisible={false} onDelete={vi.fn()} />,
    );
    const container = getByTitle('Delete node').parentElement as HTMLElement;
    expect(container.style.opacity).toBe('0');
    expect(container.style.pointerEvents).toBe('none');
  });
});
