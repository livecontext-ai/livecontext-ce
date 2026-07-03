/**
 * @vitest-environment jsdom
 *
 * ModelPicker stacking regression - the picker can be hosted inside the
 * composer Options popover (AttachmentHandler, z-[99999]). Radix Select
 * portals its list to document.body, so with the default SelectContent
 * z-[10001] the provider/model lists paint BEHIND that host (the reported
 * bug: toggling compaction ON then opening the summariser provider list
 * showed the list underneath the Options panel). Both SelectContents must
 * therefore carry z-[100000], the same "beat the Options popover"
 * convention as ChatConfigPanel's own Selects.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

vi.mock('next/image', () => ({ default: () => null }));
// Inline the Radix Select and capture the className handed to SelectContent -
// the portal/positioning internals are irrelevant to the stacking contract.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children, className }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="select-content" className={className}>{children}</div>
  ),
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => <span>{model.id}</span>,
  ModelInfoPopover: () => null,
}));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: [
        {
          name: 'anthropic',
          defaultModel: 'claude-haiku-4-5',
          supportsStreaming: true,
          supportsToolCalling: true,
          displayOrder: 1,
          models: [
            { id: 'claude-haiku-4-5', name: 'claude-haiku-4-5', provider: 'anthropic' } as AIModel,
          ],
        } as AIProvider,
      ],
      defaultProvider: 'anthropic',
      defaultModel: 'claude-haiku-4-5',
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

describe('ModelPicker - dropdown stacking above the composer Options popover', () => {
  afterEach(() => cleanup());

  it('gives BOTH SelectContents (provider + model) z-[100000] so they paint above the z-[99999] Options popover host', () => {
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={() => {}} />);
    const contents = screen.getAllByTestId('select-content');
    expect(contents).toHaveLength(2);
    for (const content of contents) {
      expect(content).toHaveClass('z-[100000]');
    }
  });
});
