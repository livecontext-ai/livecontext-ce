// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Pins the composer-ready ModelSelectorDropdown contract:
//  - the stable data-model-selector wrapper stays around the composer trigger,
//  - the reasoning-effort control renders ONLY when onReasoningEffortChange is
//    supplied and the provider supports effort,
//  - the effort control is tagged data-model-selector-keep-open (so interacting
//    with it doesn't close the model menu),
//  - the menu is PORTALLED to <body> with fixed positioning, so the composer's
//    overflow-hidden bubble can't clip it,
//  - placement is adaptive (below when there's room beneath the trigger, above
//    when the composer is docked at the bottom),
//  - a zero-size trigger (the display:none mobile composer copy in the welcome
//    view) renders NO menu, so no stray menu is pinned at the viewport's 0,0,
//  - the trigger model name uses the primary text color.
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
  supportsReasoningEffort: () => true,
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

// jsdom does not lay out, so getBoundingClientRect() returns all-zeros by default.
// The component now uses the trigger's rect to decide visibility + placement, so
// every test installs a controllable rect (and a fixed viewport) before render.
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

let triggerRect: DOMRect;
let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  // Default: a normal, visible, mid-page trigger so the menu renders.
  triggerRect = makeRect({ top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32 });
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => triggerRect);
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

const baseProps = {
  showModelSelector: true,
  setShowModelSelector: vi.fn(),
  selectedModel: { provider: 'anthropic', id: 'claude' },
  selectedModelData: { name: 'Claude', id: 'claude' },
  availableModels: [
    { provider: 'anthropic', id: 'claude', name: 'Claude', iconSlug: 'anthropic' } as never,
  ],
  setSelectedModel: vi.fn(),
  changeModelTitle: 'Change model',
};

describe('ModelSelectorDropdown - composer-ready behavior', () => {
  it('keeps the stable data-model-selector wrapper around the composer trigger', () => {
    render(<ModelSelectorDropdown {...baseProps} showModelSelector={false} />);
    const wrapper = document.querySelector('[data-model-selector]');
    expect(wrapper).not.toBeNull();
    expect(wrapper).toHaveTextContent('Claude');
  });

  it('renders the reasoning-effort control when onReasoningEffortChange + provider support are present', () => {
    render(
      <ModelSelectorDropdown
        {...baseProps}
        reasoningEffort=""
        onReasoningEffortChange={vi.fn()}
        reasoningEffortLabel="Reasoning effort"
        effortAutoLabel="Auto"
      />,
    );
    const label = screen.getByText('Reasoning effort');
    expect(label).toBeInTheDocument();
    // The effort row must be keep-open so opening it doesn't dismiss the model menu.
    expect(label.closest('[data-model-selector-keep-open]')).not.toBeNull();
  });

  it('omits the effort control when onReasoningEffortChange is not supplied', () => {
    render(<ModelSelectorDropdown {...baseProps} reasoningEffortLabel="Reasoning effort" />);
    expect(screen.queryByText('Reasoning effort')).not.toBeInTheDocument();
  });

  it('portals the menu to <body> with fixed positioning (not clipped by the composer)', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const menu = screen.getByTestId('model-selector-menu');
    expect(menu.className).toContain('fixed');
    // Portalled directly under document.body, escaping the composer's overflow-hidden.
    expect(menu.parentElement).toBe(document.body);
  });

  it('renders the trigger model name in the PRIMARY text color (not secondary)', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const trigger = screen.getByTitle('Change model');
    expect(trigger.className).toContain('text-theme-primary');
    expect(trigger.className).not.toContain('text-theme-secondary');
  });
});

describe('ModelSelectorDropdown - adaptive placement', () => {
  it('opens BELOW the trigger when the composer sits high on the page (welcome view)', () => {
    // Composer anchored near the top (welcome view at ~22vh): plenty of room below.
    triggerRect = makeRect({ top: 300, bottom: 332, left: 600, right: 720, width: 120, height: 32 });
    render(<ModelSelectorDropdown {...baseProps} />);
    const menu = screen.getByTestId('model-selector-menu');
    expect(menu.style.top).toBe('340px'); // rect.bottom (332) + GAP (8)
    expect(menu.style.bottom).toBe('');
  });

  it('opens ABOVE the trigger when the composer is docked at the bottom (active conversation)', () => {
    // Composer at the bottom of the viewport: no room below, so open upward.
    triggerRect = makeRect({ top: 850, bottom: 882, left: 600, right: 720, width: 120, height: 32 });
    render(<ModelSelectorDropdown {...baseProps} />);
    const menu = screen.getByTestId('model-selector-menu');
    expect(menu.style.bottom).toBe('58px'); // innerHeight (900) - rect.top (850) + GAP (8)
    expect(menu.style.top).toBe('');
  });

  it('left-clamps the menu so a right-anchored trigger never overflows the viewport edge', () => {
    // Trigger far right: left must clamp to innerWidth - MENU_WIDTH(320) - MARGIN(16).
    triggerRect = makeRect({ top: 600, bottom: 632, left: 1380, right: 1400, width: 20, height: 32 });
    render(<ModelSelectorDropdown {...baseProps} />);
    const menu = screen.getByTestId('model-selector-menu');
    expect(menu.style.left).toBe('1064px'); // 1400 - 320 - 16
  });
});

describe('ModelSelectorDropdown - hidden (zero-size) trigger', () => {
  it('renders NO menu when the trigger has a zero-size rect (display:none mobile composer copy)', () => {
    // The welcome view renders the composer twice; the hidden mobile copy reports
    // an all-zero rect. Its instance must not pin a stray menu at the 0,0 origin.
    triggerRect = makeRect({}); // width: 0, height: 0
    render(<ModelSelectorDropdown {...baseProps} />);
    expect(screen.queryByTestId('model-selector-menu')).not.toBeInTheDocument();
  });
});
