// @vitest-environment jsdom
/**
 * The forgot-password screen.
 *
 * The behaviour worth pinning is what the page refuses to do. It must show the
 * SAME success state for an address that has an account and one that does not,
 * because the backend answers identically on purpose and a page that told them
 * apart would put the oracle back on the client. And it has to say out loud that
 * a self-hosted install needs its own mail server, because that is the only
 * remaining channel for "no mail will ever arrive": there is no such backend
 * answer, and there cannot be (spring.mail.host defaults to localhost in every
 * edition, so the question is unanswerable server-side).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const editionMock = vi.hoisted(() => ({ IS_CLOUD: false }));
vi.mock('@/lib/edition', () => editionMock);

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
  useLocale: () => 'en',
}));

vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const forgotSpy = vi.fn();
vi.mock('@/lib/providers/embedded-auth-provider', () => ({
  embeddedForgotPassword: (email: string) => forgotSpy(email),
}));

import ForgotPasswordPage from '../page';

function submit(email: string) {
  fireEvent.change(screen.getByLabelText('email'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: /submit/ }));
}

beforeEach(() => {
  editionMock.IS_CLOUD = false;
  forgotSpy.mockReset();
  forgotSpy.mockResolvedValue({ success: true });
});

afterEach(() => cleanup());

describe('ForgotPasswordPage', () => {
  it('sends the address and shows the sent state', async () => {
    render(<ForgotPasswordPage />);

    submit('owner@example.com');

    await waitFor(() => expect(forgotSpy).toHaveBeenCalledWith('owner@example.com'));
    await waitFor(() => expect(screen.getByText('sentTitle')).toBeInTheDocument());
  });

  it('shows the SAME sent state for an address with no account', async () => {
    // The backend cannot tell the page which it was, and the page must not
    // invent a difference: any distinction here is an account-existence oracle.
    render(<ForgotPasswordPage />);
    submit('nobody@example.com');
    await waitFor(() => expect(screen.getByText('sentTitle')).toBeInTheDocument());
    const unknownState = document.body.textContent;
    cleanup();

    render(<ForgotPasswordPage />);
    submit('owner@example.com');
    await waitFor(() => expect(screen.getByText('sentTitle')).toBeInTheDocument());

    expect(document.body.textContent).toBe(unknownState);
  });

  it('names the mail-server possibility in the sent state, which is the only place it can be said', async () => {
    render(<ForgotPasswordPage />);

    submit('owner@example.com');

    // sentHint replaced a 503 that could never fire. If it disappears, a
    // self-hoster with no relay is left waiting for a mail nobody will send,
    // with no explanation anywhere in the product.
    await waitFor(() => expect(screen.getByText('sentHint')).toBeInTheDocument());
  });

  it('shows the LOCALIZED error and NOT the sent state when the request itself failed', async () => {
    forgotSpy.mockResolvedValue({ success: false, error: 'Network error' });
    render(<ForgotPasswordPage />);

    submit('owner@example.com');

    // 'Network error' is a hardcoded English literal in the provider.
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('error'));
    expect(screen.getByRole('alert')).not.toHaveTextContent('Network error');
    expect(screen.queryByText('sentTitle')).not.toBeInTheDocument();
  });

  it('on cloud it offers no form and calls nothing, because Keycloak owns the flow there', () => {
    editionMock.IS_CLOUD = true;
    render(<ForgotPasswordPage />);

    expect(screen.getByText('cloudNotice')).toBeInTheDocument();
    expect(screen.queryByLabelText('email')).not.toBeInTheDocument();
    expect(forgotSpy).not.toHaveBeenCalled();
  });
});
