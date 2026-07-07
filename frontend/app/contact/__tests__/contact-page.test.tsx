// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Regression pin: useSearchParams() at page level opts /contact out of server
// rendering entirely (empty HTML for crawlers). The prefill must come from
// window.location inside an effect instead - reintroducing the hook throws.
vi.mock('next/navigation', () => ({
  useSearchParams: () => {
    throw new Error('useSearchParams must not be called in ContactPage (forces CSR bailout of the page)');
  },
}));

vi.mock('next/script', () => ({
  default: () => null,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => {
    return Object.assign((key: string) => ({
      'fields.category': 'Category',
      'fields.message': 'Message',
    }[key] ?? key), {
      rich: (key: string) => key,
    });
  },
}));

import ContactPage from '../page';

describe('ContactPage query prefill', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/contact');
  });

  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it('prefills abuse category and reporter context from the marketplace report link', () => {
    const message = 'Publication: App\nID: pub-1\nReason for reporting:';
    window.history.replaceState(
      null,
      '',
      `/contact?category=abuse&message=${encodeURIComponent(message)}`,
    );

    render(<ContactPage />);

    expect(screen.getByLabelText('Category')).toHaveValue('abuse');
    expect(screen.getByLabelText('Message')).toHaveValue(message);
  });

  it('falls back to support when category query param is invalid', () => {
    window.history.replaceState(
      null,
      '',
      `/contact?category=not-a-category&message=${encodeURIComponent('Keep this message')}`,
    );

    render(<ContactPage />);

    expect(screen.getByLabelText('Category')).toHaveValue('support');
    expect(screen.getByLabelText('Message')).toHaveValue('Keep this message');
  });

  it('keeps the defaults when no query params are present', () => {
    render(<ContactPage />);

    expect(screen.getByLabelText('Category')).toHaveValue('support');
    expect(screen.getByLabelText('Message')).toHaveValue('');
  });
});
