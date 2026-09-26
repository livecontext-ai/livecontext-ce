// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  listSsoDomains: vi.fn(),
  addSsoDomain: vi.fn(),
  verifySsoDomain: vi.fn(),
  deleteSsoDomain: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string, values?: Record<string, string>) => {
    const translations: Record<string, string> = {
      noneVerified: 'No verified domain yet',
      verified: 'Verified',
      pending: 'Pending',
      verify: 'Check now',
      add: 'Add domain',
      placeholder: 'company.com',
      recordName: 'Name',
      recordValue: 'Value',
      copy: 'Copy',
      verifiedNotice: '{domain} is verified.',
      notFoundYet: 'The TXT record for {domain} was not found yet.',
      remove: 'Remove {domain}',
    };
    let text = translations[key] ?? key;
    for (const [k, v] of Object.entries(values ?? {})) text = text.replace(`{${k}}`, v);
    return text;
  },
}));

vi.mock('@/lib/api/organization-api', () => ({ organizationApi: api }));

import OrganizationSsoDomainsSection from '../OrganizationSsoDomainsSection';

const pending = {
  id: 'd1',
  domain: 'acme.com',
  verified: false,
  verifiedAt: null,
  lastCheckedAt: null,
  txtRecordName: '_livecontext-sso.acme.com',
  txtRecordValue: 'livecontext-sso-verification=tok',
};

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <OrganizationSsoDomainsSection orgId="org-1" />
    </QueryClientProvider>,
  );
}

describe('OrganizationSsoDomainsSection', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
  });

  afterEach(() => cleanup());

  it('shows a pending domain with the exact TXT record to publish, and warns that SSO is closed until one is verified', async () => {
    api.listSsoDomains.mockResolvedValue([pending]);
    renderSection();

    expect(await screen.findByText('acme.com')).toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
    expect(screen.getByText('_livecontext-sso.acme.com')).toBeInTheDocument();
    expect(screen.getByText('livecontext-sso-verification=tok')).toBeInTheDocument();
    expect(screen.getByText('No verified domain yet')).toBeInTheDocument();
  });

  it('hides the record and the warning once the domain is verified', async () => {
    api.listSsoDomains.mockResolvedValue([{ ...pending, verified: true, verifiedAt: '2026-09-23T10:00:00Z' }]);
    renderSection();

    expect(await screen.findByText('Verified')).toBeInTheDocument();
    expect(screen.queryByText('_livecontext-sso.acme.com')).not.toBeInTheDocument();
    expect(screen.queryByText('No verified domain yet')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Check now/ })).not.toBeInTheDocument();
  });

  it('adds the typed domain for this workspace', async () => {
    api.listSsoDomains.mockResolvedValue([]);
    api.addSsoDomain.mockResolvedValue(pending);
    renderSection();

    fireEvent.change(await screen.findByLabelText('company.com'), { target: { value: ' acme.com ' } });
    fireEvent.click(screen.getByRole('button', { name: /Add domain/ }));

    await waitFor(() => expect(api.addSsoDomain).toHaveBeenCalledWith('org-1', 'acme.com'));
  });

  it('says so when the record is not found yet, instead of claiming success', async () => {
    api.listSsoDomains.mockResolvedValue([pending]);
    api.verifySsoDomain.mockResolvedValue({ ...pending, lastCheckedAt: '2026-09-23T10:00:00Z' });
    renderSection();

    fireEvent.click(await screen.findByRole('button', { name: /Check now/ }));

    expect(await screen.findByText('The TXT record for acme.com was not found yet.')).toBeInTheDocument();
    expect(api.verifySsoDomain).toHaveBeenCalledWith('org-1', 'd1');
  });

  it('confirms a successful verification', async () => {
    api.listSsoDomains.mockResolvedValue([pending]);
    api.verifySsoDomain.mockResolvedValue({ ...pending, verified: true, verifiedAt: '2026-09-23T10:00:00Z' });
    renderSection();

    fireEvent.click(await screen.findByRole('button', { name: /Check now/ }));

    expect(await screen.findByText('acme.com is verified.')).toBeInTheDocument();
  });

  it('shows the server message when a domain is already claimed elsewhere', async () => {
    api.listSsoDomains.mockResolvedValue([pending]);
    api.verifySsoDomain.mockRejectedValue(new Error('This domain is already verified by another workspace'));
    renderSection();

    fireEvent.click(await screen.findByRole('button', { name: /Check now/ }));

    expect(await screen.findByText('This domain is already verified by another workspace')).toBeInTheDocument();
  });

  it('removes a domain', async () => {
    api.listSsoDomains.mockResolvedValue([pending]);
    api.deleteSsoDomain.mockResolvedValue(undefined);
    renderSection();

    fireEvent.click(await screen.findByRole('button', { name: 'Remove acme.com' }));

    await waitFor(() => expect(api.deleteSsoDomain).toHaveBeenCalledWith('org-1', 'd1'));
  });
});
