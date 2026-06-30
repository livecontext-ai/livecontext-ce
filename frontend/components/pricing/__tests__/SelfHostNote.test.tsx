// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => {
    const map: Record<string, string> = {
      selfHostNote: 'Prefer to self-host? Link the free CE to any plan.',
      selfHostCta: 'View on GitHub',
    };
    return map[key] ?? key;
  },
}));

import SelfHostNote from '../SelfHostNote';

afterEach(cleanup);

describe('SelfHostNote', () => {
  it('renders the self-host note text', () => {
    render(<SelfHostNote />);
    expect(screen.getByText(/Prefer to self-host/i)).toBeTruthy();
  });

  it('renders a single external GitHub link pointing at the shared constant', () => {
    render(<SelfHostNote />);
    const link = screen.getByRole('link', { name: /View on GitHub/i });
    expect(link.getAttribute('href')).toBe(SELF_HOSTED_GITHUB_URL);
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel') || '').toContain('noopener');
  });

  it('forwards a custom className', () => {
    const { container } = render(<SelfHostNote className="mt-3 text-center" />);
    expect((container.firstChild as HTMLElement).className).toContain('mt-3');
  });
});
