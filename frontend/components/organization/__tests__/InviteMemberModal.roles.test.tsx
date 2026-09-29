// @vitest-environment jsdom
/**
 * Only the OWNER may invite someone as ADMIN (server: ADMIN_INVITE_REQUIRES_OWNER, the
 * same OWNER-only rule as a role change). The dialog must not offer an option the
 * server refuses: an ADMIN (or any caller that does not say otherwise) sees MEMBER and
 * VIEWER only.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { inviteMember: vi.fn() },
}));
// The Radix select only renders its options once opened through pointer events jsdom
// lacks; a flat stand-in renders every item so the offered roles can be read directly.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <div data-testid={`role-option-${value}`}>{children}</div>
  ),
}));

import InviteMemberModal from '../InviteMemberModal';

afterEach(cleanup);

function offeredRoles(): string[] {
  return screen
    .queryAllByTestId(/^role-option-/)
    .map((el) => el.getAttribute('data-testid')!.replace('role-option-', ''));
}

describe('InviteMemberModal - ADMIN role offered to the OWNER only', () => {
  it('OWNER (canInviteAdmin) is offered MEMBER, ADMIN and VIEWER', () => {
    render(<InviteMemberModal open orgId="org-1" onClose={vi.fn()} onInviteSent={vi.fn()} canInviteAdmin />);

    expect(offeredRoles()).toEqual(['MEMBER', 'ADMIN', 'VIEWER']);
  });

  it('an ADMIN (canInviteAdmin=false) is offered MEMBER and VIEWER only', () => {
    render(<InviteMemberModal open orgId="org-1" onClose={vi.fn()} onInviteSent={vi.fn()} canInviteAdmin={false} />);

    expect(offeredRoles()).toEqual(['MEMBER', 'VIEWER']);
  });

  it('a caller that does not pass the flag never offers ADMIN', () => {
    render(<InviteMemberModal open orgId="org-1" onClose={vi.fn()} onInviteSent={vi.fn()} />);

    expect(offeredRoles()).not.toContain('ADMIN');
  });
});
