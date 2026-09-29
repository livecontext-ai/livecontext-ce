// @vitest-environment jsdom
/**
 * Share links are a workspace write. A read-only VIEWER used to be offered "Create link";
 * for a conversation that first switched public sharing ON in conversation-service and then
 * failed to create the link, leaving a half-enabled share nobody could manage. Now:
 *  - a VIEWER cannot confirm (disabled, read-only reason), and no service is called;
 *  - if link creation fails after conversation sharing was enabled, it is switched back off.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const gate = vi.hoisted(() => ({ canMutate: true }));
const sharing = vi.hoisted(() => ({ check: vi.fn(), create: vi.fn(), update: vi.fn(), regenerateToken: vi.fn() }));
const convSharing = vi.hoisted(() => ({ enableSharing: vi.fn(), disableSharing: vi.fn() }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => gate.canMutate }));
vi.mock('@/lib/api/sharing.service', () => ({ sharingService: sharing }));
vi.mock('@/lib/api/conversation-sharing.service', () => ({ conversationSharingService: convSharing }));
vi.mock('@/i18n/navigation', () => ({ Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));

import { ShareLinkDialog } from '../ShareLinkDialog';

function renderDialog(resourceType = 'CONVERSATION') {
  render(<ShareLinkDialog open onOpenChange={() => {}} resourceType={resourceType}
    resourceToken="conv-1" resourceName="My chat" />);
}

beforeEach(() => {
  gate.canMutate = true;
  sharing.check.mockReset().mockResolvedValue({ link: null, config: { currentCount: 0, maxPerUser: 5 } });
  sharing.create.mockReset();
  convSharing.enableSharing.mockReset().mockResolvedValue({ shareToken: 'cs_1' });
  convSharing.disableSharing.mockReset().mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('ShareLinkDialog - VIEWER gate', () => {
  it('VIEWER: the confirm button is disabled with the read-only reason; nothing is enabled', async () => {
    gate.canMutate = false;
    renderDialog();

    const confirm = await screen.findByText('confirmButton');
    const button = confirm.closest('button')!;
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'viewerReadOnly');
    fireEvent.click(button);
    expect(convSharing.enableSharing).not.toHaveBeenCalled();
    expect(sharing.create).not.toHaveBeenCalled();
  });

  it('MEMBER: a failed link creation switches conversation sharing back off (no partial enable)', async () => {
    sharing.create.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    renderDialog();

    fireEvent.click(await screen.findByText('confirmButton'));

    await waitFor(() => expect(convSharing.disableSharing).toHaveBeenCalledWith('conv-1'));
    expect(convSharing.enableSharing).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByText('errorLoadingLink')).toBeInTheDocument());
  });

  it('regression: a translator that changes identity every render does not re-run the open check', async () => {
    // The next-intl mock above returns a NEW function per call, like a translator that is not
    // referentially stable. With the translator in checkExistingLink's deps, the open effect
    // re-ran on every render and wiped the error the confirm handler had just set.
    sharing.create.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    renderDialog();

    fireEvent.click(await screen.findByText('confirmButton'));
    await waitFor(() => expect(screen.getByText('errorLoadingLink')).toBeInTheDocument());

    expect(sharing.check).toHaveBeenCalledTimes(1);
  });

  it('MEMBER: a 403 on creation shows the read-only sentence and still rolls back', async () => {
    sharing.create.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    renderDialog();

    fireEvent.click(await screen.findByText('confirmButton'));

    await waitFor(() => expect(screen.getByText('viewerReadOnly')).toBeInTheDocument());
    expect(convSharing.disableSharing).toHaveBeenCalledWith('conv-1');
  });
});
