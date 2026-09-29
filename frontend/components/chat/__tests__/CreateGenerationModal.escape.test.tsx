/**
 * @vitest-environment jsdom
 *
 * Escape pressed the instant a generation's result is on screen closes the dialog.
 *
 * <p>The Escape handler is registered once and calls `dismissRef.current`, which refuses while a
 * generation runs. The result and `running = false` come from the same render, so if the ref
 * is refreshed by a PASSIVE effect it still holds the running-time `dismiss` right after the
 * commit that shows the result: an Escape in that gap is swallowed (preventDefault +
 * stopPropagation) and refused, and the dialog stays open. Same gap as CreateWorkflowModal's.
 *
 * <p>A MutationObserver fires right after the commit, before React 19 runs passive effects, so
 * pressing Escape from it lands in the gap every time. (If a future React flushed passive
 * effects in the same task as the commit, this test would pass without the fix too.)
 *
 * <p>Collaborators are stubbed exactly as in CreateGenerationModal.analytics.test.tsx.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as React from 'react';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

const mocks = vi.hoisted(() => ({
  track: vi.fn(),
  execute: vi.fn(),
  invalidateHistory: vi.fn(),
}));

const FLUX = {
  model: 'flux-1.1-pro', kind: 'image', label: 'Flux 1.1 Pro', provider: 'flux', iconSlug: null,
  apiToolId: 't-flux', integrationName: 'flux', accepts: ['prompt'], required: [],
} as unknown as GenerationModel;
const DALLE = {
  model: 'gpt-image-1', kind: 'image', label: 'GPT Image', provider: 'openai', iconSlug: null,
  apiToolId: 't-openai', integrationName: 'openai', accepts: ['prompt'], required: [],
} as unknown as GenerationModel;

vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => mocks.track(...a) }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: undefined, isLoading: false }),
  useQueries: ({ queries }: { queries: unknown[] }) => queries.map(() => ({ data: undefined })),
}));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { execute: (...a: unknown[]) => mocks.execute(...a) },
}));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [FLUX, DALLE], isLoading: false }),
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/hooks/useGenerationHistory', () => ({ useInvalidateGenerationHistory: () => mocks.invalidateHistory }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({ useMonthlyCreditsCannotPay: () => ({ blocked: false }) }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => null }));
vi.mock('@/components/generation/GenerationHistoryList', () => ({ GenerationHistoryList: () => null }));
vi.mock('@/app/workflows/builder/components/inspector/CredentialSection', () => ({ CredentialSection: () => null }));
vi.mock('@/components/billing/UpgradeRequiredBadge', () => ({
  UpgradeRequiredBadge: () => null, UpgradeRequiredNotice: () => null,
}));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPlatformCredentialPublicInfo: vi.fn() } }));

// Each Select becomes a group of buttons, one per item, that hand the item's value to the
// Select's own onValueChange: the same callback a real pick reaches.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<((v: string) => void) | undefined>(undefined);
  return {
    Select: ({ onValueChange, children }: { onValueChange?: (v: string) => void; children: React.ReactNode }) => (
      <Ctx.Provider value={onValueChange}>{children}</Ctx.Provider>
    ),
    SelectTrigger: ({ id }: { id?: string }) => <span data-testid={id} />,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value }: { value: string }) => {
      const onValueChange = ReactModule.useContext(Ctx);
      return <button type="button" onClick={() => onValueChange?.(value)}>{`pick:${value}`}</button>;
    },
  };
});

import { CreateGenerationModal } from '../CreateGenerationModal';
import { ApiError } from '@/lib/api/api-client';

beforeEach(() => {
  Object.values(mocks).forEach((fn) => fn.mockReset());
});
afterEach(cleanup);

/**
 * Open on images, start a Flux generation, and press Escape from a MutationObserver the moment
 * `visibleText` (what the finished step shows) is in the DOM: right after that commit, before
 * React runs its passive effects.
 */
async function escapeTheInstantItShows(visibleText: string) {
  const onClose = vi.fn();
  render(<CreateGenerationModal isOpen onClose={onClose} initialKind="image" />);
  fireEvent.click(screen.getByText('pick:flux-1.1-pro'));
  const generate = screen.getByText('generate').closest('button')!;
  await waitFor(() => expect(generate.hasAttribute('disabled')).toBe(false));

  let pressed: KeyboardEvent | null = null;
  const observer = new MutationObserver(() => {
    if (pressed || !screen.queryByText(visibleText)) return;
    pressed = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
    document.dispatchEvent(pressed);
  });
  observer.observe(document.body, { childList: true, subtree: true });
  try {
    fireEvent.click(generate);
    await waitFor(() => expect(pressed, `"${visibleText}" never appeared`).not.toBeNull());
  } finally {
    observer.disconnect();
  }
  return { pressed: pressed!, onClose };
}

describe('CreateGenerationModal Escape', () => {
  it('closes on Escape pressed the instant the result is on screen, not one effect later', async () => {
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'flux-1.1-pro', kind: 'image', provider: 'flux' } });

    const { pressed, onClose } = await escapeTheInstantItShows('openInFiles');

    expect(pressed.defaultPrevented, 'the dialog did not take the key').toBe(true);
    expect(onClose, 'Escape pressed as the result appeared was refused').toHaveBeenCalled();
  });

  // The failure paths clear `running` in the same render as the message, like the success
  // path: the same gap, pinned here rather than argued from the code.
  it('closes on Escape pressed the instant a refusal is on screen', async () => {
    mocks.execute.mockResolvedValue({ success: false, error: 'Not enough credits for this model' });

    const { onClose } = await escapeTheInstantItShows('Not enough credits for this model');

    expect(onClose, 'Escape pressed as the refusal appeared was refused').toHaveBeenCalled();
  });

  it('closes on Escape pressed the instant a failed call is on screen', async () => {
    mocks.execute.mockRejectedValue(new ApiError('The generation service failed', 500));

    const { onClose } = await escapeTheInstantItShows('The generation service failed');

    expect(onClose, 'Escape pressed as the error appeared was refused').toHaveBeenCalled();
  });
});
