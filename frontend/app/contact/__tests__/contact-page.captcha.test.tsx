// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

/**
 * Regression suite for the "Network error - please try again later." reported from
 * /contact?category=bug&message=... (the workflow-node "Report a problem" deep-link).
 *
 * <p>Nothing was wrong with the network. reCAPTCHA had never been configured in
 * production: NEXT_PUBLIC_RECAPTCHA_SITE_KEY was referenced by the page and passed by
 * no build lane, so the bundle shipped an empty key. api.js?render= still answers 200
 * and defines window.grecaptcha, so the widget reported itself ready and only failed
 * inside execute() - a rejection the page caught in the SAME try as fetch() and
 * reported as a network failure. The visitor was told to retry a request that had
 * never been sent and could never succeed, and lost the ticket they had written.
 *
 * <p>The tests below pin the three halves of that: an unconfigured key is announced up
 * front, a captcha rejection reads as a captcha failure, and a real network failure
 * still reads as one.
 */

vi.mock('next/navigation', () => ({
  useSearchParams: () => {
    throw new Error('useSearchParams must not be called in ContactPage (forces CSR bailout of the page)');
  },
}));

// next/script only renders; the real one calls onLoad once the tag has loaded. The mock
// reproduces that, because recaptchaReady - and therefore which branch of handleSubmit
// runs - depends on it. A mock that never fires onLoad would send every case below down
// the "still loading" path and prove nothing.
vi.mock('next/script', () => ({
  default: (props: { src?: string; onLoad?: () => void }) => {
    React.useEffect(() => { props.onLoad?.(); }, []);
    return React.createElement('div', { 'data-testid': 'recaptcha-script', 'data-src': props.src });
  },
}));

// Identity translator: assertions compare message KEYS, so rewording the English copy
// can neither break them nor make a wrong branch look right.
vi.mock('next-intl', () => ({
  useTranslations: () => Object.assign((key: string) => ({
    'fields.name': 'Name',
    'fields.email': 'Email',
    'fields.category': 'Category',
    'fields.message': 'Message',
  }[key] ?? key), {
    rich: (key: string) => key,
  }),
}));

const SITE_KEY = 'test-site-key';

let execute: ReturnType<typeof vi.fn>;
let fetchMock: ReturnType<typeof vi.fn>;

/** Render the page with the site key baked into the bundle, or with none at all. */
async function renderPage(siteKey: string | null) {
  vi.resetModules();
  if (siteKey === null) delete process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
  else process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY = siteKey;
  const { default: ContactPage } = await import('../page');
  render(React.createElement(ContactPage));
}

function fillAndSubmit() {
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Jane Doe' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'jane@example.com' } });
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Resolve Channel node fails' } });
  // Submitted on the form, not by clicking: jsdom constraint validation would otherwise
  // decide whether handleSubmit runs, which is not what any of these tests is about.
  fireEvent.submit(screen.getByLabelText('Message').closest('form') as HTMLFormElement);
}

describe('ContactPage captcha and network failures are told apart', () => {
  beforeEach(() => {
    execute = vi.fn();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    (window as any).grecaptcha = { ready: (cb: () => void) => cb(), execute };
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    delete (window as any).grecaptcha;
    delete process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
  });

  it('announces an unconfigured site key up front instead of accepting a ticket it must drop', async () => {
    // Matches production: with no key the script is never rendered, so nothing defines
    // window.grecaptcha. beforeEach installs it for the configured cases, so drop it here
    // rather than proving the new guard beats a state that cannot occur.
    delete (window as any).grecaptcha;
    await renderPage(null);

    expect(screen.getByRole('alert')).toHaveTextContent('unavailable');
    expect(screen.getByRole('button', { name: 'send' })).toBeDisabled();
    // The empty-key script is what made the widget look ready while execute() could only fail.
    expect(screen.queryByTestId('recaptcha-script')).not.toBeInTheDocument();
  });

  it('refuses a submit with no site key without calling the network', async () => {
    delete (window as any).grecaptcha;
    await renderPage(null);

    // The banner already renders 'unavailable', so asserting merely that the text is PRESENT
    // passes with the guard deleted. Counting the occurrences is what separates the banner
    // from the form's own status line, and the recaptchaLoading assertion is what proves the
    // unconfigured guard ran rather than the not-ready guard immediately behind it.
    expect(screen.getAllByText('unavailable')).toHaveLength(1);

    fillAndSubmit();

    await waitFor(() => expect(screen.getAllByText('unavailable')).toHaveLength(2));
    expect(screen.queryByText('errors.recaptchaLoading')).not.toBeInTheDocument();
    expect(execute).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('treats a whitespace-only site key as no key at all', async () => {
    // A build arg set to a blank line is what a half-filled secret looks like, and
    // `render=%20` would make api.js define grecaptcha exactly as an empty key does. The
    // .trim() on the module constant is the only thing separating the two.
    delete (window as any).grecaptcha;
    await renderPage('   ');

    expect(screen.getByRole('alert')).toHaveTextContent('unavailable');
    expect(screen.getByRole('button', { name: 'send' })).toBeDisabled();
    expect(screen.queryByTestId('recaptcha-script')).not.toBeInTheDocument();
  });

  it('still says the widget is loading when the key IS set but grecaptcha has not arrived', async () => {
    delete (window as any).grecaptcha;
    await renderPage(SITE_KEY);

    fillAndSubmit();

    // Pins the ORDER of the two early returns: the unconfigured guard sits directly in front
    // of this one and must not swallow it.
    await waitFor(() => expect(screen.getByText('errors.recaptchaLoading')).toBeInTheDocument());
    expect(screen.queryByText('unavailable')).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports a rejected grecaptcha.execute as a captcha failure, not as a network error', async () => {
    execute.mockRejectedValue(new Error('Invalid site key or not loaded in api.js'));
    await renderPage(SITE_KEY);

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('errors.captchaUnavailable')).toBeInTheDocument());
    expect(screen.queryByText('errors.network')).not.toBeInTheDocument();
    // The request was never sent, so "network error" described nothing that happened.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports an empty captcha token as a captcha failure rather than posting it', async () => {
    execute.mockResolvedValue('');
    await renderPage(SITE_KEY);

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('errors.captchaUnavailable')).toBeInTheDocument());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('still reports a genuine fetch failure as a network error', async () => {
    execute.mockResolvedValue('token');
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await renderPage(SITE_KEY);

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('errors.network')).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('tells the visitor the form is unavailable when the SERVER has no captcha secret', async () => {
    execute.mockResolvedValue('token');
    fetchMock.mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: 'captcha_misconfigured' }),
    });
    await renderPage(SITE_KEY);

    fillAndSubmit();

    // Permanent, so it must not read as the transient "try again in a moment".
    await waitFor(() => expect(screen.getByText('unavailable')).toBeInTheDocument());
    expect(screen.queryByText('errors.captchaUnavailable')).not.toBeInTheDocument();
  });

  it('keeps reporting a transient captcha outage as retryable', async () => {
    execute.mockResolvedValue('token');
    fetchMock.mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: 'captcha_unavailable' }),
    });
    await renderPage(SITE_KEY);

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('errors.captchaUnavailable')).toBeInTheDocument());
  });

  it('sends the captcha token and clears the form once configured end to end', async () => {
    execute.mockResolvedValue('token');
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ status: 'sent' }) });
    await renderPage(SITE_KEY);

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('success')).toBeInTheDocument());
    expect(execute).toHaveBeenCalledWith(SITE_KEY, { action: 'contact' });
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.captchaToken).toBe('token');
    expect(screen.getByLabelText('Message')).toHaveValue('');
  });
});
