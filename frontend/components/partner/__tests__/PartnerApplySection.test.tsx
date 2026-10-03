// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import { PARTNER_APPLY_DRAFT_KEY } from '@/lib/partners/applyDraft';
import { PARTNER_TERMS_VERSION } from '@/lib/partners/terms';

const auth = vi.hoisted(() => ({ isAuthenticated: false, isLoading: false, loginWithRedirect: vi.fn() }));
const me = vi.fn();
const apply = vi.fn();

const analytics = vi.hoisted(() => ({ track: vi.fn(), setLandingIntent: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => auth }));
vi.mock('@/lib/analytics/analytics', () => analytics);
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  PARTNER_DASHBOARD_QUERY_KEY: ['partner-program', 'me'],
  partnerProgramApi: { me: () => me(), apply: (body: unknown) => apply(body) },
}));

import { PartnerApplySection } from '../PartnerApplySection';

/** A draft saved by the form: the visitor ticked the current Partner Program Terms before signing in. */
const DRAFT = { company: 'Acme Automation', website: 'https://acme.io', audience: 'Agencies', message: 'Hi', termsVersion: PARTNER_TERMS_VERSION };

function renderSection(locale: 'en' | 'fr' = 'en') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale={locale} messages={locale === 'fr' ? frMessages : enMessages}>
        <PartnerApplySection />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function dashboard(state: string, extra: Record<string, unknown> = {}) {
  return { state, terms: {}, application: null, partner: null, ...extra };
}

