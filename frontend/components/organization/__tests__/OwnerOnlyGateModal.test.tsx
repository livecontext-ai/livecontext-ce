// @vitest-environment jsdom
//
// CE-only owner-only gate (informational). A non-owner MEMBER/VIEWER who clicks invite /
// create-workspace gets this modal (the install belongs to the admin) instead of the plan upsell.
// useTranslations echoes the key verbatim, so assertions check WHICH key each action resolves to.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { OwnerOnlyGateModal } from '../OwnerOnlyGateModal';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Render the dialog body inline when open - the test targets action→copy mapping, not portal mechanics.
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) => (open ? <div>{children}</div> : null),
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

describe('OwnerOnlyGateModal', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('invite action shows the invite copy (and not the workspace copy)', () => {
    render(<OwnerOnlyGateModal open onClose={vi.fn()} action="invite" />);

    expect(screen.getByText('inviteTitle')).toBeInTheDocument();
    expect(screen.getByText('inviteBody')).toBeInTheDocument();
    expect(screen.queryByText('workspaceTitle')).not.toBeInTheDocument();
    expect(screen.queryByText('workspaceBody')).not.toBeInTheDocument();
  });

  it('workspace action shows the workspace copy (and not the invite copy)', () => {
    render(<OwnerOnlyGateModal open onClose={vi.fn()} action="workspace" />);

    expect(screen.getByText('workspaceTitle')).toBeInTheDocument();
    expect(screen.getByText('workspaceBody')).toBeInTheDocument();
    expect(screen.queryByText('inviteTitle')).not.toBeInTheDocument();
    expect(screen.queryByText('inviteBody')).not.toBeInTheDocument();
  });

  it('is purely informational: the single dismiss button only closes the modal', () => {
    const onClose = vi.fn();
    render(<OwnerOnlyGateModal open onClose={onClose} action="invite" />);

    const dismiss = screen.getByRole('button', { name: 'dismiss' });
    expect(dismiss).toBeInTheDocument();
    // Only one button - no action/CTA/upgrade button.
    expect(screen.getAllByRole('button')).toHaveLength(1);

    fireEvent.click(dismiss);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('uses the building icon for workspace and the people icon for invite', () => {
    const ws = render(<OwnerOnlyGateModal open onClose={vi.fn()} action="workspace" />);
    expect(ws.container.querySelector('svg[class*="building"]')).toBeTruthy();
    expect(ws.container.querySelector('svg[class*="lucide-users"]')).toBeNull();
    cleanup();

    const invite = render(<OwnerOnlyGateModal open onClose={vi.fn()} action="invite" />);
    expect(invite.container.querySelector('svg[class*="lucide-users"]')).toBeTruthy();
    expect(invite.container.querySelector('svg[class*="building"]')).toBeNull();
  });

  it('renders nothing when closed', () => {
    const { container } = render(<OwnerOnlyGateModal open={false} onClose={vi.fn()} action="invite" />);
    expect(container).toBeEmptyDOMElement();
  });
});
