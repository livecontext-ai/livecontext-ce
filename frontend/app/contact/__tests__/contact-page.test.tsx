// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

const getSearchParam = vi.hoisted(() => vi.fn<(key: string) => string | null>());

vi.mock('next/navigation', () => ({
  useSearchParams: () => ({
    get: getSearchParam,
  }),
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
    getSearchParam.mockReset();
  });

  it('prefills abuse category and reporter context from the marketplace report link', () => {
    getSearchParam.mockImplementation((key: string) => {
      if (key === 'category') return 'abuse';
      if (key === 'message') return 'Publication: App\nID: pub-1\nReason for reporting:';
      return null;
    });

    render(<ContactPage />);

    expect(screen.getByLabelText('Category')).toHaveValue('abuse');
    expect(screen.getByLabelText('Message')).toHaveValue('Publication: App\nID: pub-1\nReason for reporting:');
  });

  it('falls back to support when category query param is invalid', () => {
    getSearchParam.mockImplementation((key: string) => {
      if (key === 'category') return 'not-a-category';
      if (key === 'message') return 'Keep this message';
      return null;
    });

    render(<ContactPage />);

    expect(screen.getByLabelText('Category')).toHaveValue('support');
    expect(screen.getByLabelText('Message')).toHaveValue('Keep this message');
  });
});
