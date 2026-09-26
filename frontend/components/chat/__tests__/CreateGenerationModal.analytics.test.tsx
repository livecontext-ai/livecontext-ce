/**
 * @vitest-environment jsdom
 *
 * The "Create" modal reports the same two studio events as the Studio page, tagged with
 * `entry_point: 'modal'`: a model the reader picked, and a submitted generation once its outcome
 * is known. A model put in place by a recipe is not a pick, and is not reported.
 *
 * The select is replaced by plain buttons (Radix portals cannot be driven in jsdom), and every
 * price / credential / history collaborator by a stub: what is under test is the modal's own glue
 * between the reader's action and the event.
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

const eventsNamed = (event: string) => mocks.track.mock.calls.filter(([name]) => name === event);

function openOnImages() {
  render(<CreateGenerationModal isOpen onClose={() => {}} initialKind="image" />);
}

async function pickFluxAndGenerate() {
  fireEvent.click(screen.getByText('pick:flux-1.1-pro'));
  const generate = screen.getByText('generate').closest('button')!;
  await waitFor(() => expect(generate.hasAttribute('disabled')).toBe(false));
  fireEvent.click(generate);
  await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
}

describe('CreateGenerationModal studio analytics', () => {
  it('a model picked in the list is reported as studio_model_selected with entry_point modal', () => {
    openOnImages();

    fireEvent.click(screen.getByText('pick:flux-1.1-pro'));

    expect(eventsNamed('studio_model_selected')).toEqual([
      ['studio_model_selected', { model: 'flux-1.1-pro', kind: 'image', provider: 'flux', entry_point: 'modal' }],
    ]);
  });

  it('a provider pick lands on its first model and reports that model', () => {
    openOnImages();

    fireEvent.click(screen.getByText('pick:openai'));

    expect(eventsNamed('studio_model_selected')).toEqual([
      ['studio_model_selected', { model: 'gpt-image-1', kind: 'image', provider: 'openai', entry_point: 'modal' }],
    ]);
  });

  it('a model restored from a recipe is not reported as a pick', () => {
    render(
      <CreateGenerationModal
        isOpen
        onClose={() => {}}
        initialRecipe={{ model: 'flux-1.1-pro', kind: 'image', prompt: 'a lighthouse' } as never}
      />,
    );

    expect(eventsNamed('studio_model_selected')).toEqual([]);
  });

  it('a successful generation is reported once, with the payer the run was sent with and outcome success', async () => {
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'flux-1.1-pro', kind: 'image', provider: 'flux' } });
    openOnImages();

    await pickFluxAndGenerate();

    // No published price in this stub, so the modal pays with the reader's own key: the event
    // must state the payer the request actually carried, whichever it is.
    const sentPayer = mocks.execute.mock.calls[0][0].credential_source;
    expect(['platform', 'user']).toContain(sentPayer);
    await waitFor(() => expect(eventsNamed('studio_generation_submitted')).toEqual([
      ['studio_generation_submitted', {
        model: 'flux-1.1-pro', kind: 'image', provider: 'flux',
        credential_source: sentPayer, outcome: 'success', entry_point: 'modal',
      }],
    ]));
  });

  it('a gateway that gave up (504) is reported as lost, not failed', async () => {
    mocks.execute.mockRejectedValue(new ApiError('HTTP 504: ', 504));
    openOnImages();

    await pickFluxAndGenerate();

    await waitFor(() => expect(eventsNamed('studio_generation_submitted')).toEqual([
      ['studio_generation_submitted', expect.objectContaining({ outcome: 'lost', entry_point: 'modal' })],
    ]));
  });

  it('a 4xx the server chose to send is reported as refused', async () => {
    mocks.execute.mockRejectedValue(new ApiError('bad request', 400));
    openOnImages();

    await pickFluxAndGenerate();

    await waitFor(() => expect(eventsNamed('studio_generation_submitted')).toEqual([
      ['studio_generation_submitted', expect.objectContaining({ outcome: 'refused', entry_point: 'modal' })],
    ]));
  });
});
