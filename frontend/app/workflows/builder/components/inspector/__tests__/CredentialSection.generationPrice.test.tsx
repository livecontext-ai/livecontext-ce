// @vitest-environment jsdom
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { AbstractIntlMessages } from 'use-intl';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator';

const apiMocks = vi.hoisted(() => ({
  getAllCredentials: vi.fn(),
  getCredentialTemplates: vi.fn(),
  getCredentialTemplateByName: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/orchestrator')>(
    '@/lib/api/orchestrator',
  );
  return {
    ...actual,
    orchestratorApi: {
      ...actual.orchestratorApi,
      getAllCredentials: apiMocks.getAllCredentials,
      getCredentialTemplates: apiMocks.getCredentialTemplates,
      getCredentialTemplateByName: apiMocks.getCredentialTemplateByName,
      getPlatformCredentialPublicInfo: apiMocks.getPlatformCredentialPublicInfo,
    },
  };
});

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false }),
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

vi.mock('@/components/credentials/CredentialWizard', async () => {
  const actual = await vi.importActual<typeof import('@/components/credentials/CredentialWizard')>(
    '@/components/credentials/CredentialWizard',
  );
  return { ...actual, CredentialWizard: () => null };
});

import { CredentialSection, describePlatformRate } from '../CredentialSection';

const messages: AbstractIntlMessages = {
  credentials: {
    configure: 'Configure credential',
    configured: 'Configured',
    selectCredential: 'Select credential',
    addNewCredential: 'Add new credential',
    manageAll: 'Manage all credentials',
    source: {
      label: 'Source',
      user: 'My credential',
      platform: 'Platform',
      markupNote: 'A small markup is billed on each call.',
      markupNoteFactor: 'The options you chose multiply that by {factor}.',
      markupNoteFactorWithReason: 'The options you chose multiply that by {factor} ({reason}).',
      markupNoteWithRate: '{rate} credits are billed on each call as markup.',
      markupNoteWithUnitRate: 'It is billed {unitRate} credits per {unit}.',
      markupNoteWithUnitRateAndTotal:
        '{unitRate} credits per {unit}, {quantity} {unit} = {total} credits for this run.',
      markupNoteNotSold: 'This generation is not sold on the platform key.',
      priceUnits: {
        call: 'call',
        second: 'second',
        minute: 'minute',
        image: 'image',
        character: 'character',
      },
      platformExplanation: 'Using platform credentials.',
    },
    toasts: { credentialCreated: 'x', credentialConfigured: 'y' },
  },
};

/**
 * A generation node charges per run and the price scales with the request, so
 * the inspector has to state the cost of the request the user actually typed.
 * These tests pin the two halves of that: which sentence is chosen for a given
 * quote, and that the quote is asked for with the model and the size currently
 * entered.
 */
