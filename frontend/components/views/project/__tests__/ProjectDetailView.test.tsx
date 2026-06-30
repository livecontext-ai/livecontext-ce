// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const { useProject, useProjectResources, useProjectPermissions, openTab, removeTab } = vi.hoisted(() => ({
  useProject: vi.fn(),
  useProjectResources: vi.fn(),
  useProjectPermissions: vi.fn(),
  openTab: vi.fn(),
  removeTab: vi.fn(),
}));

vi.mock('@/hooks/useProjects', () => ({
  useProject,
  useProjectResources,
  useProjectPermissions,
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanel: () => ({ openTab, removeTab }),
}));

// Heavy children - only the inline grids/tabs of ProjectDetailView are under test.
vi.mock('@/components/views/AuthenticatedView', () => ({
  AuthenticatedView: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  ProjectMultiStepModal: () => null,
  getProjectIcon: () => () => null,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div>loading</div> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({ WorkflowBuilderPanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
vi.mock('@/components/app/InterfacePanelContent', () => ({ InterfacePanelContent: () => null }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/ApplicationSidePanel', () => ({ ApplicationPanelContent: () => null }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div>file-detail</div> }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { downloadAndSave: vi.fn() } }));

// The three tab cards are reused verbatim from /app/tables, /app/files, /app/applications
// (tested in their own suites). Here we stub them to assert ProjectDetailView wires the
// right data/props into each - the point of this view.
vi.mock('@/components/data-table/DataSourceCard', () => ({
  DataSourceCard: ({ ds, rowCount, sampleRows, onClick }: any) => (
    <button data-testid={`ds-${ds.id}`} onClick={onClick}>
      {ds.name} rows={rowCount} sample={(sampleRows || []).length}
    </button>
  ),
}));
vi.mock('@/components/files/FileCard', () => ({
  FileCard: ({ entry, onOpen }: any) => (
    <button data-testid={`file-${entry.id}`} onClick={() => onOpen(entry)}>
      {entry.fileName} {entry.formattedSize}
    </button>
  ),
}));
vi.mock('@/components/applications/ApplicationCard', () => ({
  ApplicationCard: ({ publication, source, onCardClick }: any) => (
    <button
      data-testid={`app-${publication.id}`}
      data-source={source}
      data-visibility={String(publication.visibility)}
      data-status={String(publication.status)}
      onClick={onCardClick}
    >
      {publication.title} pub={String(publication.publisherId)}
    </button>
  ),
}));

import { ProjectDetailView } from '../ProjectDetailView';

const baseResources = {
  workflows: [], agents: [], interfaces: [], datasources: [], applications: [], files: [],
};

function setup(resources: Record<string, any>) {
  useProject.mockReturnValue({ project: { id: 'p1', name: 'My Project', icon: 'box', color: '#fff' }, loading: false });
  useProjectResources.mockReturnValue({
    resources: { ...baseResources, ...resources },
    loading: false,
    refetch: vi.fn(),
  });
  useProjectPermissions.mockReturnValue({ canEdit: true, canAssignResources: true });
}

function openTabByLabel(label: string) {
  fireEvent.click(screen.getAllByText(label)[0].closest('button')!);
}

describe('ProjectDetailView - Files tab', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  const file = { id: 'f1', fileName: 'report.pdf', mimeType: 'application/pdf', sizeBytes: 2048, createdAt: '2026-05-29T10:00:00Z' };

  it('renders a Files tab with the assigned-file count', () => {
    setup({ files: [file] });
    render(<ProjectDetailView projectId="p1" />);

    const filesTab = screen.getAllByText('project.tabs.files')[0].closest('button')!;
    expect(within(filesTab).getByText('1')).toBeInTheDocument();
  });

  it('renders project files as FileCard tiles (same visualization as /app/files) with a human-readable size', () => {
    setup({ files: [file] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.files');

    const tile = screen.getByTestId('file-f1');
    expect(tile).toHaveTextContent('report.pdf');
    // FileCard footer shows the formatted size (computed from sizeBytes), not the raw mime.
    expect(tile).toHaveTextContent('2.0 KB');
  });

  it('opens a file side-panel tab when a file tile is clicked', () => {
    setup({ files: [file] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.files');
    fireEvent.click(screen.getByTestId('file-f1'));

    expect(openTab).toHaveBeenCalledTimes(1);
    // Canonical shared Files side panel (id 'files-panel'), not a bespoke per-file `file-<id>` tab.
    expect(openTab).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'files-panel', label: 'report.pdf' }),
    );
  });

  it('passes the file mimeType/size/created to the side-panel preview (so it renders inline, not the placeholder)', () => {
    setup({ files: [file] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.files');
    fireEvent.click(screen.getByTestId('file-f1'));

    // The side-panel content IS the <FileDetailView/> element. Dropping mimeType
    // here is what made the project view show "No inline preview" while the Files
    // browser (which passes it) rendered the image - so assert the metadata the
    // preview needs reaches the component.
    const tab = openTab.mock.calls[0][0];
    expect(tab.content.props).toMatchObject({
      entryId: 'f1',
      fileName: 'report.pdf',
      mimeType: 'application/pdf',
      sizeBytes: 2048,
      createdAt: '2026-05-29T10:00:00Z',
    });
  });

  it('shows the empty state when the project has no files', () => {
    setup({ files: [] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.files');
    expect(screen.queryByTestId('file-f1')).not.toBeInTheDocument();
    const filesTab = screen.getAllByText('project.tabs.files')[0].closest('button')!;
    expect(within(filesTab).queryByText('1')).not.toBeInTheDocument();
  });
});

describe('ProjectDetailView - Tables tab', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('renders DataSourceCard with the row count + sample rows from the project preview maps', () => {
    setup({
      datasources: [{ id: 10, name: 'Emails' }],
      datasourceRowCounts: { '10': 5 },
      datasourceSampleRows: { '10': [{ subject: 'hi' }, { subject: 'yo' }] },
    });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.tables');

    const card = screen.getByTestId('ds-10');
    // The preview maps (keyed by id-as-string) reach the card - this is what makes the
    // Tables tab render the same mini-table as /app/tables.
    expect(card).toHaveTextContent('Emails rows=5 sample=2');
  });

  it('opens a datasource side-panel tab when a table card is clicked', () => {
    setup({ datasources: [{ id: 10, name: 'Emails' }] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.tables');
    fireEvent.click(screen.getByTestId('ds-10'));

    expect(openTab).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'datasource-10', label: 'Emails' }),
    );
  });
});

describe('ProjectDetailView - Applications tab', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('renders ApplicationCard carrying publisherId + visibility + status (card parity with /app/applications)', () => {
    setup({ applications: [{ id: 'a1', title: 'My App', publisherId: 'user-42', publisherName: 'LiveContext', visibility: 'PUBLIC', status: 'PENDING_REVIEW' }] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.applications');

    const card = screen.getByTestId('app-a1');
    expect(card).toHaveAttribute('data-source', 'published');
    // publisherId reaches the card → PublisherAvatar hits /users/{id}/avatar instead of initials.
    expect(card).toHaveTextContent('My App pub=user-42');
    // visibility reaches the card → the footer shows the correct public/private icon
    // (without it, VisibilityBadge defaults to the private Lock for every app).
    expect(card).toHaveAttribute('data-visibility', 'PUBLIC');
    expect(card).toHaveAttribute('data-status', 'PENDING_REVIEW');
  });

  it('drops a bare category slug so ApplicationCard does not mis-render category.name', () => {
    setup({ applications: [{ id: 'a2', title: 'Slugged', publisherId: 'u1', category: 'productivity' }] });
    render(<ProjectDetailView projectId="p1" />);

    openTabByLabel('project.tabs.applications');
    // The card still renders (no crash from category being a string vs the object it reads).
    expect(screen.getByTestId('app-a2')).toHaveTextContent('Slugged');
  });
});
