// @vitest-environment jsdom
/**
 * The reset-password screen.
 *
 * The behaviour worth pinning is not the happy path, it is the two client-side
 * refusals: a reset token works ONCE, so a password that the backend would
 * reject anyway must never reach it. If the too-short and mismatch checks are
 * dropped, the user burns their link on a typo and has to wait for another
 * e-mail. Both tests below assert the API was not called at all.
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

const search = { token: 'a-real-token' as string | null };

/**
 * `replace` CLEARS the query, the way the browser does.
 *
 * A static mock that kept answering with the token made the capture test a
 * tautology: dropping the `useState` capture from the page left it green, while
 * in production the rewrite would blank the token on the next render and the
 * page would flip to "this link is incomplete" before anyone could type. The
 * mock has to model the thing the page is defending against.
 */
const replaceSpy = vi.fn((url: string) => {
  if (!String(url).includes('token=')) search.token = null;
});
vi.mock('next/navigation', () => ({
  useSearchParams: () => ({ get: (k: string) => (k === 'token' ? search.token : null) }),
  useRouter: () => ({ push: vi.fn(), replace: replaceSpy }),
}));

vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const resetSpy = vi.fn();
vi.mock('@/lib/providers/embedded-auth-provider', () => ({
  embeddedResetPassword: (token: string, password: string) => resetSpy(token, password),
}));

import ResetPasswordPage from '../page';

function fill(password: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('newPassword'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('confirm'), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: /submit/ }));
}

beforeEach(() => {
  editionMock.IS_CLOUD = false;
  search.token = 'a-real-token';
  replaceSpy.mockReset();
  resetSpy.mockReset();
  resetSpy.mockResolvedValue({ success: true });
});

afterEach(() => cleanup());

describe('ResetPasswordPage', () => {
  it('refuses a 7-character password, pinning the mirrored minimum as a NUMBER', async () => {
    // The backend pins 8 with a literal in PasswordAuthServiceTest because
    // nothing can enforce the mirror across the two languages. Measured: without
    // this case, changing the constant to 7 left all 9 tests green, so the drift
    // the backend comment warns about was undetected on this side.
    render(<ResetPasswordPage />);

    fill('7charac', '7charac');

    expect(resetSpy).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });


  it('does not spend the token on a password the backend would refuse anyway', () => {
    render(<ResetPasswordPage />);

    fill('short', 'short');

    expect(resetSpy).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('does not spend the token when the two fields disagree', () => {
    render(<ResetPasswordPage />);

    fill('a-good-password', 'a-good-passwrod');

    expect(resetSpy).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('mismatch');
  });

  it('sends the token and the password once both checks pass', async () => {
    render(<ResetPasswordPage />);

    fill('a-good-password', 'a-good-password');

    await waitFor(() => expect(resetSpy).toHaveBeenCalledWith('a-real-token', 'a-good-password'));
  });

  it('shows the done screen and offers sign-in, without signing the user in', async () => {
    render(<ResetPasswordPage />);

    fill('a-good-password', 'a-good-password');

    // The backend has just revoked every refresh token, so the only honest next
    // step is to authenticate again.
    await waitFor(() => expect(screen.getByText('doneTitle')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'goToLogin' })).toHaveAttribute('href', '/en/login');
  });

  it('shows the LOCALIZED refusal, never the server text, and does not claim success', async () => {
    // The server message is English-only; this page ships in six languages.
    resetSpy.mockResolvedValue({ success: false, error: 'This reset link has expired.' });
    render(<ResetPasswordPage />);

    fill('a-good-password', 'a-good-password');

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('error'));
    expect(screen.getByRole('alert')).not.toHaveTextContent('This reset link has expired.');
    expect(screen.queryByText('doneTitle')).not.toBeInTheDocument();
  });

  it('takes the token OUT of the address bar, and keeps working with it', async () => {
    render(<ResetPasswordPage />);

    // A reset link is a bearer credential for the account. Leaving it in the URL
    // leaves it in history, in screenshots, and in any page-view the app emits.
    await waitFor(() =>
      expect(replaceSpy).toHaveBeenCalledWith('/en/reset-password', { scroll: false }),
    );
    expect(String(replaceSpy.mock.calls[0][0])).not.toContain('a-real-token');

    // And the rewrite must not cost the page the token it was given: it was read
    // once into state, so the form still submits the real value.
    fill('a-good-password', 'a-good-password');
    await waitFor(() => expect(resetSpy).toHaveBeenCalledWith('a-real-token', 'a-good-password'));
  });

  it('does not rewrite the URL when there was no token to hide', () => {
    search.token = null;
    render(<ResetPasswordPage />);

    expect(replaceSpy).not.toHaveBeenCalled();
  });

  it('on cloud it renders nothing usable and never touches the token', () => {
    editionMock.IS_CLOUD = true;
    render(<ResetPasswordPage />);

    // The endpoint behind this form is embedded-only, so a submit here could
    // only 404: no form, and nothing sent.
    expect(screen.queryByLabelText('newPassword')).not.toBeInTheDocument();
    expect(screen.getByText('cloudNotice')).toBeInTheDocument();
    expect(resetSpy).not.toHaveBeenCalled();

    // The URL is still cleaned, which is deliberate rather than incidental: the
    // effect runs before the edition check because hooks always do, and a cloud
    // build is exactly where analytics captures $current_url. Stripping a
    // credential we are not going to use costs nothing and is the safer default.
    expect(replaceSpy).toHaveBeenCalledWith('/en/reset-password', { scroll: false });
  });

  it('refuses to show the form at all when the link arrived without its token', () => {
    search.token = null;
    render(<ResetPasswordPage />);

    expect(screen.getByText('missingTokenTitle')).toBeInTheDocument();
    expect(screen.queryByLabelText('newPassword')).not.toBeInTheDocument();
  });
});
