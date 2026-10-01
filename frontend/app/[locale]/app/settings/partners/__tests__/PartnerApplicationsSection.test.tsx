// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import type { PartnerApplicationRow } from '@/lib/api/services/partner-admin-api.service';

const applications = vi.fn();
const approveApplication = vi.fn();
const rejectApplication = vi.fn();

vi.mock('@/lib/api/services/partner-admin-api.service', () => ({
  partnerAdminApi: {
    applications: (status: string) => applications(status),
    approveApplication: (id: number, body: unknown) => approveApplication(id, body),
    rejectApplication: (id: number, note?: string) => rejectApplication(id, note),
  },
}));

import { PartnerApplicationsSection } from '../PartnerApplicationsSection';

const ROW: PartnerApplicationRow = {
  id: 5, status: 'pending', company_name: 'Acme Automation', website: 'https://acme.io',
  audience: 'Real estate agencies', message: 'We run 30 clients', decision_note: null,
  created_at: '2026-09-20T10:00:00Z', reviewed_at: null, user_id: 7, email: 'p@acme.io', reward_code_id: null,
};

const notify = vi.fn();
const onDecided = vi.fn();
const onError = vi.fn();

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerApplicationsSection onDecided={onDecided} onError={onError} notify={notify} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('Admin > Partners > Applications', () => {
  beforeEach(() => {
    [applications, approveApplication, rejectApplication, notify, onDecided, onError].forEach((m) => m.mockReset());
    applications.mockResolvedValue({ applications: [ROW] });
  });
  afterEach(cleanup);

  it('lists pending applications with what the applicant wrote', async () => {
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    expect(applications).toHaveBeenCalledWith('pending');
    expect(screen.getByText(/p@acme\.io/)).toBeTruthy();
    expect(screen.getByText(/Real estate agencies/)).toBeTruthy();
    expect(screen.getByText(/We run 30 clients/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /https:\/\/acme\.io/ }).getAttribute('rel')).toContain('nofollow');
  });

  it('never turns a non-http website into a link', async () => {
    applications.mockResolvedValue({ applications: [{ ...ROW, website: 'javascript:alert(1)' }] });
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('approve sends the overrides, notifies with the created code and refreshes the report', async () => {
    approveApplication.mockResolvedValue({ success: true, application: { ...ROW, status: 'approved' }, code: 'ACME', mailed: true });
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Code (optional)'), { target: { value: ' acme ' } });
    fireEvent.change(screen.getByLabelText('Commission % (optional)'), { target: { value: '40' } });
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }));

    await waitFor(() => expect(approveApplication).toHaveBeenCalledWith(5, { code: 'acme', commission_percent: 40 }));
    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      type: 'success',
      title: 'Partner approved',
      message: 'Code ACME created. The applicant was e-mailed.',
    })));
    expect(onDecided).toHaveBeenCalled();
  });

  it('approve with empty overrides sends none, so the program defaults apply', async () => {
    approveApplication.mockResolvedValue({ success: true, application: ROW, code: 'LC-X', mailed: false });
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }));

    await waitFor(() => expect(approveApplication).toHaveBeenCalledWith(5, { code: undefined, commission_percent: undefined }));
    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      message: 'Code LC-X created. No e-mail could be sent: tell the applicant yourself.',
    })));
  });

  it('regression: a decimal comma is read as a decimal, never dropped to the default rate', async () => {
    approveApplication.mockResolvedValue({ success: true, application: ROW, code: 'ACME', mailed: true });
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Commission % (optional)'), { target: { value: '12,5' } });
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }));

    await waitFor(() => expect(approveApplication).toHaveBeenCalledWith(5, { code: undefined, commission_percent: 12.5 }));
  });

  it('regression: a rate that is not a number blocks the approval instead of approving at 50%', async () => {
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Commission % (optional)'), { target: { value: 'abc' } });

    const approveButton = screen.getByRole('button', { name: /Approve/ }) as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toMatch(/enter a number between 0 and 100/);
    fireEvent.click(approveButton);
    expect(approveApplication).not.toHaveBeenCalled();
  });

  it('reject asks for an optional note, then sends it', async () => {
    rejectApplication.mockResolvedValue({ success: true, application: { ...ROW, status: 'rejected' }, mailed: true });
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Reject/ }));
    fireEvent.change(screen.getByLabelText('Note for the applicant (optional)'), { target: { value: 'Not yet' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send rejection' }));

    await waitFor(() => expect(rejectApplication).toHaveBeenCalledWith(5, 'Not yet'));
    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({ title: 'Application rejected' })));
  });

  it('a refused decision goes to the page error handler', async () => {
    const failure = new Error('conflict');
    approveApplication.mockRejectedValue(failure);
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }));

    await waitFor(() => expect(onError).toHaveBeenCalled());
    expect(onError.mock.calls[0][0]).toBe(failure);
    expect(notify).not.toHaveBeenCalled();
    // A refused decision usually means another admin decided first: the list is reloaded so
    // the row stops offering a decision that can only fail again.
    await waitFor(() => expect(applications).toHaveBeenCalledTimes(2));
    // The code report below reloads too: the admin who won may have created a code.
    expect(onDecided).toHaveBeenCalled();
  });

  it('a refused rejection also reloads the list and the report', async () => {
    const failure = new Error('conflict');
    rejectApplication.mockRejectedValue(failure);
    renderSection();

    await waitFor(() => expect(screen.getByText('Acme Automation')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Reject/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Send rejection' }));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(failure));
    await waitFor(() => expect(applications).toHaveBeenCalledTimes(2));
    expect(onDecided).toHaveBeenCalled();
    expect(notify).not.toHaveBeenCalled();
  });

  it('can show decided applications too, without decision buttons', async () => {
    applications.mockImplementation((status: string) => Promise.resolve({
      applications: status === 'all' ? [{ ...ROW, status: 'rejected', decision_note: 'Not yet' }] : [],
    }));
    renderSection();

    await waitFor(() => expect(screen.getByText('No pending application.')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Show decided too' }));

    await waitFor(() => expect(screen.getByText('Rejected')).toBeTruthy());
    expect(screen.getByText('Note sent: Not yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve/ })).toBeNull();
  });
});
