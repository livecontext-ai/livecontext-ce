// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const {
  getWorkflows, getInterfaces, getAgents, getDataSources,
  getMyPublications, getExplorerEntries,
  assignMutate, removeMutate, updateMutate,
} = vi.hoisted(() => ({
  getWorkflows: vi.fn(), getInterfaces: vi.fn(), getAgents: vi.fn(), getDataSources: vi.fn(),
  getMyPublications: vi.fn(), getExplorerEntries: vi.fn(),
  assignMutate: vi.fn(), removeMutate: vi.fn(), updateMutate: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: { getWorkflows, getInterfaces, getAgents, getDataSources },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getMyPublications },
}));
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjectMutations: () => ({
    createProject: { mutateAsync: vi.fn(), isPending: false },
    updateProject: { mutateAsync: updateMutate, isPending: false },
    deleteProject: { mutateAsync: vi.fn(), isPending: false },
  }),
  useProject: () => ({ project: { id: 'p1', name: 'My Project' } }),
  useProjectPermissions: () => ({ canAssignResources: true, canEdit: true, canDelete: false }),
  useResourceAssignment: () => ({
    assignResource: { mutateAsync: assignMutate, isPending: false },
    removeResource: { mutateAsync: removeMutate, isPending: false },
  }),
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

import { ProjectMultiStepModal } from '../ProjectMultiStepModal';

const project = { id: 'p1', name: 'My Project', color: '#3b82f6', icon: 'briefcase' } as any;

function renderModal(extra?: Partial<React.ComponentProps<typeof ProjectMultiStepModal>>) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ProjectMultiStepModal
        project={project}
        initialStep={2}
        onClose={() => {}}
        onSuccess={() => {}}
        {...extra}
      />
    </QueryClientProvider>,
  );
}

describe('ProjectMultiStepModal - files as an assignable resource', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getWorkflows.mockResolvedValue([]);
    getInterfaces.mockResolvedValue([]);
    getAgents.mockResolvedValue([]);
    getDataSources.mockResolvedValue([]);
    getMyPublications.mockResolvedValue({ publications: [] });
    getExplorerEntries.mockResolvedValue({ content: [{ id: 'f1', fileName: 'report.pdf' }] });
    updateMutate.mockResolvedValue(project);
    assignMutate.mockResolvedValue(undefined);
  });
  afterEach(() => cleanup());

  it('renders a Files resource section and queries S3 files only (S3_FILES_FILTER)', async () => {
    renderModal();
    expect(await screen.findByText('project.tabs.files')).toBeInTheDocument();
    await waitFor(() =>
      expect(getExplorerEntries).toHaveBeenCalledWith({ size: 100, filesOnly: true, s3Only: true }),
    );
  });

  it('lists an unassigned file (name from fileName fallback) when the section is expanded', async () => {
    renderModal();
    fireEvent.click(await screen.findByText('project.tabs.files'));
    expect(await screen.findByText('report.pdf')).toBeInTheDocument();
  });

  it('assigns the selected file to the project on save (resourceType="file")', async () => {
    renderModal();
    fireEvent.click(await screen.findByText('project.tabs.files'));
    const fileRow = await screen.findByText('report.pdf');
    fireEvent.click(fileRow.closest('div')!);

    fireEvent.click(screen.getByText('project.save'));

    await waitFor(() =>
      expect(assignMutate).toHaveBeenCalledWith({ resourceType: 'file', resourceId: 'f1' }),
    );
  });
});
