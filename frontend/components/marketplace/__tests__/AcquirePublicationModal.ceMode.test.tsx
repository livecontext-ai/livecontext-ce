// @vitest-environment jsdom
/**
 * CE-cloud acquire routing (2026-06-18). On a cloud-linked CE the publication
 * lives on the cloud, so EVERY type - agent, resource, workflow - must install
 * through the unified remote acquire (`acquireRemotePublication` →
 * /publications/remote/{id}/acquire), which charges the linked cloud account
 * for paid publications and returns the matching id. Off CE-cloud, each type
 * keeps its own local acquire endpoint (regression guard).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
const avatarProps = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({
  PublisherAvatar: (props: Record<string, unknown>) => { avatarProps.calls.push(props); return null; },
}));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

import AcquirePublicationModal from '../AcquirePublicationModal';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Cloud Thing',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'WORKFLOW',
    ...overrides,
  } as WorkflowPublication;
}

function clickConfirm() {
  // Free publication → confirm button label is the key-echoed 'addToApplications'.
  fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  avatarProps.calls = [];
  svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1', agentId: 'a1', resourceId: 'r1' });
  svc.acquireAgentPublication.mockResolvedValue({ agentId: 'a1' });
  svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'TABLE' });
  svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
});

afterEach(() => cleanup());

describe('AcquirePublicationModal - CE-cloud acquire routing', () => {
  it('ceMode + AGENT → unified remote acquire, NOT the local agent endpoint', async () => {
    render(<AcquirePublicationModal isOpen ceMode publication={pub({ publicationType: 'AGENT' })} onClose={() => {}} />);
    clickConfirm();
    await waitFor(() => expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquireAgentPublication).not.toHaveBeenCalled();
  });

  it('ceMode + TABLE (resource) → unified remote acquire, NOT the local resource endpoint', async () => {
    render(<AcquirePublicationModal isOpen ceMode publication={pub({ publicationType: 'TABLE' })} onClose={() => {}} />);
    clickConfirm();
    await waitFor(() => expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
  });

  it('ceMode + WORKFLOW → unified remote acquire', async () => {
    render(<AcquirePublicationModal isOpen ceMode publication={pub({ publicationType: 'WORKFLOW' })} onClose={() => {}} />);
    clickConfirm();
    await waitFor(() => expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquirePublication).not.toHaveBeenCalled();
  });

  it('NON-ceMode + AGENT → local agent acquire (unchanged off CE-cloud)', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'AGENT' })} onClose={() => {}} />);
    clickConfirm();
    await waitFor(() => expect(svc.acquireAgentPublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
  });

  it('NON-ceMode + TABLE → local resource acquire (unchanged off CE-cloud)', async () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publicationType: 'TABLE' })} onClose={() => {}} />);
    clickConfirm();
    await waitFor(() => expect(svc.acquireResourcePublication).toHaveBeenCalledWith('pub-1'));
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
  });
});

describe('AcquirePublicationModal - publisher avatar remote routing (CE-cloud)', () => {
  it('ceMode → the publisher avatar is told to load via the cloud proxy (remote=true)', () => {
    render(<AcquirePublicationModal isOpen ceMode publication={pub({ publisherId: '99' })} onClose={() => {}} />);
    // The cloud publisher id is absent locally, so the modal avatar must go remote.
    expect(avatarProps.calls.some((p) => p.remote === true)).toBe(true);
  });

  it('non-ceMode → the publisher avatar stays local (falsy remote)', () => {
    render(<AcquirePublicationModal isOpen publication={pub({ publisherId: '99' })} onClose={() => {}} />);
    expect(avatarProps.calls.length).toBeGreaterThan(0);
    expect(avatarProps.calls.every((p) => !p.remote)).toBe(true);
  });
});