describe('describePlatformRate', () => {
  it('states the rate AND the total when the quote knows the size of this run', () => {
    const note = describePlatformRate({
      integrationName: 'seedance',
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      baseCredits: '0',
      quantity: '10',
      markupCredits: '600',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteWithUnitRateAndTotal');
    expect(note.values).toEqual({
      unitRate: '60',
      unit: 'second',
      quantity: '10',
      total: '600',
    });
  });

  it('states the rate alone when no size is known yet, rather than inventing a total', () => {
    const note = describePlatformRate({
      integrationName: 'seedance',
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      markupCredits: '0',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteWithUnitRate');
    expect(note.values).toEqual({ unitRate: '60', unit: 'second' });
  });

  it('falls back to the flat per-call sentence for an ordinary priced endpoint', () => {
    const note = describePlatformRate({
      integrationName: 'gmail',
      available: true,
      hasPricing: true,
      markupCredits: '5',
      priceUnit: 'call',
      unitCredits: '0',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteWithRate');
    expect(note.values).toEqual({ rate: '5' });
  });

  it('falls back to the version-wide default when the endpoint has no resolved rate', () => {
    const note = describePlatformRate({
      integrationName: 'gmail',
      available: true,
      hasPricing: true,
      defaultMarkupCredits: '2',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteWithRate');
    expect(note.values).toEqual({ rate: '2' });
  });

  it('falls back to the generic note when nothing priced came back', () => {
    expect(describePlatformRate(undefined).key).toBe('source.markupNote');
    expect(describePlatformRate({ integrationName: 'x', available: true, hasPricing: false }).key)
      .toBe('source.markupNote');
  });

  /**
   * The number that must never be printed.
   *
   * <p>A generation whose only applicable price is the credential-wide default
   * is REFUSED at execution, so quoting the default puts the price of an
   * ordinary API lookup next to a button that will not run. The server now says
   * so outright ({@code versionDefaultOnly}); this pins that the inspector acts
   * on it instead of reaching for `defaultMarkupCredits` one line further down.
   */
  it('never quotes an amount when the only applicable price is the credential-wide default', () => {
    const note = describePlatformRate({
      integrationName: 'seedance',
      available: true,
      hasPricing: false,
      versionDefaultOnly: true,
      // Both numbers a fallback could latch onto are present on purpose.
      defaultMarkupCredits: '2',
      markupCredits: '2',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteNotSold');
    expect(note.values).toBeUndefined();
  });

  it('reads the flag alone, so no other field can talk it into quoting the default', () => {
    // This function never reads `hasPricing` at all, by design: the note is
    // rendered for a node already SAVED on the platform source, where the
    // question is what the step will do rather than whether a toggle should
    // appear. Pinned so nobody adds a `hasPricing` early-return above it and
    // silently sends the version-default case back to the flat fallback, which
    // reaches for `defaultMarkupCredits`.
    const note = describePlatformRate({
      integrationName: 'seedance',
      available: true,
      hasPricing: true,
      versionDefaultOnly: true,
      markupCredits: '2',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteNotSold');
  });

  it('ignores a zero unit rate: a per-call endpoint is not "0 credits per call"', () => {
    const note = describePlatformRate({
      integrationName: 'gmail',
      available: true,
      hasPricing: true,
      priceUnit: 'call',
      unitCredits: '0',
      markupCredits: '5',
    } as PlatformCredentialPublicInfo);

    expect(note.key).toBe('source.markupNoteWithRate');
  });
});

function renderSection(props: Partial<React.ComponentProps<typeof CredentialSection>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={messages}>
        <CredentialSection
          toolCredentials={[{ credentialName: 'seedance', isRequired: true, displayName: 'Seedance' }]}
          integration="seedance"
          apiToolId="11111111-2222-3333-4444-555555555555"
          selectedCredentialId={null}
          onCredentialSelect={vi.fn()}
          credentialSource="platform"
          platformCredentialId={7}
          onCredentialSourceChange={vi.fn()}
          {...props}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('CredentialSection generation price', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMocks.getAllCredentials.mockResolvedValue([]);
    apiMocks.getCredentialTemplates.mockResolvedValue({ credentials: [] });
    apiMocks.getCredentialTemplateByName.mockResolvedValue(null);
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      baseCredits: '0',
      quantity: '10',
      markupCredits: '600',
    });
  });

  it('quotes the SELECTED MODEL for the size currently entered, not the endpoint as a whole', async () => {
    renderSection({ modelId: 'seedance-2.0-fast', quantity: 10, quantityUnit: 'second' });

    // Field by field, not as one object literal: toHaveBeenCalledWith compares
    // with toEqual, which cannot tell an absent property from an undefined one,
    // so a whole-object assertion is satisfied by a field never being sent.
    await waitFor(() => expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());
    const [integration, toolId, quote] =
      apiMocks.getPlatformCredentialPublicInfo.mock.calls.at(-1)!;
    expect(integration).toBe('seedance');
    expect(toolId).toBe('11111111-2222-3333-4444-555555555555');
    expect(quote).toHaveProperty('modelId', 'seedance-2.0-fast');
    expect(quote).toHaveProperty('quantity', 10);
    expect(quote).toHaveProperty('generation', false);
    // The unit is asserted HERE too, not only on the chat modal. Both surfaces
    // send it, and only one of them was pinned: deleting it from this one left
    // 27 tests green while the quote lost the ability to notice a rate that
    // cannot price the call at all.
    expect(quote).toHaveProperty('quantityUnit', 'second');
  });

  it('renders the rate, the size and the total, so the cost of this run is visible before running it', async () => {
    renderSection({ modelId: 'seedance-2.0-fast', quantity: 10, quantityUnit: 'second' });

    expect(
      await screen.findByText('60 credits per second, 10 second = 600 credits for this run.'),
    ).toBeTruthy();
  });

  it('re-quotes when the request size changes: a longer clip is a different price', async () => {
    const { rerender } = renderSection({ modelId: 'seedance-2.0-fast', quantity: 10 });
    await waitFor(() => expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());

    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      quantity: '4',
      markupCredits: '240',
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={queryClient}>
        <NextIntlClientProvider locale="en" messages={messages}>
          <CredentialSection
            toolCredentials={[{ credentialName: 'seedance', isRequired: true, displayName: 'Seedance' }]}
            integration="seedance"
            apiToolId="11111111-2222-3333-4444-555555555555"
            modelId="seedance-2.0-fast"
            quantity={4}
            selectedCredentialId={null}
            onCredentialSelect={vi.fn()}
            credentialSource="platform"
            platformCredentialId={7}
            onCredentialSourceChange={vi.fn()}
          />
        </NextIntlClientProvider>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalledWith(
        'seedance',
        '11111111-2222-3333-4444-555555555555',
        // The factor travels on every quote now, and a call at the published rate sends 1: the
        // surfaces that carry one have to share a cache entry with the ones that do not.
        { modelId: 'seedance-2.0-fast', quantity: 4, generation: false, priceMultiplier: 1 },
      );
    });
  });

  it('renders the not-sold sentence, and no number, for a version-default-only quote', async () => {
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: false,
      versionDefaultOnly: true,
      defaultMarkupCredits: '2',
    });

    renderSection({ modelId: 'seedance-2.0-fast', quantity: 10, quantityUnit: 'second' });

    expect(await screen.findByText('This generation is not sold on the platform key.')).toBeTruthy();
    // The credential-wide default must not have leaked in through the flat
    // fallback: that is the exact number the server will refuse to charge.
    expect(screen.queryByText(/2 credits/)).toBeNull();
  });

  /**
   * The step that cannot name a model.
   *
   * <p>An `mcp:` node bound straight to a generation endpoint has nowhere to put
   * a model id, so the quote used to be told nothing that marked it as a
   * generation and answered with the credential-wide default: a price, and a
   * platform toggle, on a step execution refuses. The endpoint's own nature has
   * to travel instead, since the pricing service owns no catalog schema to look
   * it up in.
   */
  it('tells the quote the endpoint is a generation even when no model can be named', async () => {
    renderSection({ isGeneration: true, modelId: null, quantity: null });

    await waitFor(() => {
      expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalledWith(
        'seedance',
        '11111111-2222-3333-4444-555555555555',
        { modelId: null, quantity: null, generation: true, priceMultiplier: 1 },
      );
    });
  });

  it('hides the platform toggle on a generation endpoint the platform will not sell', async () => {
    // The answer the server gives once it knows: no price it will honour, so
    // the step must not be offered the platform key at all.
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: false,
      versionDefaultOnly: true,
      defaultMarkupCredits: '2',
    });

    renderSection({ isGeneration: true, credentialSource: 'user' });

    await waitFor(() => expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());
    expect(screen.queryByText('Platform')).toBeNull();
    expect(screen.queryByText(/2 credits/)).toBeNull();
  });

  it('shows no platform price when the node runs on the author own key: the platform bills nothing then', async () => {
    renderSection({ modelId: 'seedance-2.0-fast', quantity: 10, credentialSource: 'user' });

    await waitFor(() => expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());
    expect(screen.queryByText(/credits for this run/)).toBeNull();
  });

  it('states the rate by DEFAULT, because the inspector is the only place that states it at all', async () => {
    // A second surface (the app's generation dialog) prints each model's amount
    // on the model options and asks this section to stay quiet, which is what
    // `showPlatformPricingNotes` is for. Nothing here passes it, and nothing
    // here should have to: flipping the default would take the price off the
    // one screen where a workflow author decides whether they can afford the
    // node, and every existing caller would go silent at once.
    renderSection({ modelId: 'seedance-2.0-fast', quantity: 10, quantityUnit: 'second' });

    expect(
      await screen.findByText('60 credits per second, 10 second = 600 credits for this run.'),
    ).toBeTruthy();
  });

  it('says nothing about the rate when the caller states the price elsewhere', async () => {
    renderSection({
      modelId: 'seedance-2.0-fast', quantity: 10, quantityUnit: 'second',
      showPlatformPricingNotes: false,
    });

    await waitFor(() => expect(apiMocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());
    expect(screen.queryByText(/credits for this run/)).toBeNull();
    // The CHOICE survives: only the explanation is suppressed, so the reader
    // can still decide who pays.
    expect(screen.getByText('Platform')).toBeTruthy();
  });
});

/**
 * A price this pane CANNOT know, and must not state as a fact.
 *
 * <p>The inspector's fields accept expressions. A priced `resolution` bound to
 * `{{trigger:webhook.output.res}}` has no value until the run reaches the step, so the local
 * factor falls to the reference tier, the quote is asked without one, and the server answers the
 * published rate. This pane then printed "60 credits per second, 10 seconds = 600 credits" as a
 * plain statement for a step the server bills 2400 - while the model row ten pixels above it
 * already said the price depended on a runtime value. One screen, two claims, the confident one
 * next to the choice of who pays.
 */
describe('CredentialSection - a price that depends on a runtime value', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMocks.getAllCredentials.mockResolvedValue([]);
    apiMocks.getCredentialTemplates.mockResolvedValue({ credentials: [] });
    apiMocks.getCredentialTemplateByName.mockResolvedValue(null);
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      baseCredits: '0',
      quantity: '10',
      markupCredits: '600',
    } as PlatformCredentialPublicInfo);
  });

  it('says the price depends on a value the run resolves, beside the total', async () => {
    renderSection({
      modelId: 'seedance-2.0-fast',
      quantity: 10,
      quantityUnit: 'second',
      priceFactorReason: 'the final price depends on a value this step resolves when it runs',
      priceFactorIsUncertain: true,
    });

    expect(await screen.findByText(/depends on a value this step resolves/)).toBeTruthy();
  });

  it('does not ALSO claim a surcharge, which it cannot know either', async () => {
    // The hedge replaces the factor note rather than joining it: "multiply that by 1.2" beside
    // "the price depends on a runtime value" is two incompatible claims about one number.
    renderSection({
      modelId: 'seedance-2.0-fast',
      quantity: 10,
      quantityUnit: 'second',
      priceFactorReason: 'the final price depends on a value this step resolves when it runs',
      priceFactorIsUncertain: true,
    });

    await screen.findByText(/depends on a value this step resolves/);
    expect(screen.queryByText(/multiply that by/)).toBeNull();
  });

  it('states a KNOWN factor plainly when nothing is templated', async () => {
    // The other half: the hedge must not swallow a factor the surface can actually stand behind.
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      platformCredentialId: 7,
      available: true,
      hasPricing: true,
      priceUnit: 'second',
      unitCredits: '60',
      baseCredits: '0',
      quantity: '10',
      markupCredits: '1200',
      priceMultiplier: '2',
    } as PlatformCredentialPublicInfo);

    renderSection({
      modelId: 'seedance-2.0-fast',
      quantity: 10,
      quantityUnit: 'second',
      priceMultiplier: 2,
      priceFactorReason: 'includes Resolution x2',
      priceFactorIsUncertain: false,
    });

    expect(await screen.findByText(/multiply that by 2 \(includes Resolution x2\)/))
      .toBeTruthy();
  });
});
