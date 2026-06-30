// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, within } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, any>) => {
    if (key === 'sources') return 'Result sources';
    if (key === 'moreCount') return `+${params?.count ?? ''} more`;
    return key;
  },
}));

import { FaviconStack } from '../FaviconStack';

afterEach(cleanup);

describe('FaviconStack', () => {
  it('renders nothing when given no urls', () => {
    const { container } = render(<FaviconStack urls={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders one chip per unique hostname', () => {
    render(
      <FaviconStack
        urls={['https://example.com/a', 'https://other.org/b', 'https://example.com/c']}
        max={4}
      />,
    );
    const list = screen.getByRole('list', { name: /result sources/i });
    const chips = within(list).getAllByRole('listitem');
    expect(chips).toHaveLength(2);
    expect(chips[0].getAttribute('aria-label')).toBe('example.com');
    expect(chips[1].getAttribute('aria-label')).toBe('other.org');
  });

  it('caps visible chips at max and renders a +N badge for the remainder', () => {
    const urls = [
      'https://a.com',
      'https://b.com',
      'https://c.com',
      'https://d.com',
      'https://e.com',
      'https://f.com',
    ];
    render(<FaviconStack urls={urls} max={4} />);

    const list = screen.getByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(4);

    expect(screen.getByText('+2')).toBeTruthy();
    expect(screen.getByLabelText('+2 more')).toBeTruthy();
  });

  it('does not render a +N badge when count is at or below the cap', () => {
    render(<FaviconStack urls={['https://a.com', 'https://b.com']} max={4} />);
    expect(screen.queryByText(/^\+\d/)).toBeNull();
  });

  it('tolerates malformed URLs by falling back to the raw string as the dedup key', () => {
    render(<FaviconStack urls={['not-a-url', 'not-a-url', 'https://a.com']} max={4} />);
    const list = screen.getByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
  });

  it('lets callers override the aria-label of the list', () => {
    render(<FaviconStack urls={['https://a.com']} ariaLabel="Custom label" />);
    const list = screen.getByRole('list', { name: 'Custom label' });
    expect(list).toBeTruthy();
  });

  it('passes className through to the root container', () => {
    const { container } = render(
      <FaviconStack urls={['https://a.com']} className="ml-auto special" />,
    );
    const root = container.firstChild as HTMLElement;
    expect(root.className).toContain('ml-auto');
    expect(root.className).toContain('special');
    // shrink-0 must always be there so the stack does not collapse in narrow rows.
    expect(root.className).toContain('shrink-0');
  });
});
