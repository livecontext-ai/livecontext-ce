// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { WorkspaceUpgradeModal } from '../WorkspaceUpgradeModal';

// useTranslations returns the key verbatim, so assertions check WHICH key each variant resolves to.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const mockPush = vi.fn();
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
}));

// Render the dialog body inline when open - the test targets the variant→copy mapping, not Radix portal mechanics.
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) => (open ? <div>{children}</div> : null),
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

describe('WorkspaceUpgradeModal', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('teammates variant (default) shows the TEAM-collaboration copy', () => {
    render(<WorkspaceUpgradeModal open onClose={vi.fn()} />);

    expect(screen.getByText('title')).toBeInTheDocument();
    expect(screen.getByText('description')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'upgradeCta' })).toBeInTheDocument();
  });

  // Regression: the "Create workspace" gate must NOT reuse the teammates/TEAM copy - additional
  // workspaces unlock on PRO, so this variant points the user at PRO.
  it('workspace variant shows the PRO workspace copy and not the teammates copy', () => {
    render(<WorkspaceUpgradeModal open onClose={vi.fn()} variant="workspace" />);

    expect(screen.getByText('workspaceTitle')).toBeInTheDocument();
    expect(screen.getByText('workspaceDescription')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'workspaceUpgradeCta' })).toBeInTheDocument();
    // The teammates/TEAM copy must be absent.
    expect(screen.queryByText('title')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'upgradeCta' })).not.toBeInTheDocument();
  });

  it('upgrade CTA routes to the pricing page (both variants land on the same upsell page)', () => {
    render(<WorkspaceUpgradeModal open onClose={vi.fn()} variant="workspace" />);

    fireEvent.click(screen.getByRole('button', { name: 'workspaceUpgradeCta' }));

    expect(mockPush).toHaveBeenCalledWith('/app/settings/pricing');
  });

  // Icon coherence: the workspace variant must use the building glyph (same identity as
  // CreateWorkspaceModal / the sidebar workspace-focus row), the teammates variant the people glyph.
  it('uses the building icon for the workspace variant and the people icon for teammates', () => {
    const ws = render(<WorkspaceUpgradeModal open onClose={vi.fn()} variant="workspace" />);
    expect(ws.container.querySelector('svg[class*="building"]')).toBeTruthy();
    expect(ws.container.querySelector('svg[class*="lucide-users"]')).toBeNull();
    cleanup();

    const team = render(<WorkspaceUpgradeModal open onClose={vi.fn()} variant="teammates" />);
    expect(team.container.querySelector('svg[class*="lucide-users"]')).toBeTruthy();
    expect(team.container.querySelector('svg[class*="building"]')).toBeNull();
  });
});