describe('the application form on /partners', () => {
  beforeEach(() => {
    auth.isAuthenticated = false;
    auth.isLoading = false;
    auth.loginWithRedirect.mockReset();
    analytics.track.mockReset();
    analytics.setLandingIntent.mockReset();
    me.mockReset();
    apply.mockReset();
    window.sessionStorage.clear();
    window.history.replaceState({}, '', '/partners');
  });
  afterEach(cleanup);

  it('a visitor fills it without an account; sending keeps what they typed and starts the sign-in back to the form', async () => {
    renderSection();

    expect(screen.getByText(/we will ask you to sign in or create your free account/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Company or brand'), { target: { value: 'Acme Automation' } });
    fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Sign in and send' }));

    const saved = JSON.parse(window.sessionStorage.getItem(PARTNER_APPLY_DRAFT_KEY) ?? '{}');
    expect(saved.company).toBe('Acme Automation');
    // The terms the visitor ticked travel with the draft across the sign-in.
    expect(saved.termsVersion).toBe(PARTNER_TERMS_VERSION);
    // No fragment in an OAuth return address; the page scrolls to the form itself on return.
    expect(auth.loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/partners?apply=1' } });
    expect(apply).not.toHaveBeenCalled();
    expect(analytics.track).toHaveBeenCalledWith('landing_cta_clicked', expect.objectContaining({ cta: 'partners_apply_submit' }));
    expect(analytics.setLandingIntent).toHaveBeenCalledWith('landing_cta', 'partners_apply_submit');
  });

  it('back from the sign-in, the saved application is sent once, then the draft is gone', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('none'));
    apply.mockResolvedValue({ success: true, application: { id: 1 } });

    renderSection();

    await waitFor(() => expect(screen.getByTestId('partner-apply-sent')).toBeTruthy());
    expect(apply).toHaveBeenCalledTimes(1);
    expect(apply).toHaveBeenCalledWith({
      company_name: 'Acme Automation', website: 'https://acme.io', audience: 'Agencies', message: 'Hi',
      terms_version: PARTNER_TERMS_VERSION,
    });
    expect(window.sessionStorage.getItem(PARTNER_APPLY_DRAFT_KEY)).toBeNull();
    expect(screen.getByRole('link', { name: 'Open my partner page' }).getAttribute('href')).toBe('/app/settings/partner');
    // A sent application is its own event, not a click.
    expect(analytics.track).toHaveBeenCalledWith('partner_application_submitted', { source: 'partners_page' });
  });

  it('back from the sign-in, the page scrolls to the form (the return address carries no fragment)', async () => {
    const anchor = document.createElement('section');
    anchor.id = 'apply';
    anchor.scrollIntoView = vi.fn();
    document.body.appendChild(anchor);
    window.history.replaceState({}, '', '/partners?apply=1');

    renderSection();

    await waitFor(() => expect(anchor.scrollIntoView).toHaveBeenCalled());
    anchor.remove();
  });

  it('a saved draft without the return flag only prefills: nothing is sent behind the visitor\'s back', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('none'));

    renderSection();

    await waitFor(() => expect((screen.getByLabelText('Company or brand') as HTMLInputElement).value).toBe('Acme Automation'));
    expect(apply).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Submit my application' })).toBeTruthy();
  });

  it('an application already waiting shows its status instead of a form the backend would refuse', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('pending', { application: { id: 3, company_name: 'Acme Automation' } }));

    renderSection();

    await waitFor(() => expect(screen.getByTestId('partner-apply-pending')).toBeTruthy());
    expect(screen.queryByLabelText('Company or brand')).toBeNull();
    expect(apply).not.toHaveBeenCalled();
  });

  it('a partner is sent to their partner page', async () => {
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('active'));

    renderSection();

    await waitFor(() => expect(screen.getByTestId('partner-apply-partner')).toBeTruthy());
    expect(screen.getByText('You are already a partner')).toBeTruthy();
  });

  it('a rejected applicant coming back from the sign-in may apply again: the draft is sent', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('rejected'));
    apply.mockResolvedValue({ success: true, application: { id: 2 } });

    renderSection();

    await waitFor(() => expect(screen.getByTestId('partner-apply-sent')).toBeTruthy());
    expect(apply).toHaveBeenCalledTimes(1);
  });

  it('when the partner status cannot be read, the form stays (the backend still refuses a duplicate) and nothing is auto-sent', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockRejectedValue(new Error('down'));

    renderSection();

    await waitFor(() => expect(screen.getByLabelText('Company or brand')).toBeTruthy());
    expect(apply).not.toHaveBeenCalled();
  });

  it('while the sign-in state is loading, a placeholder, not a form that would send to the wrong place', () => {
    auth.isLoading = true;

    renderSection();

    expect(screen.queryByLabelText('Company or brand')).toBeNull();
    expect(screen.queryByTestId('partner-apply-section')).toBeNull();
  });

  it('V557: without ticking the Partner Program Terms nothing can be sent, and the box links to the terms', () => {
    renderSection();

    fireEvent.change(screen.getByLabelText('Company or brand'), { target: { value: 'Acme Automation' } });
    const send = screen.getByRole('button', { name: 'Sign in and send' }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fireEvent.click(send);
    expect(auth.loginWithRedirect).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem(PARTNER_APPLY_DRAFT_KEY)).toBeNull();

    const terms = screen.getByRole('link', { name: 'Partner Program Terms' });
    expect(terms.getAttribute('href')).toBe('/legal/partners');
    expect(terms.getAttribute('target')).toBe('_blank');
    expect(screen.getByText(new RegExp(`version ${PARTNER_TERMS_VERSION}`))).toBeTruthy();

    fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
    expect(send.disabled).toBe(false);
  });

  it('V557: a French reader is sent to the French text, the one that prevails', () => {
    renderSection('fr');

    const terms = screen.getByRole('link', { name: 'conditions du programme partenaires' });
    expect(terms.getAttribute('href')).toBe('/legal/partners/fr');
  });

  it('V557: a draft saved before the terms, or on an older version, is restored but never sent without a fresh tick', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify({ ...DRAFT, termsVersion: '2020-01-01' }));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('none'));
    apply.mockResolvedValue({ success: true, application: { id: 1 } });

    renderSection();

    await waitFor(() => expect((screen.getByLabelText('Company or brand') as HTMLInputElement).value).toBe('Acme Automation'));
    expect(apply).not.toHaveBeenCalled();
    expect((screen.getByTestId('partner-terms-checkbox') as HTMLInputElement).checked).toBe(false);

    fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Submit my application' }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith(expect.objectContaining({ terms_version: PARTNER_TERMS_VERSION })));
  });

  it('V557: terms updated while the visitor was reading are reported with a way out, not as a generic error', async () => {
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('none'));
    const { ApiError } = await import('@/lib/api/api-client');
    apply.mockRejectedValue(new ApiError('conflict', 409, 'terms_outdated'));

    renderSection();

    await waitFor(() => expect(screen.getByLabelText('Company or brand')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Company or brand'), { target: { value: 'Acme Automation' } });
    fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Submit my application' }));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(enMessages.partnerDashboard.errors.terms_outdated));
  });

  it('a refused send (already applied elsewhere) is reported on the form, not swallowed', async () => {
    window.sessionStorage.setItem(PARTNER_APPLY_DRAFT_KEY, JSON.stringify(DRAFT));
    window.history.replaceState({}, '', '/partners?apply=1');
    auth.isAuthenticated = true;
    me.mockResolvedValue(dashboard('none'));
    const { ApiError } = await import('@/lib/api/api-client');
    apply.mockRejectedValue(new ApiError('conflict', 409, 'already_pending'));

    renderSection();

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(apply).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('partner-apply-sent')).toBeNull();
  });

  // Moved from the settings page, which no longer holds a form: /partners is the only one.
  describe('signed in, never applied', () => {
    beforeEach(() => {
      auth.isAuthenticated = true;
      me.mockResolvedValue(dashboard('none'));
    });

    it('sends the trimmed company, the website and the version of the terms ticked, then confirms', async () => {
      apply.mockResolvedValue({ success: true, application: { id: 1 } });
      renderSection();

      fireEvent.change(await screen.findByLabelText('Company or brand'), { target: { value: '  Acme Automation ' } });
      fireEvent.change(screen.getByLabelText('Website'), { target: { value: 'https://acme.io' } });
      fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
      fireEvent.click(screen.getByRole('button', { name: 'Submit my application' }));

      await waitFor(() => expect(apply).toHaveBeenCalledWith({
        company_name: 'Acme Automation', website: 'https://acme.io', audience: undefined, message: undefined,
        terms_version: PARTNER_TERMS_VERSION,
      }));
      await waitFor(() => expect(screen.getByTestId('partner-apply-sent')).toBeTruthy());
    });

    it('a refused send shows the translated reason, not a raw token', async () => {
      const { ApiError } = await import('@/lib/api/api-client');
      apply.mockRejectedValue(new ApiError('conflict', 409, 'already_pending'));
      renderSection();

      fireEvent.change(await screen.findByLabelText('Company or brand'), { target: { value: 'Acme' } });
      fireEvent.click(screen.getByTestId('partner-terms-checkbox'));
      fireEvent.click(screen.getByRole('button', { name: 'Submit my application' }));

      await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('You already have an application under review.'));
    });

    it('the submit button stays disabled until a company is entered', async () => {
      renderSection();

      const submit = await screen.findByRole('button', { name: 'Submit my application' });
      expect((submit as HTMLButtonElement).disabled).toBe(true);
      fireEvent.change(screen.getByLabelText('Company or brand'), { target: { value: '   ' } });
      expect((submit as HTMLButtonElement).disabled).toBe(true);
    });
  });
});
