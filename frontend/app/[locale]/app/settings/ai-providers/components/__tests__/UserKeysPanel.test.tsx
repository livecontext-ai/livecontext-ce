// @vitest-environment jsdom
/**
 * A cloud user's OWN provider keys: which key serves their next execution, the switch between
 * "my key" and the LiveContext key, the plan gate, and the key check before saving.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { credentialService, gate } = vi.hoisted(() => ({
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
  gate: {
    isLoading: false,
    lock: { locked: false, requiredPlan: null as string | null },
    lockFor: vi.fn(),
  },
}));

vi.mock('@/lib/api/orchestrator/credential.service', () => ({ credentialService }));
vi.mock('@/hooks/usePlanFeatureGate', () => ({
  usePlanFeatureGate: () => ({ isLoading: gate.isLoading, lockFor: gate.lockFor }),
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
// The fee ladder beside the panel title. Stubbed like every other collaborator in this suite:
// it reads the cost basis through react-query and its own next-intl namespaces, neither of
// which this file provides (the panel takes its translator as a prop). The (i) has its own
// suite, OwnKeyFeeInfo.test.tsx, which renders it against the real messages.
vi.mock('../OwnKeyFeeInfo', () => ({ default: () => <span data-testid="own-key-fee-info" /> }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('next/link', () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

import UserKeysPanel from '../UserKeysPanel';
import { track } from '@/lib/analytics/analytics';

const t = (key: string, values?: Record<string, string>) => (values ? `${key}:${JSON.stringify(values)}` : key);

const anthropic = {
  providerName: 'anthropic',
  integrationName: 'llm_anthropic',
  displayName: 'Anthropic (Claude)',
  docsUrl: 'https://console.anthropic.com/settings/keys',
  placeholder: 'sk-ant-...',
};

const savedKey = (mode: 'no_proxy' | 'proxy', id = 7) => ({
  id,
  integration: 'llm_anthropic',
  is_default: true,
  credential_data: { mode },
});

const callOrder = () => {
  const calls: Array<[string, number]> = [];
  for (const [name, fn] of Object.entries(credentialService)) {
    for (const inv of (fn as ReturnType<typeof vi.fn>).mock.invocationCallOrder) calls.push([name, inv]);
  }
  return calls.sort((a, b) => a[1] - b[1]).map(([name]) => name);
};

beforeEach(() => {
  vi.clearAllMocks();
  gate.isLoading = false;
  gate.lock = { locked: false, requiredPlan: null };
  gate.lockFor.mockImplementation(() => gate.lock);
  credentialService.getProvidersOfferingModels.mockResolvedValue(['anthropic']);
  credentialService.invalidateMyLlmCacheIfLlmKey.mockResolvedValue(undefined);
  credentialService.setLlmKeyMode.mockResolvedValue({});
  credentialService.createCredential.mockResolvedValue({ id: 99, is_default: false });
  credentialService.setDefaultCredential.mockResolvedValue(undefined);
  credentialService.deleteCredential.mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('UserKeysPanel - which providers are named at all', () => {
  const mistral = {
    providerName: 'mistral',
    integrationName: 'llm_mistral',
    displayName: 'Mistral',
    docsUrl: 'https://console.mistral.ai/api-keys',
    placeholder: 'mi-...',
  };

  it('a provider that exposes no model is never named: the user must not learn it exists here', async () => {
    // An admin has switched every Mistral model off, so no picker will ever offer one. The
    // panel used to list a hardcoded set of providers, so it would have invited a key that
    // saves fine, validates fine, and then serves nothing.
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.getProvidersOfferingModels.mockResolvedValue(['anthropic']);

    render(<UserKeysPanel definitions={[anthropic, mistral]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('Anthropic (Claude)')).toBeInTheDocument();
    expect(screen.queryByText('Mistral')).not.toBeInTheDocument();
  });

  it('matches the provider case-insensitively, the way the catalogue and the definition disagree', async () => {
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.getProvidersOfferingModels.mockResolvedValue(['Anthropic']);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('Anthropic (Claude)')).toBeInTheDocument();
  });

  it('a provider the user ALREADY has a key for stays visible, or the key would be stranded', async () => {
    // It stopped being offered after the key was saved. Hiding it now would leave a saved
    // credential the user can neither see, use, nor delete.
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy')]);
    credentialService.getProvidersOfferingModels.mockResolvedValue([]);

    render(<UserKeysPanel definitions={[anthropic, mistral]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('Anthropic (Claude)')).toBeInTheDocument();
    expect(screen.queryByText('Mistral')).not.toBeInTheDocument();
  });

  it('nothing to offer at all says so, instead of an empty grid', async () => {
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.getProvidersOfferingModels.mockResolvedValue([]);

    render(<UserKeysPanel definitions={[anthropic, mistral]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('yourKeys.noProviders')).toBeInTheDocument();
  });

  it('a failed lookup names nobody rather than falling back to the hardcoded list', async () => {
    // Failing open here would reintroduce the exact defect: a provider offered on a guess.
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.getProvidersOfferingModels.mockRejectedValue(new Error('gateway down'));

    render(<UserKeysPanel definitions={[anthropic, mistral]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('yourKeys.noProviders')).toBeInTheDocument();
    expect(screen.queryByText('Anthropic (Claude)')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('gateway down');
  });
});

describe('UserKeysPanel', () => {
  it('carries the price of a turn beside the offer, where someone deciding can see it', async () => {
    // The (i) is the whole point of the backend field, the component and its eight strings, and
    // this suite stubs it - so without this the line that mounts it could be deleted and every
    // test here would still pass. What the popover itself SAYS is pinned in OwnKeyFeeInfo.test.
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.getProvidersOfferingModels.mockResolvedValue(['anthropic']);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByTestId('own-key-fee-info')).toBeInTheDocument();
  });

  it('a saved key in no_proxy mode: the row says the next run is on YOUR key, and the switch is on', async () => {
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy'), { id: 1, integration: 'gmail', is_default: true, credential_data: {} }]);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByTestId('own-key-route-anthropic')).toHaveTextContent('yourKeys.route.mine');
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('yourKeys.billing.mine:{"provider":"Anthropic (Claude)"}')).toBeInTheDocument();
    // The one place the frontend lock and the backend gate (OwnKeyFeatureGate.FEATURE_KEY,
    // V507 seed) must agree: the same feature key, or the lock never matches the gate.
    expect(gate.lockFor).toHaveBeenCalledWith(['feature:own_llm_key']);
  });

  it('flipping the switch sets proxy mode on the credential and drops the cached key, without re-pasting anything; and back', async () => {
    credentialService.getAllCredentials
      .mockResolvedValueOnce([savedKey('no_proxy')])
      .mockResolvedValueOnce([savedKey('proxy')])
      .mockResolvedValue([savedKey('no_proxy')]);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    fireEvent.click(await screen.findByRole('switch'));

    await waitFor(() => expect(credentialService.setLlmKeyMode).toHaveBeenCalledWith(7, 'proxy'));
    expect(credentialService.invalidateMyLlmCacheIfLlmKey).toHaveBeenCalledWith('llm_anthropic');
    expect(credentialService.deleteCredential).not.toHaveBeenCalled();
    expect(await screen.findByText('yourKeys.route.platformSaved')).toBeInTheDocument();
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(screen.getByRole('switch'));
    await waitFor(() => expect(credentialService.setLlmKeyMode).toHaveBeenCalledWith(7, 'no_proxy'));
    expect(await screen.findByText('yourKeys.route.mine')).toBeInTheDocument();
  });

  it('a refused switch says so and leaves the row as it was', async () => {
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy')]);
    credentialService.setLlmKeyMode.mockRejectedValue(new Error('HTTP 404'));

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    fireEvent.click(await screen.findByRole('switch'));

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 404');
    expect(screen.getByTestId('own-key-route-anthropic')).toHaveTextContent('yourKeys.route.mine');
  });

  it('below the required plan the rows are read-only and the upgrade prompt names the plan', async () => {
    gate.lock = { locked: true, requiredPlan: 'PRO' };
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy')]);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('yourKeys.requiresPlan:{"plan":"PRO"}')).toBeInTheDocument();
    expect(screen.getByText('yourKeys.requiresPlanDescription:{"plan":"PRO"}')).toBeInTheDocument();
    // The upgrade card closes the panel, after the provider rows (the Settings > Organization
    // pattern), and the old amber status bar above the rows is gone.
    const card = screen.getByText('yourKeys.requiresPlan:{"plan":"PRO"}');
    const row = screen.getByTestId('own-key-route-anthropic');
    expect(row.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByRole('link', { name: 'yourKeys.upgrade:{"plan":"PRO"}' })).toHaveAttribute('href', '/pricing');
    fireEvent.click(screen.getByRole('link', { name: 'yourKeys.upgrade:{"plan":"PRO"}' }));
    expect(track).toHaveBeenCalledWith('byok_upgrade_clicked', { required_plan: 'pro' });
    // Even a saved key does not run: the platform key serves until the plan allows it.
    expect(screen.getByTestId('own-key-route-anthropic')).toHaveTextContent('yourKeys.route.platformSaved');
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.getByPlaceholderText('yourKeys.replaceKey')).toBeDisabled();
  });

  it('on a plan that allows own keys there is no upgrade card', async () => {
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy')]);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByText('yourKeys.route.mine')).toBeInTheDocument();
    expect(screen.queryByText(/yourKeys\.requiresPlan/)).toBeNull();
    expect(screen.queryByRole('link', { name: /yourKeys\.upgrade/ })).toBeNull();
  });

  it('a key the provider rejects is never saved; an accepted one is saved in no_proxy mode and made the default', async () => {
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.validateLlmKey.mockResolvedValueOnce({ valid: false, verified: true, error: 'rejected by anthropic (HTTP 401)' });

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    const input = await screen.findByPlaceholderText('sk-ant-...');
    fireEvent.change(input, { target: { value: 'sk-ant-bad' } });
    fireEvent.click(screen.getByRole('button', { name: 'yourKeys.addKey' }));

    await screen.findByText('yourKeys.errors.invalidKey:{"provider":"Anthropic (Claude)","reason":"rejected by anthropic (HTTP 401)"}');
    expect(credentialService.createCredential).not.toHaveBeenCalled();

    credentialService.validateLlmKey.mockResolvedValueOnce({ valid: true, verified: true });
    fireEvent.change(input, { target: { value: 'sk-ant-good' } });
    fireEvent.click(screen.getByRole('button', { name: 'yourKeys.addKey' }));

    await waitFor(() => expect(credentialService.createCredential).toHaveBeenCalledTimes(1));
    expect(credentialService.createCredential.mock.calls[0][0]).toMatchObject({
      integration: 'llm_anthropic',
      credential_data: { api_key: 'sk-ant-good', mode: 'no_proxy' },
    });
    await waitFor(() => expect(credentialService.setDefaultCredential).toHaveBeenCalledWith(99));
    expect(credentialService.deleteCredential).not.toHaveBeenCalled();
  });

  it('a provider that could not be asked (validate throws, or answers unverified) never blocks saving', async () => {
    credentialService.getAllCredentials.mockResolvedValue([]);
    credentialService.validateLlmKey.mockRejectedValueOnce(new Error('HTTP 503'));

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    const input = await screen.findByPlaceholderText('sk-ant-...');
    fireEvent.change(input, { target: { value: 'sk-ant-unchecked' } });
    fireEvent.click(screen.getByRole('button', { name: 'yourKeys.addKey' }));
    await waitFor(() => expect(credentialService.createCredential).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).toBeNull();

    credentialService.validateLlmKey.mockResolvedValueOnce({ valid: true, verified: false, error: 'could not reach anthropic' });
    fireEvent.change(input, { target: { value: 'sk-ant-unchecked-2' } });
    // The button reads 'saved' for two seconds after the first save.
    fireEvent.click(screen.getByRole('button', { name: /yourKeys.addKey|^saved$/ }));
    await waitFor(() => expect(credentialService.createCredential).toHaveBeenCalledTimes(2));
  });

  it('replacing a key creates the new row FIRST, keeps the route the old one had, then removes the old one', async () => {
    credentialService.getAllCredentials.mockResolvedValue([savedKey('proxy', 7)]);
    credentialService.validateLlmKey.mockResolvedValue({ valid: true, verified: true });

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    const input = await screen.findByPlaceholderText('yourKeys.replaceKey');
    fireEvent.change(input, { target: { value: 'sk-ant-new' } });
    fireEvent.click(screen.getByRole('button', { name: 'yourKeys.replace' }));

    await waitFor(() => expect(credentialService.deleteCredential).toHaveBeenCalledWith(7));
    // The user never stands without a key: create, then delete, then promote the new row.
    expect(callOrder().filter((n) => ['createCredential', 'deleteCredential', 'setDefaultCredential'].includes(n)))
      .toEqual(['createCredential', 'deleteCredential', 'setDefaultCredential']);
    // A key replaced while on the platform route stays on the platform route.
    expect(credentialService.createCredential.mock.calls[0][0]).toMatchObject({ credential_data: { api_key: 'sk-ant-new', mode: 'proxy' } });
  });

  it('a create that fails during a replace leaves the old key in place and says so', async () => {
    credentialService.getAllCredentials.mockResolvedValue([savedKey('no_proxy', 7)]);
    credentialService.validateLlmKey.mockResolvedValue({ valid: true, verified: true });
    credentialService.createCredential.mockRejectedValueOnce(new Error('HTTP 500'));

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    fireEvent.change(await screen.findByPlaceholderText('yourKeys.replaceKey'), { target: { value: 'sk-ant-new' } });
    fireEvent.click(screen.getByRole('button', { name: 'yourKeys.replace' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500');
    expect(credentialService.deleteCredential).not.toHaveBeenCalled();
  });

  it('removing the key deletes it, drops the cached key, and the row goes back to the platform route', async () => {
    credentialService.getAllCredentials
      .mockResolvedValueOnce([savedKey('no_proxy', 7)])
      .mockResolvedValue([]);

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);
    fireEvent.click(await screen.findByRole('button', { name: 'removeKey' }));

    await waitFor(() => expect(credentialService.deleteCredential).toHaveBeenCalledWith(7));
    expect(credentialService.invalidateMyLlmCacheIfLlmKey).toHaveBeenCalledWith('llm_anthropic');
    expect(await screen.findByText('yourKeys.route.platform')).toBeInTheDocument();
  });

  it('says when the keys could not be loaded', async () => {
    credentialService.getAllCredentials.mockRejectedValue(new Error('HTTP 502'));

    render(<UserKeysPanel definitions={[anthropic]} t={t} pricingHref="/pricing" />);

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502');
  });
});
