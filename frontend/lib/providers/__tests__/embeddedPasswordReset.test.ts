// @vitest-environment jsdom
/**
 * The client half of the wire contract, which nothing tested.
 *
 * Both page suites mock these two functions and no other file imports them, so
 * three separate mutations shipped green with `tsc --noEmit` clean: a
 * one-character typo in the path (`reset-passwrod`), a renamed body key
 * (`password` instead of `newPassword`), and `method: 'GET'`. Any of them makes
 * every reset attempt fail for every user, and CI would not have noticed.
 *
 * The server half IS pinned, to the exact keys `token` and `newPassword`
 * (EmbeddedAuthControllerResetTest). So both ends were tested and nothing
 * checked that they agree, which is the only thing that matters.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { embeddedForgotPassword, embeddedResetPassword } from '../embedded-auth-provider';

/** The paths the Next proxy forwards to the gateway, verbatim. */
const FORGOT_PATH = '/api/proxy/auth/forgot-password';
const RESET_PATH = '/api/proxy/auth/reset-password';

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function ok(body: unknown = { success: true }) {
  return { ok: true, status: 200, json: async () => body };
}

function refused(status: number, body: unknown) {
  return { ok: false, status, json: async () => body };
}

function callOf(index = 0) {
  const [url, init] = fetchMock.mock.calls[index];
  return { url: String(url), init: init as RequestInit, body: JSON.parse(String(init?.body)) };
}

describe('embeddedForgotPassword', () => {
  it('POSTs the address as JSON to the forgot-password proxy path', async () => {
    fetchMock.mockResolvedValue(ok());

    await embeddedForgotPassword('owner@example.com');

    const { url, init, body } = callOf();
    expect(url).toBe(FORGOT_PATH);
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    // The server reads body.get("email"); any other key is a silent no-op that
    // still answers 200, because the endpoint cannot distinguish an unknown
    // address from a missing one.
    expect(body).toEqual({ email: 'owner@example.com' });
  });

  it('reports success on 200', async () => {
    fetchMock.mockResolvedValue(ok());
    await expect(embeddedForgotPassword('owner@example.com')).resolves.toEqual({ success: true });
  });

  it('reports failure on a non-2xx, without inventing success', async () => {
    fetchMock.mockResolvedValue(refused(503, { message: 'nope' }));

    const result = await embeddedForgotPassword('owner@example.com');

    expect(result.success).toBe(false);
    expect(result.error).toBeTruthy();
  });

  it('reports failure when the request never completes', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));

    const result = await embeddedForgotPassword('owner@example.com');

    expect(result).toEqual({ success: false, error: 'offline' });
  });
});

describe('embeddedResetPassword', () => {
  it('POSTs token and newPassword under EXACTLY those keys, to the reset-password proxy path', async () => {
    fetchMock.mockResolvedValue(ok());

    await embeddedResetPassword('a-real-token', 'a-good-password');

    const { url, init, body } = callOf();
    expect(url).toBe(RESET_PATH);
    expect(init.method).toBe('POST');
    // `token` and `newPassword` are what the controller reads. Rename either and
    // the server sees null, the service refuses, and the user is told their link
    // is invalid forever.
    expect(body).toEqual({ token: 'a-real-token', newPassword: 'a-good-password' });
  });

  it('reports success on 200 and saves NO tokens (the backend just revoked them all)', async () => {
    fetchMock.mockResolvedValue(ok());
    const before = { ...localStorage };

    await expect(embeddedResetPassword('a-real-token', 'a-good-password'))
      .resolves.toEqual({ success: true });

    expect({ ...localStorage }).toEqual(before);
  });

  it('surfaces the refusal on a 400 rather than claiming the password was changed', async () => {
    fetchMock.mockResolvedValue(refused(400, { message: 'This reset link is not valid.' }));

    const result = await embeddedResetPassword('stale', 'a-good-password');

    expect(result.success).toBe(false);
    expect(result.error).toContain('not valid');
  });

  it('reports failure when the request never completes', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));

    await expect(embeddedResetPassword('a-real-token', 'a-good-password'))
      .resolves.toEqual({ success: false, error: 'offline' });
  });

  it('does not fall over when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error('not json');
      },
    });

    const result = await embeddedResetPassword('a-real-token', 'a-good-password');

    // A gateway 502 usually carries HTML. The helper must still answer, or the
    // page hangs on a spinner.
    expect(result.success).toBe(false);
    expect(result.error).toBeTruthy();
  });
});
