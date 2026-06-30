// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The agent file allow-list picker must list the SAME set as the Files page
// (app/file): real object-storage files only. CreateAgentModal therefore fetches
// with S3_FILES_FILTER (filesOnly + s3Only). This test pins that the s3Only flag
// reaches the server - it would FAIL on the pre-change `{ size: 100, filesOnly: true }`.
const { getExplorerEntries } = vi.hoisted(() => ({ getExplorerEntries: vi.fn() }));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));
vi.mock('next/image', () => ({ default: () => null }));

// storage-api: spy the file fetch + export the real-shaped single-source-of-truth const.
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

// Service clients - every query's fn just needs to exist; react-query (retry:false)
// captures any rejection into an error state without breaking the mount.
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getSkills: vi.fn().mockResolvedValue([]),
    getSkillFolders: vi.fn().mockResolvedValue([]),
    getWorkflows: vi.fn().mockResolvedValue([]),
    // Workflows now load via useInfiniteQuery → getWorkflowsPage (one empty page).
    getWorkflowsPage: vi.fn().mockResolvedValue({ workflows: [], count: 0, totalCount: 0, page: 0, size: 100 }),
    getInterfaces: vi.fn().mockResolvedValue([]),
    getAgents: vi.fn().mockResolvedValue([]),
    getDataSources: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: vi.fn().mockResolvedValue({}), post: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn() },
  getFileUrlById: () => 'url',
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getMyPublications: vi.fn().mockResolvedValue({ publications: [] }) },
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getSubAgentEdges: vi.fn().mockResolvedValue([]) },
}));
vi.mock('@/lib/api/orchestrator/schedule-settings.service', () => ({
  scheduleSettingsService: { getConfig: vi.fn().mockResolvedValue(null) },
}));

// Hooks + heavy child components → inert stubs so the modal mounts without their internals.
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ providers: [], defaultModel: null, defaultProvider: null, isLoading: false }),
}));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  useMcpApis: () => ({ apis: [], isLoading: false }),
  fetchApiTools: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: () => {},
}));
vi.mock('@/components/ai/ModelPicker', () => ({ ModelPicker: () => null }));
vi.mock('@/components/skills/SkillFolderTree', () => ({ SkillFolderTree: () => null }));
vi.mock('@/components/agents', () => ({
  AvatarDisplay: () => null,
  AvatarPicker: () => null,
  getPresetDefaultName: () => 'Agent',
  isPresetDefaultName: () => false,
}));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

import { CreateAgentModal } from '../CreateAgentModal';

function renderModal() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} />
    </QueryClientProvider>,
  );
}

describe('CreateAgentModal - file allow-list lists S3 files only', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
  });
  afterEach(() => cleanup());

  it('fetches the agent file picker with S3_FILES_FILTER (page 0, size 100, filesOnly + s3Only)', async () => {
    renderModal();
    // Files now load via useInfiniteQuery → the first page request carries page: 0.
    await waitFor(() =>
      expect(getExplorerEntries).toHaveBeenCalledWith({ page: 0, size: 100, filesOnly: true, s3Only: true }),
    );
  });
});
