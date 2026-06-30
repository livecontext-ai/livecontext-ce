// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';

// The legacy /settings/cloud-link routes were merged into /settings/cloud-account.
// Both pages are now thin client redirects - assert they replace() to the
// canonical path, preserving locale (and token for the recovery sub-route).
const replace = vi.fn();
let params: Record<string, unknown> = {};
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  useParams: () => params,
}));

import CloudLinkRedirectPage from '../page';
import CloudLinkRecoverRedirectPage from '../recover/[token]/page';

afterEach(() => {
  cleanup();
  replace.mockClear();
  params = {};
});

describe('legacy cloud-link routes redirect into the unified cloud-account page', () => {
  it('the install-list route replaces to /<locale>/app/settings/cloud-account', () => {
    params = { locale: 'fr' };
    render(<CloudLinkRedirectPage />);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledWith('/fr/app/settings/cloud-account');
  });

  it('the recover route preserves locale + token into cloud-account/recover', () => {
    params = { locale: 'de', token: 'tok_ABC-123' };
    render(<CloudLinkRecoverRedirectPage />);
    expect(replace).toHaveBeenCalledWith('/de/app/settings/cloud-account/recover/tok_ABC-123');
  });

  it('the recover route falls back to the en locale when locale is missing', () => {
    params = { token: 'xyz' };
    render(<CloudLinkRecoverRedirectPage />);
    expect(replace).toHaveBeenCalledWith('/en/app/settings/cloud-account/recover/xyz');
  });

  it('the recover route url-encodes tokens with reserved characters', () => {
    params = { locale: 'en', token: 'a/b c' };
    render(<CloudLinkRecoverRedirectPage />);
    expect(replace).toHaveBeenCalledWith('/en/app/settings/cloud-account/recover/a%2Fb%20c');
  });
});
