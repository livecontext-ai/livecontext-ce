/**
 * @vitest-environment jsdom
 *
 * ModelInfo stacking regressions - everything this file portals to
 * document.body must paint ABOVE its hosts, which are themselves portalled
 * far above the app:
 *   - composer model menu (ModelSelectorDropdown)  z-[10000]
 *   - composer Options popover (AttachmentHandler) z-[99999]
 *   - ModelPicker SelectContents                   z-[100000]
 *   - the (i) ModelInfoPopover card                z-[100001]
 *   - hover tooltips (tier/capability/star/...)    z-[100002]
 * The shared ui defaults (PopoverContent z-50, TooltipContent z-[9999]) sat
 * BELOW those hosts, so the (i) card and the hover tooltips painted BEHIND
 * the menus (the reported bug for the card).
 *
 * These tests render the REAL ui primitives (Radix + cn/tailwind-merge): the
 * fix only works if twMerge resolves the z-* conflict in favour of the
 * override, so the assertions check the MERGED class list on the portalled
 * element - override present AND the ui default gone.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel } from '@/hooks/useModels';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

beforeAll(() => {
  // Radix positioning (@floating-ui) needs ResizeObserver, absent from jsdom.
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
});

import { ModelInfoPopover, TierBadge } from '../ModelInfo';

const model: AIModel = {
  id: 'claude-haiku-4-5',
  name: 'Claude Haiku 4.5',
  provider: 'anthropic',
};

describe('ModelInfo - portalled surfaces stack above their host menus', () => {
  afterEach(() => cleanup());

  it('opens the (i) card at z-[100001] with the z-50 popover default merged away', () => {
    render(<ModelInfoPopover model={model} />);

    fireEvent.click(screen.getByRole('button', { name: 'infoTooltip' }));

    const card = screen.getByRole('dialog');
    expect(card).toHaveClass('z-[100001]');
    expect(card).not.toHaveClass('z-50');
  });

  it('opens hover tooltips at z-[100002] with the z-[9999] tooltip default merged away', async () => {
    render(<TierBadge tier="top" />);

    fireEvent.focus(screen.getByText('tier.top'));

    const rendered = await screen.findAllByText('tierTooltip.top');
    expect(rendered.some(el => el.className.includes('z-[100002]'))).toBe(true);
    expect(rendered.every(el => !el.className.includes('z-[9999]'))).toBe(true);
  });
});
