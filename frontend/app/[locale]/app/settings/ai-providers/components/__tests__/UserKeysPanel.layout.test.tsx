// @vitest-environment jsdom
/**
 * Layout regression for the own-keys panel (2026-09-22): every card was crushed.
 *
 * <p>The arithmetic behind it. This panel renders inside the settings column, and at the
 * {@code md} breakpoint that column is about 500px wide because {@code SettingsNav} takes 192 of
 * them. With {@code md:grid-cols-2} each card therefore got roughly 240px to hold a provider
 * logo, a provider name, a route chip, a toggle, a key field and two buttons. Inside the card the
 * header was a single flex row, so the route chip - which spells out a whole sentence
 * ("Runs on the LiveContext key (yours is saved)") - fought the provider name for the same line;
 * the 40px logo tile, having no {@code flex-shrink-0}, was the flexible item and collapsed to a
 * sliver, and the name wrapped mid-word.
 *
 * <p>None of that throws, renders an error, or fails any behavioural test: the panel works
 * perfectly and looks broken. The classes ARE the behaviour here, so they are what gets pinned -
 * the same reason the Models table next door pins its grid template structurally. Each assertion
 * below is one token away from the bug it names.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

const { credentialService } = vi.hoisted(() => ({
  credentialService: {
    getAllCredentials: vi.fn(),
    getProvidersOfferingModels: vi.fn(),
    validateLlmKey: vi.fn(),
    createCredential: vi.fn(),
    deleteCredential: vi.fn(),
    setDefaultCredential: vi.fn(),
    setLlmKeyMode: vi.fn(),
    invalidateMyLlmCacheIfLlmKey: vi.fn(),
  },
}));

vi.mock('@/lib/api/orchestrator/credential.service', () => ({ credentialService }));
vi.mock('@/hooks/usePlanFeatureGate', () => ({
  usePlanFeatureGate: () => ({ isLoading: false, lockFor: () => ({ locked: false, requiredPlan: null }) }),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('../OwnKeyFeeInfo', () => ({ default: () => <span data-testid="own-key-fee-info" /> }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

import UserKeysPanel from '../UserKeysPanel';

const t = (key: string, values?: Record<string, string>) => (values ? `${key}:${JSON.stringify(values)}` : key);

const anthropic = {
  providerName: 'anthropic',
  integrationName: 'llm_anthropic',
  displayName: 'Anthropic (Claude)',
  docsUrl: 'https://console.anthropic.com/settings/keys',
  placeholder: 'sk-ant-...',
};

/** A saved key in "my key serves" mode, so the route chip renders its longest label. */
const savedKey = {
  id: 7,
  integration: 'llm_anthropic',
  is_default: true,
  credential_data: { mode: 'proxy' },
};

async function renderPanel() {
  const view = render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
  await waitFor(() => screen.getByText('Anthropic (Claude)'));
  return view;
}

beforeEach(() => {
  vi.clearAllMocks();
  credentialService.getAllCredentials.mockResolvedValue([savedKey]);
  credentialService.getProvidersOfferingModels.mockResolvedValue(['anthropic']);
});

afterEach(() => {
  cleanup();
});

describe('own-keys panel layout', () => {
  it('waits for a wide viewport before splitting into two columns', async () => {
    // `md` is the breakpoint that crushed them: the settings column is ~500px there, so two
    // columns means ~240px per card. `xl` is the first width where a card gets its ~430px.
    const { container } = await renderPanel();

    const grid = container.querySelector('[class*="grid-cols-1"]');
    expect(grid).not.toBeNull();
    expect(grid!.className).toContain('xl:grid-cols-2');
    expect(grid!.className).not.toContain('md:grid-cols-2');
  });

  it('stacks the card header until there is room for the chip beside the name', async () => {
    await renderPanel();

    const header = screen.getByText('Anthropic (Claude)').closest('[class*="mb-3"]');
    expect(header).not.toBeNull();
    // Column first, row only from `sm`: below that the route chip takes its own line instead of
    // competing with the provider name for one.
    expect(header!.className).toContain('flex-col');
    expect(header!.className).toContain('sm:flex-row');
  });

  it('keeps the logo tile at its size instead of letting the text squeeze it', async () => {
    const { container } = await renderPanel();

    const tile = container.querySelector('[class*="w-10"][class*="h-10"]');
    expect(tile).not.toBeNull();
    // A fixed 40px box in a flex row beside text is the flexible item unless told otherwise,
    // and what it collapsed into was a sliver, not a smaller logo.
    expect(tile!.className).toContain('flex-shrink-0');
  });

  it('lets a long provider name truncate rather than widen or break the card', async () => {
    await renderPanel();

    const name = screen.getByText('Anthropic (Claude)');
    expect(name.className).toContain('truncate');
    // Truncation hides text, so the full name has to stay reachable on hover.
    expect(name).toHaveAttribute('title', 'Anthropic (Claude)');
    // Truncation also does nothing inside a flex child that refuses to shrink below its content.
    expect(name.parentElement!.className).toContain('min-w-0');
  });

  it('pins the route chip to the top of its line rather than stretching it', async () => {
    await renderPanel();

    const chip = screen.getByTestId('own-key-route-anthropic');
    expect(chip.className).toContain('self-start');
  });
});
