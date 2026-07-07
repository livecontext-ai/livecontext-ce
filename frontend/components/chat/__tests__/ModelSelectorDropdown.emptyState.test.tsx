// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Pins the empty-catalog contract of the composer model selector:
//  - with ZERO models and no selection, the trigger shows the caller-provided
//    noModelsLabel instead of collapsing to a blank chevron,
//  - the open menu renders the caller-injected emptyState node (the composers
//    inject <NoProviderCta variant='menu' />) instead of an empty list,
//  - with models present, neither the label nor the emptyState leak in,
//  - both props stay OPTIONAL so the component remains translation-free and
//    provider-less renders (the panels' tests) keep working unchanged.
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: () => null,
  ModelInfoPopover: () => null,
}));
vi.mock('@/lib/ai-providers/reasoningEffort', () => ({
  REASONING_EFFORT_LEVELS: ['low', 'high'],
  supportsReasoningEffort: () => false,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children, ...p }: { children: React.ReactNode }) => <button {...p}>{children}</button>,
  SelectValue: () => <span>value</span>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SELECT_EMPTY_VALUE_SENTINEL: '__empty__',
}));

import { ModelSelectorDropdown } from '../ModelSelectorDropdown';

const makeRect = (r: Partial<DOMRect>): DOMRect =>
  ({
    x: r.left ?? 0,
    y: r.top ?? 0,
    top: r.top ?? 0,
    left: r.left ?? 0,
    bottom: r.bottom ?? 0,
    right: r.right ?? 0,
    width: r.width ?? 0,
    height: r.height ?? 0,
    toJSON: () => ({}),
  }) as DOMRect;

let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  const triggerRect = makeRect({ top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32 });
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => triggerRect);
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

const emptyProps = {
  showModelSelector: true,
  setShowModelSelector: vi.fn(),
  selectedModel: { provider: '', id: '' },
  selectedModelData: undefined,
  availableModels: [] as never[],
  setSelectedModel: vi.fn(),
  changeModelTitle: 'Change model',
};

describe('ModelSelectorDropdown - empty catalog', () => {
  it('shows the noModelsLabel on the trigger instead of a blank button', () => {
    render(
      <ModelSelectorDropdown {...emptyProps} showModelSelector={false} noModelsLabel="No models" />,
    );
    expect(screen.getByTitle('Change model')).toHaveTextContent('No models');
  });

  it('renders the injected emptyState inside the open menu when the list is empty', () => {
    render(
      <ModelSelectorDropdown
        {...emptyProps}
        emptyState={<div data-testid="injected-empty-state" />}
      />,
    );
    expect(screen.getByTestId('model-selector-menu')).toBeInTheDocument();
    expect(screen.getByTestId('injected-empty-state')).toBeInTheDocument();
  });

  it('does NOT render the emptyState or the label when models exist', () => {
    render(
      <ModelSelectorDropdown
        {...emptyProps}
        availableModels={[
          { provider: 'anthropic', id: 'claude', name: 'Claude', iconSlug: 'anthropic' } as never,
        ]}
        selectedModelData={{ name: 'Claude', id: 'claude' }}
        noModelsLabel="No models"
        emptyState={<div data-testid="injected-empty-state" />}
      />,
    );
    expect(screen.queryByTestId('injected-empty-state')).toBeNull();
    expect(screen.getByTitle('Change model')).toHaveTextContent('Claude');
    expect(screen.getByTitle('Change model')).not.toHaveTextContent('No models');
  });

  it('keeps rendering without either prop (composer back-compat, blank trigger as before)', () => {
    render(<ModelSelectorDropdown {...emptyProps} showModelSelector={false} />);
    expect(document.querySelector('[data-model-selector]')).not.toBeNull();
  });
});
