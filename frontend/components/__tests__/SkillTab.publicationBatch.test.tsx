// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * SkillTab used to resolve each skill's shared/in-review/rejected badge with ONE
 * getResourcePublicationStatus('SKILL', id) call PER skill (an N+1 that saturated the connection pool
 * on large skill sets). It now uses the SAME single batched /publications/my sweep
 * (getAllMyPublications + buildPublicationStatusSets(pubs, 'SKILL')) that AgentTable / InterfaceTable
 * already use. This pins that wiring: ONE sweep call, never the per-skill fan-out.
 */

const mocks = vi.hoisted(() => ({
  getAllMyPublications: vi.fn(),
  getResourcePublicationStatus: vi.fn(),
  allSkills: [] as Array<{ id: string; name: string }>,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { getMySkillOverrides: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAllMyPublications: mocks.getAllMyPublications,
    getResourcePublicationStatus: mocks.getResourcePublicationStatus,
    unpublishResource: vi.fn(),
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
vi.mock('@/hooks/useSkillExplorer', () => ({
  useSkillExplorer: () => ({
    allFolders: [],
    allSkills: mocks.allSkills,
    loading: false,
    searchQuery: '',
    setSearchQuery: vi.fn(),
    refresh: vi.fn(),
    createFolder: vi.fn(),
    renameFolder: vi.fn(),
    deleteFolder: vi.fn(),
    moveFolder: vi.fn(),
    moveSkill: vi.fn(),
  }),
}));
vi.mock('@/components/skills/SkillFolderTree', () => ({ SkillFolderTree: () => null }));
vi.mock('@/components/skills/CreateFolderDialog', () => ({ CreateFolderDialog: () => null }));
vi.mock('@/components/chat/CreateSkillModal', () => ({ CreateSkillModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

import { SkillTab } from '../SkillTab';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SkillTab - publication status from the batched /publications/my sweep (no per-skill N+1)', () => {
  it('resolves the badge via getAllMyPublications ONCE, never getResourcePublicationStatus per skill', async () => {
    mocks.allSkills = [{ id: 's1', name: 'Shared Skill' }, { id: 's2', name: 'Private Skill' }];
    // s1 shared (ACTIVE), s2 absent → private. Keyed by resourceId for SKILL.
    mocks.getAllMyPublications.mockResolvedValue([
      { publicationType: 'SKILL', status: 'ACTIVE', resourceId: 's1' },
    ]);

    render(<SkillTab />);

    // The badge is resolved from a single sweep...
    await waitFor(() => expect(mocks.getAllMyPublications).toHaveBeenCalledTimes(1));
    // ...and the old per-skill is-resource-published N+1 is gone.
    expect(mocks.getResourcePublicationStatus).not.toHaveBeenCalled();
  });

  it('does not sweep when there are no skills', async () => {
    mocks.allSkills = [];
    mocks.getAllMyPublications.mockResolvedValue([]);

    render(<SkillTab />);
    // Give the mount effects a tick; the sweep is guarded behind allSkills.length > 0.
    await waitFor(() => expect(mocks.getResourcePublicationStatus).not.toHaveBeenCalled());
    expect(mocks.getAllMyPublications).not.toHaveBeenCalled();
  });
});
