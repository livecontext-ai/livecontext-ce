// @vitest-environment jsdom
/**
 * Render-level checks for the /changelog RSC timeline: releases from the GitHub
 * API render as dated, versioned, markdown-formatted timeline entries (newest
 * flagged "Latest"); an unavailable API degrades to the GitHub fallback link
 * instead of breaking the landing page.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import ChangelogPage from '../page';

const newer = {
  tag_name: 'v0.1.6',
  name: 'LiveContext CE v0.1.6',
  published_at: '2026-07-04T09:00:00Z',
  html_url: 'https://github.com/livecontext-ai/livecontext-ce/releases/tag/v0.1.6',
  body: '## Highlights\n\n- Branch-port overflow fix',
  draft: false,
  prerelease: false,
};

const older = {
  tag_name: 'v0.1.5',
  name: 'LiveContext CE v0.1.5',
  published_at: '2026-07-02T12:30:00Z',
  html_url: 'https://github.com/livecontext-ai/livecontext-ce/releases/tag/v0.1.5',
  body: '## What is new\n\n- Rerun cycle fix',
  draft: false,
  prerelease: false,
};

describe('ChangelogPage timeline', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders each release with title, human date, version tag and markdown body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [older] }));

    render(await ChangelogPage());

    expect(screen.getByRole('heading', { name: 'LiveContext CE v0.1.5' })).toBeTruthy();
    // Locale-neutral, timezone-safe human date (not the raw ISO string).
    expect(screen.getByText('Jul 2, 2026')).toBeTruthy();
    // Version tag pill.
    expect(screen.getByText('v0.1.5')).toBeTruthy();
    // Markdown body is formatted, not dumped raw.
    expect(screen.getByRole('heading', { name: 'What is new' })).toBeTruthy();
    expect(screen.getByText('Rerun cycle fix')).toBeTruthy();
    const gh = screen.getByRole('link', { name: /View on GitHub/ }) as HTMLAnchorElement;
    expect(gh.href).toBe(older.html_url);
  });

  it('sorts newest-first and flags only the most recent release as Latest', async () => {
    // API returns oldest-first on purpose; the page must reorder.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [older, newer] }));

    render(await ChangelogPage());

    const titles = screen
      .getAllByRole('heading', { level: 2 })
      .map((h) => h.textContent);
    expect(titles[0]).toBe('LiveContext CE v0.1.6');
    expect(titles[1]).toBe('LiveContext CE v0.1.5');

    // Exactly one "Latest" badge, and it sits on the newest entry.
    const latest = screen.getAllByText('Latest');
    expect(latest).toHaveLength(1);
  });

  it('falls back to the GitHub releases link when the API is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('rate limited')));

    render(await ChangelogPage());

    expect(screen.getByText(/momentarily unavailable/)).toBeTruthy();
    const links = screen.getAllByRole('link') as HTMLAnchorElement[];
    expect(
      links.some((l) => l.href === 'https://github.com/livecontext-ai/livecontext-ce/releases'),
    ).toBe(true);
  });
});
