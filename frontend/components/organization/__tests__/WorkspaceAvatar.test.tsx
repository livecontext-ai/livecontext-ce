// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { WorkspaceAvatar, getWorkspaceInitials } from '../WorkspaceAvatar';

describe('getWorkspaceInitials', () => {
  it('takes first + last word initials for a multi-word name (drops "Workspace")', () => {
    expect(getWorkspaceInitials("ada lovelace's Workspace")).toBe('AL');
  });

  it('takes the first two letters of a single-token name', () => {
    expect(getWorkspaceInitials("livecontextai's Workspace")).toBe('LI');
  });

  it('uppercases and caps at two characters', () => {
    expect(getWorkspaceInitials('acme corp')).toBe('AC');
  });

  it('falls back to "?" for an empty / whitespace name', () => {
    expect(getWorkspaceInitials('   ')).toBe('?');
    expect(getWorkspaceInitials('')).toBe('?');
  });

  it('reverts to the original name when stripping "Workspace" leaves nothing', () => {
    // "Workspace" alone strips to "" → fall back to the un-stripped name.
    expect(getWorkspaceInitials('Workspace')).toBe('WO');
    // "My Workspace" → after dropping "Workspace", one token "My" remains.
    expect(getWorkspaceInitials('My Workspace')).toBe('MY');
  });
});

describe('WorkspaceAvatar', () => {
  afterEach(cleanup);

  it('renders the initials chip when no avatarUrl is provided', () => {
    render(<WorkspaceAvatar name="ada lovelace's Workspace" />);
    expect(screen.getByText('AL')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('renders the <img> OVER the persistent initials when an avatarUrl is provided (anti-blink)', () => {
    const { container } = render(<WorkspaceAvatar name="Acme" avatarUrl="/avatar.png" />);
    const img = container.querySelector('img') as HTMLImageElement;
    // Not an /api/ path → passed through unchanged.
    expect(img).toHaveAttribute('src', '/avatar.png');
    // The initials chip stays mounted underneath: a fresh mount (e.g. the user menu
    // opening) shows stable initials instead of an empty circle that pops to the photo.
    expect(screen.getByText('AC')).toBeInTheDocument();
    // The image only fades in once loaded.
    expect(img.className).toContain('opacity-0');
    fireEvent.load(img);
    expect(img.className).toContain('opacity-100');
  });

  it('routes a backend /api/ avatar URL through the Next.js proxy', () => {
    const { container } = render(<WorkspaceAvatar name="Acme" avatarUrl="/api/organizations/org-1/avatar?v=abc" />);
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      '/api/proxy/organizations/org-1/avatar?v=abc'
    );
  });

  it('passes object/blob URLs through untouched', () => {
    const { container } = render(<WorkspaceAvatar name="Acme" avatarUrl="blob:http://x/123" />);
    expect(container.querySelector('img')).toHaveAttribute('src', 'blob:http://x/123');
  });

  it('picks a deterministic background colour from the name', () => {
    const { container: a } = render(<WorkspaceAvatar name="Acme Corp" />);
    const first = a.firstChild as HTMLElement;
    cleanup();
    const { container: b } = render(<WorkspaceAvatar name="Acme Corp" />);
    const second = b.firstChild as HTMLElement;

    const bgOf = (el: HTMLElement) => el.className.match(/bg-[a-z]+-500/)?.[0];
    expect(bgOf(first)).toBeDefined();
    expect(bgOf(first)).toBe(bgOf(second));
  });
});
