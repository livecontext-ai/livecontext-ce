// @vitest-environment jsdom
/**
 * Connecting your OWN provider key from the studio.
 *
 * <p>The form was already reachable here: the payer pane embeds the app's shared credential
 * control, which offers "connect a key" for the chosen provider. It was also unusable, for a reason
 * that leaves no trace anywhere. The form is a DIALOG, and a dialog renders in a portal on the
 * document rather than inside this menu, so the first click inside it is a click OUTSIDE the
 * popover: the menu dismisses itself, the control that owns the form unmounts, and the form
 * disappears under the reader's cursor. Pressing the button again does the same thing.
 *
 * <p>So the menu is told when a form is up and refuses to dismiss until it is gone.
 *
 * <p><b>What these tests can and cannot reach.</b> The guard covers all three of Radix's dismissal
 * routes (pointer-down outside, focus outside, Escape), because a real browser uses all three. Only
 * ESCAPE is reachable here: jsdom has no PointerEvent, so the outside-pointer layer never fires and
 * a test written against it would pass on the broken code too - which is worse than no test, since
 * it reads as proof. The two routes jsdom cannot produce are therefore not asserted rather than
 * asserted vacuously, and the Escape path below was checked to FAIL with the guard removed.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * The shared credential control, stubbed down to the ONE thing this suite is about: it is handed a
 * way to say "a form is open", and it uses it. The real control's own behaviour has its own tests;
 * what cannot be tested there is what the container around it does with the signal.
 */
const wizard = vi.hoisted(() => ({
  notify: null as ((open: boolean) => void) | null,
  // The whole question the pane asks, captured: which integration, which endpoint, which model,
  // and what it says about the size and the factor.
  props: null as Record<string, unknown> | null,
}));
vi.mock('@/app/workflows/builder/components/inspector/CredentialSection', () => ({
  CredentialSection: (props: { onWizardOpenChange?: (open: boolean) => void }) => {
    wizard.notify = props.onWizardOpenChange ?? null;
    wizard.props = props as Record<string, unknown>;
    return <div data-testid="credential-section" />;
  },
}));
vi.mock('@/lib/generation/formats', () => ({
  FORMAT_ORDER: ['image', 'video', 'audio', 'voice', 'music'],
  FormatGlyph: () => <span data-testid="format-glyph" />,
  ProviderIcon: () => <span data-testid="provider-icon" />,
}));

import { StudioModelPicker } from '../StudioModelPicker';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

const SEEDANCE = {
  model: 'seedance-2', kind: 'video', label: 'Seedance 2.0', provider: 'Seedance',
  iconSlug: null, apiToolId: 't1', integrationName: 'seedance',
  accepts: ['prompt'], required: [], limits: {},
  billedOn: null, measuredUnit: null, defaultQuantity: null,
  price: { unit: 'call', baseCredits: '0', unitCredits: '0' }, async: false,
} as unknown as GenerationModel;

/** A second provider, so the pane can be walked onto a model that is NOT the selected one. */
const KLING = {
  ...SEEDANCE,
  model: 'kling-2', label: 'Kling 2.0', provider: 'Kling',
  apiToolId: 't2', integrationName: 'kling', measuredUnit: 'second',
} as unknown as GenerationModel;

function renderPicker(models: GenerationModel[] = [SEEDANCE], extra: Record<string, unknown> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <StudioModelPicker
        models={models}
        selected={SEEDANCE}
        onSelect={vi.fn()}
        credentialSource="user"
        onCredentialSourceChange={vi.fn()}
        onCredentialIdChange={vi.fn()}
        {...extra}
      />
    </NextIntlClientProvider>,
  );
  // A selected model opens the picker straight on its own pane, which is where the payer lives.
  fireEvent.click(screen.getByTitle('Change model'));
}

afterEach(() => { wizard.notify = null; wizard.props = null; cleanup(); });

describe('StudioModelPicker - connecting your own key', () => {
  it('offers the credential control on the pane of the chosen provider', () => {
    renderPicker();
    expect(screen.getByTestId('credential-section')).toBeInTheDocument();
  });

  it('gives that control a way to say a form is open', () => {
    // Without the wiring the menu cannot know, and every guard below is dead code.
    renderPicker();
    expect(wizard.notify).toBeInstanceOf(Function);
  });

  it('stays open while the key form is up, so the form is not dismissed with it', () => {
    // Escape is the route jsdom can actually produce, and it is the one the reader hits first:
    // the form's own cancel key would otherwise take the menu, the section and the form with it.
    renderPicker();

    wizard.notify!(true);
    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(screen.getByTestId('credential-section')).toBeInTheDocument();
  });

  it('closes normally again once the form is gone', () => {
    // The guard must be temporary. A menu that could no longer be dismissed would be a worse bug
    // than the one it fixes, and it would look like the app had frozen.
    renderPicker();

    wizard.notify!(true);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.getByTestId('credential-section')).toBeInTheDocument();

    wizard.notify!(false);
    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(screen.queryByTestId('credential-section')).toBeNull();
  });

  it('closes on Escape when no form was ever opened', () => {
    renderPicker();

    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(screen.queryByTestId('credential-section')).toBeNull();
  });
});

describe('StudioModelPicker - the payer pane belongs to the selected model', () => {
  it('quotes the selected model with the quantity and factor typed in the composer', () => {
    renderPicker([SEEDANCE, KLING], { quantity: 10, priceMultiplier: 2 });
    expect(wizard.props).toMatchObject({
      integration: 'seedance', apiToolId: 't1', modelId: 'seedance-2',
      quantity: 10, priceMultiplier: 2,
    });
  });

  it('does not mount a mutable payer control while browsing another provider', () => {
    renderPicker([SEEDANCE, KLING]);
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    fireEvent.click(screen.getByRole('button', { name: /Kling/ }));
    expect(screen.queryByTestId('credential-section')).toBeNull();
    expect(screen.getByRole('button', { name: 'Kling 2.0' })).toBeInTheDocument();
  });

  it('does not quote an image model in the same providers video pane', () => {
    const image = { ...SEEDANCE, model: 'seedance-image', kind: 'image', label: 'Seedance Image' };
    renderPicker([SEEDANCE, image], { selected: image });
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    fireEvent.click(screen.getByText('Video'));
    fireEvent.click(screen.getByText('Seedance'));
    expect(screen.queryByTestId('credential-section')).toBeNull();
    expect(screen.getByRole('button', { name: 'Seedance 2.0' })).toBeInTheDocument();
  });
});
