// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';

// Mutable pathname holder, created before the mock factory runs.
const nav = vi.hoisted(() => ({ path: '/' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.path }));
vi.mock('next/link', () => {
  const React = require('react');
  return {
    default: ({ href, children, ...rest }: { href: unknown; children: React.ReactNode }) =>
      React.createElement('a', { href: typeof href === 'string' ? href : '#', ...rest }, children),
  };
});

import { DocsNav } from '../_components/DocsNav';
import { slugify } from '../_components/DocsToc';

afterEach(cleanup);

describe('DocsNav', () => {
  // If any docs component reached for a next-intl hook, this render would throw
  // "No intl context found" (no provider is mounted here) - so a passing render
  // is itself the regression guard for the i18n-free constraint.
  it('renders the sections and links from the IA', () => {
    nav.path = '/';
    render(<DocsNav />);
    expect(screen.getByText('Get started')).toBeInTheDocument();
    // AI and Data are separate sections.
    expect(screen.getByText('AI')).toBeInTheDocument();
    expect(screen.getByText('Data')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Agents' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Marketplace' })).toBeInTheDocument();
  });

  it('marks the current page active and leaves others inactive', () => {
    nav.path = '/agents';
    render(<DocsNav />);
    expect(screen.getByRole('link', { name: 'Agents' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Workflows' })).not.toHaveAttribute('aria-current');
  });

  it('renders roadmap stubs as non-link text with a badge', () => {
    nav.path = '/';
    render(<DocsNav />);
    expect(screen.queryByRole('link', { name: /Access, roles/ })).toBeNull();
    expect(screen.getByText('Access, roles & SSO')).toBeInTheDocument();
    expect(screen.getAllByText('Soon').length).toBeGreaterThan(0);
  });

  it('filters the visible pages as the user types', () => {
    nav.path = '/';
    render(<DocsNav />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Filter documentation' }), {
      target: { value: 'agent' },
    });
    expect(screen.getByRole('link', { name: 'Agents' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Marketplace' })).toBeNull();
  });
});

describe('slugify (DocsToc heading ids)', () => {
  it('lowercases and hyphenates words', () => {
    expect(slugify('Core Concepts')).toBe('core-concepts');
  });

  it('strips punctuation', () => {
    expect(slugify('Why an agent stopped?')).toBe('why-an-agent-stopped');
  });

  it('falls back to "section" for empty or symbol-only text', () => {
    expect(slugify('')).toBe('section');
    expect(slugify('!!!')).toBe('section');
  });

  it('truncates very long headings to 60 chars', () => {
    expect(slugify('a'.repeat(200)).length).toBeLessThanOrEqual(60);
  });
});
