// @vitest-environment jsdom
/**
 * Render-level checks for the /changelog RSC: releases from the GitHub API
 * render as titled, dated, markdown-formatted entries; an unavailable API
 * degrades to the GitHub fallback link instead of breaking the landing page.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import ChangelogPage from '../page';

const ghRelease = {
  tag_name: 'v0.1.5',
  name: 'LiveContext CE v0.1.5',
  published_at: '2026-07-02T12:30:00Z',
  html_url: 'https://github.com/livecontext-ai/livecontext-ce/releases/tag/v0.1.5',
  body: '## What is new\n\n- Rerun cycle fix',
  draft: false,
  prerelease: false,
};

describe('ChangelogPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders each published release with title, ISO date and markdown body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [ghRelease] }));

    render(await ChangelogPage());

    expect(screen.getByRole('heading', { name: 'LiveContext CE v0.1.5' })).toBeTruthy();
    expect(screen.getByText('2026-07-02')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'What is new' })).toBeTruthy();
    expect(screen.getByText('Rerun cycle fix')).toBeTruthy();
    const gh = screen.getByRole('link', { name: 'View on GitHub' }) as HTMLAnchorElement;
    expect(gh.href).toBe(ghRelease.html_url);
  });

  it('falls back to the GitHub releases link when the API is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('rate limited')));

    render(await ChangelogPage());

    expect(screen.getByText(/momentarily unavailable/)).toBeTruthy();
    const links = screen.getAllByRole('link') as HTMLAnchorElement[];
    expect(links.some((l) => l.href === 'https://github.com/livecontext-ai/livecontext-ce/releases')).toBe(true);
  });
});
