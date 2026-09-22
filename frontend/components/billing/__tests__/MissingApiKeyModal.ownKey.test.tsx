// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, fireEvent } from '@testing-library/react';

import en from '@/messages/en.json';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
// The REAL shipped messages, not stand-ins: a key missing from en.json then fails here
// rather than rendering an empty modal in production.
vi.mock('next-intl', async () => {
  const messages = (await import('@/messages/en.json')).default as Record<string, any>;
  return {
    useLocale: () => 'fr',
    useTranslations: (ns: string) => (key: string) =>
      ns.split('.').reduce((o: any, k) => o?.[k], messages)?.[key],
  };
});

const msg = (key: string) => (en as any).modals.missingApiKey[key] as string;

import MissingApiKeyModal, { showMissingApiKeyModal } from '../MissingApiKeyModal';

/**
 * One modal, two situations that must not be confused.
 *
 * "Nothing is configured" sends the reader to the platform credentials page. "Your own key was
 * refused" is a different person with a different fix: they HAVE a key, it lives on another
 * page, and telling them to add one would be wrong. The provider names the case in the error
 * sentence and the chat surface passes it through.
 */

afterEach(() => {
  cleanup();
  push.mockClear();
});

const open = (kind?: 'platform' | 'own-key') => {
  render(<MissingApiKeyModal />);
  act(() => showMissingApiKeyModal(kind));
};

describe('MissingApiKeyModal', () => {
  it('sends a user whose OWN key was rejected to the page that holds that key', () => {
    open('own-key');

    expect(screen.getByText(msg('rejectedTitle'))).toBeInTheDocument();
    expect(screen.getByTestId('missing-api-key-body')).toHaveTextContent('revoked, expired or run out of quota');
    // Both moves named, because switching back to the platform key is the other one.
    expect(screen.getByTestId('missing-api-key-body')).toHaveTextContent('switch the provider back to the platform key');

    fireEvent.click(screen.getByTestId('missing-api-key-cta'));

    expect(push).toHaveBeenCalledWith('/fr/app/settings/ai-providers');
  });

  it('keeps the original case unchanged: nothing configured goes to the credentials page', () => {
    open();

    expect(screen.getByText(msg('title'))).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('missing-api-key-cta'));

    expect(push).toHaveBeenCalledWith('/fr/app/settings/credentials');
  });

  it('an unknown kind falls back to the original case rather than accusing the user', () => {
    render(<MissingApiKeyModal />);
    act(() => {
      window.dispatchEvent(new CustomEvent('missingApiKey', { detail: { kind: 'something-else' } }));
    });

    expect(screen.getByText(msg('title'))).toBeInTheDocument();
  });

  it('says the same thing the provider said: the backend sentence and this modal cannot drift', () => {
    // The chat surface decides WHICH of the two cases this is by matching the provider's
    // sentence (AbstractLLMProvider.ownKeyRejectedMessage). Both sides are strings in two
    // languages with no compiler between them, so pin the overlap that carries the meaning.
    const body = msg('rejectedDescription');
    expect(body).toContain('revoked, expired or run out of quota');
    expect(body).toContain('switch the provider back to the platform key');
  });
});
