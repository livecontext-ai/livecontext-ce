// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

// Importing TaskDetailPanel pulls next-intl at module scope; mocking it avoids
// resolving its next/navigation submodule under vitest (same pattern as the
// sibling TaskDetailPanel.actions test).
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// AgentPanelContent transitively imports next-intl's navigation client (which can't
// resolve under vitest). UserBadge never touches it, so stub it out - same as the
// sibling TaskDetailPanel.actions test. We deliberately do NOT mock the avatar
// components: PublisherAvatar must run for real so this asserts the actual fix.
vi.mock('@/components/app/AgentPanelContent', () => ({
  AGENT_CONVERSATION_TAB: 'conversation',
  AgentPanelContent: () => null,
}));

import { UserBadge } from '../TaskDetailPanel';

// Regression for the "cracked avatar" bug: a human teammate's stored avatar key is
// a raw storage UUID (e.g. "6875e63b-..."), NOT a URL. Rendering it straight into
// <img src> 404s. Human avatars MUST go through PublisherAvatar, which hits the
// stable endpoint /api/proxy/users/{userId}/avatar (real photo + server fallback).
//
// We render the REAL PublisherAvatar (not mocked) so this test fails on the pre-fix
// code (a bare <img src={uuid}>) and passes once UserBadge delegates to PublisherAvatar.

const RAW_AVATAR_KEY = '6875e63b-1234-4abc-9def-000000000000';
const USER_ID = '42';

describe('teammate (human) avatars use PublisherAvatar, never a raw UUID img', () => {
  afterEach(() => cleanup());

  it('UserBadge renders an avatar img pointing at the stable user-avatar endpoint', () => {
    render(<UserBadge userId={USER_ID} name="Ada Lovelace" />);

    const img = screen.getByRole('img');
    // PublisherAvatar derives the src from the userId, hitting the proxy endpoint…
    expect(img).toHaveAttribute('src', `/api/proxy/users/${USER_ID}/avatar`);
    // …and NEVER renders the raw storage key as the src (the cracked-image bug).
    expect(img).not.toHaveAttribute('src', RAW_AVATAR_KEY);
  });

  it('still shows the teammate display name next to the avatar', () => {
    render(<UserBadge userId={USER_ID} name="Ada Lovelace" />);
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('falls back to client initials (no broken img) when there is no userId', () => {
    render(<UserBadge userId={null} name="Ada Lovelace" />);
    // With no userId PublisherAvatar skips the <img> entirely and renders initials -
    // so there is never an <img src> that could be a raw UUID.
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('AL')).toBeInTheDocument();
  });
});
