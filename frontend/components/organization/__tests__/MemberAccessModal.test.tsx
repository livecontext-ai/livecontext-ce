// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// next-intl: return `${namespace}.${key}` so the files section (tFiles('title') => 'files.title')
// is distinguishable from the settings labels.
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const {
  getWorkflows, getWorkflowsPage, getInterfaces, getAgents, getDataSources, getSkills,
  getExplorerEntries, getProjects, getMemberRestrictions, setRestrictions,
  getMyPublicationsPage, getAcquiredApplicationsPage,
} = vi.hoisted(() => ({
  getWorkflows: vi.fn(), getWorkflowsPage: vi.fn(), getInterfaces: vi.fn(), getAgents: vi.fn(), getDataSources: vi.fn(),
  getSkills: vi.fn(),
  getExplorerEntries: vi.fn(), getProjects: vi.fn(),
  getMemberRestrictions: vi.fn(), setRestrictions: vi.fn(),
  getMyPublicationsPage: vi.fn(), getAcquiredApplicationsPage: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { getWorkflows, getWorkflowsPage, getInterfaces, getAgents, getDataSources, getSkills },
}));
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProjects },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getMyPublicationsPage, getAcquiredApplicationsPage },
}));
vi.mock('@/lib/api/orchestrator/org-access.service', () => ({
  orgAccessService: { getMemberRestrictions, setRestrictions },
}));

import MemberAccessModal from '../MemberAccessModal';

const member = { userId: 42, displayName: 'Dana' } as any;

function mockEmptyExceptFiles(files: { id: string; fileName?: string; contentType?: string }[]) {
  getWorkflows.mockResolvedValue([]);
  getWorkflowsPage.mockResolvedValue({ workflows: [], totalCount: 0 });
  getInterfaces.mockResolvedValue([]);
  getAgents.mockResolvedValue([]);
  getDataSources.mockResolvedValue([]);
  getProjects.mockResolvedValue([]);
  getExplorerEntries.mockResolvedValue({ content: files });
  setRestrictions.mockResolvedValue(undefined);
}

describe('MemberAccessModal - files as a restrictable resource', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  it('lazily defers the file sweep until the Files section is expanded, then requests filesOnly', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptFiles([{ id: 'file-1', fileName: 'report.pdf' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    // The Files section header renders, but its (potentially large) sweep must NOT run on open -
    // only "workflow" is expanded by default. This is the optimization: no eager all-types fetch.
    const filesHeader = await screen.findByText('files.title');
    expect(getExplorerEntries).not.toHaveBeenCalled();

    // Expanding the Files section triggers the lazy, PAGINATED fetch: first page only (size 30),
    // s3Only so observability TEXT blobs never show, filesOnly for real files.
    fireEvent.click(filesHeader);
    await screen.findByText('report.pdf');
    expect(getExplorerEntries).toHaveBeenCalledWith(
      expect.objectContaining({ filesOnly: true, s3Only: true, page: 0, size: 30 }),
    );
  });

  it('lists the org files when the Files section is expanded and toggling a file off then saving restricts it', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptFiles([{ id: 'file-1', fileName: 'report.pdf' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const filesHeader = await screen.findByText('files.title');
    // Expand the Files section (only "workflow" is expanded by default).
    fireEvent.click(filesHeader);

    const fileRow = await screen.findByText('report.pdf');
    // Fully deny the file via the tri-state control ("No access").
    fireEvent.click(within(fileRow.closest('div')!).getByText('files.accessNone'));

    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'file', ['file-1'], { 'file-1': 'DENY' }),
    );
  });

  it('saves a READ permission when a file is set to read-only', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptFiles([{ id: 'file-1', fileName: 'report.pdf' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const filesHeader = await screen.findByText('files.title');
    fireEvent.click(filesHeader);

    const fileRow = await screen.findByText('report.pdf');
    fireEvent.click(within(fileRow.closest('div')!).getByText('files.accessRead'));

    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'file', ['file-1'], { 'file-1': 'READ' }),
    );
  });

  it('loads an existing READ restriction and round-trips it as read-only', async () => {
    getMemberRestrictions.mockResolvedValue([{ resourceType: 'file', resourceId: 'file-1', permission: 'READ' }]);
    mockEmptyExceptFiles([{ id: 'file-1', fileName: 'report.pdf' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const filesHeader = await screen.findByText('files.title');
    fireEvent.click(filesHeader);
    await screen.findByText('report.pdf');

    // Save without changes - the loaded READ level must be preserved.
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'file', ['file-1'], { 'file-1': 'READ' }),
    );
  });

  it('falls back to contentType then id when a file has no name', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptFiles([{ id: 'file-2', contentType: 'image/png' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const filesHeader = await screen.findByText('files.title');
    fireEvent.click(filesHeader);

    expect(await screen.findByText('image/png')).toBeInTheDocument();
  });
});

// The non-file resource types (workflow/interface/agent/datasource/project) now expose the
// SAME tri-state (Full access / Read-only / No access) as files, not a binary on/off switch.
// "workflow" is the section expanded by default, so its items load on open.
describe('MemberAccessModal - non-file tri-state (workflow)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  function mockEmptyExceptWorkflows(workflows: { id: string; name: string }[]) {
    getWorkflows.mockResolvedValue([]);
    getWorkflowsPage.mockResolvedValue({ workflows, totalCount: workflows.length });
    getInterfaces.mockResolvedValue([]);
    getAgents.mockResolvedValue([]);
    getDataSources.mockResolvedValue([]);
    getProjects.mockResolvedValue([]);
    getExplorerEntries.mockResolvedValue({ content: [] });
    setRestrictions.mockResolvedValue(undefined);
  }

  it('saves a READ permission when a workflow is set to read-only', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptWorkflows([{ id: 'wf-1', name: 'My Flow' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const wfRow = await screen.findByText('My Flow');
    // Read-only via the shared tri-state control (org-scoped label).
    fireEvent.click(within(wfRow.closest('div')!).getByText('settings.organization.accessRead'));

    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'workflow', ['wf-1'], { 'wf-1': 'READ' }),
    );
  });

  it('saves a DENY permission when a workflow is set to no-access', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptWorkflows([{ id: 'wf-1', name: 'My Flow' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const wfRow = await screen.findByText('My Flow');
    fireEvent.click(within(wfRow.closest('div')!).getByText('settings.organization.accessNone'));

    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'workflow', ['wf-1'], { 'wf-1': 'DENY' }),
    );
  });

  it('clears a restriction when a workflow is set back to full access', async () => {
    // Pre-load an existing DENY restriction; setting it back to "Full access" must remove it.
    getMemberRestrictions.mockResolvedValue([{ resourceType: 'workflow', resourceId: 'wf-1', permission: 'DENY' }]);
    mockEmptyExceptWorkflows([{ id: 'wf-1', name: 'My Flow' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    const wfRow = await screen.findByText('My Flow');
    fireEvent.click(within(wfRow.closest('div')!).getByText('settings.organization.accessFull'));

    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'workflow', [], {}),
    );
  });

  it('round-trips an existing READ restriction as read-only on save without changes', async () => {
    getMemberRestrictions.mockResolvedValue([{ resourceType: 'workflow', resourceId: 'wf-1', permission: 'READ' }]);
    mockEmptyExceptWorkflows([{ id: 'wf-1', name: 'My Flow' }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    await screen.findByText('My Flow');
    // Save unchanged - the loaded READ level must be preserved for the non-file type.
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', 'workflow', ['wf-1'], { 'wf-1': 'READ' }),
    );
  });
});

// The tri-state must work for EVERY non-file resource type, not only workflow. getLevel/setLevel
// are generic over `type`, but pin each type's section header + READ/DENY persistence so a
// type-keying regression (a missed RESOURCE_TYPES entry, a wrong section label, or a wrong
// setRestrictions type arg) is caught per type. workflow is covered above (expanded by default);
// these 4 start collapsed and are expanded via their section header.
describe('MemberAccessModal - non-file tri-state covers interface/agent/datasource/project', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  const CASES = [
    { type: 'interface',  header: 'Interfaces', mock: (r: any) => getInterfaces.mockResolvedValue(r) },
    { type: 'agent',      header: 'Agents',     mock: (r: any) => getAgents.mockResolvedValue(r) },
    { type: 'datasource', header: 'Tables',     mock: (r: any) => getDataSources.mockResolvedValue(r) },
    { type: 'project',    header: 'Projects',   mock: (r: any) => getProjects.mockResolvedValue(r) },
    { type: 'skill',      header: 'Skills',     mock: (r: any) => getSkills.mockResolvedValue(r) },
  ] as const;

  function mockAllEmpty() {
    getWorkflows.mockResolvedValue([]);
    getWorkflowsPage.mockResolvedValue({ workflows: [], totalCount: 0 });
    getInterfaces.mockResolvedValue([]);
    getAgents.mockResolvedValue([]);
    getDataSources.mockResolvedValue([]);
    getProjects.mockResolvedValue([]);
    getSkills.mockResolvedValue([]);
    getExplorerEntries.mockResolvedValue({ content: [] });
    setRestrictions.mockResolvedValue(undefined);
  }

  it.each(CASES)('saves a READ permission when a $type is set to read-only', async ({ type, header, mock }) => {
    getMemberRestrictions.mockResolvedValue([]);
    mockAllEmpty();
    mock([{ id: `${type}-1`, name: `My ${type}` }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);
    // Non-workflow sections start collapsed - expand via the section header.
    fireEvent.click(await screen.findByText(header));

    const row = await screen.findByText(`My ${type}`);
    fireEvent.click(within(row.closest('div')!).getByText('settings.organization.accessRead'));
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', type, [`${type}-1`], { [`${type}-1`]: 'READ' }),
    );
  });

  it.each(CASES)('saves a DENY permission when a $type is set to no-access', async ({ type, header, mock }) => {
    getMemberRestrictions.mockResolvedValue([]);
    mockAllEmpty();
    mock([{ id: `${type}-1`, name: `My ${type}` }]);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);
    fireEvent.click(await screen.findByText(header));

    const row = await screen.findByText(`My ${type}`);
    fireEvent.click(within(row.closest('div')!).getByText('settings.organization.accessNone'));
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith('org-1', '42', type, [`${type}-1`], { [`${type}-1`]: 'DENY' }),
    );
  });
});

// Applications are a NEW restrictable resource type, keyed by PUBLICATION id under the "application"
// deny-list (the same id /app/applications + publication-service + ProjectService use). The modal
// sweeps own published-as-app publications (getMyPublicationsPage applicationOnly) + acquired apps
// (getAcquiredApplicationsPage), deduped by publication id. These pin the new section end-to-end.
describe('MemberAccessModal - applications as a restrictable resource', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  function mockEmptyExceptApplications(
    own: { id: string; title: string }[],
    acquired: { sourcePublicationId: string; name: string }[],
  ) {
    getWorkflows.mockResolvedValue([]);
    getWorkflowsPage.mockResolvedValue({ workflows: [], totalCount: 0 });
    getInterfaces.mockResolvedValue([]);
    getAgents.mockResolvedValue([]);
    getDataSources.mockResolvedValue([]);
    getProjects.mockResolvedValue([]);
    getExplorerEntries.mockResolvedValue({ content: [] });
    getMyPublicationsPage.mockResolvedValue({ items: own, totalCount: own.length });
    getAcquiredApplicationsPage.mockResolvedValue({ items: acquired, totalCount: acquired.length });
    setRestrictions.mockResolvedValue(undefined);
  }

  it('lists own + acquired applications and DENY persists under the "application" type (publication id)', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptApplications(
      [{ id: 'pub-own', title: 'Own App' }],
      [{ sourcePublicationId: 'pub-acquired', name: 'Acquired App' }],
    );

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);

    // The Applications section starts collapsed - expand via its header.
    fireEvent.click(await screen.findByText('Applications'));

    // Both the own-published and the acquired application show up.
    await screen.findByText('Own App');
    const acquiredRow = await screen.findByText('Acquired App');

    // Fully deny the acquired application.
    fireEvent.click(within(acquiredRow.closest('div')!).getByText('settings.organization.accessNone'));
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith(
        'org-1', '42', 'application', ['pub-acquired'], { 'pub-acquired': 'DENY' }),
    );
    // The sweep used the publication-id streams (application-only for own published pubs).
    expect(getMyPublicationsPage).toHaveBeenCalledWith(expect.objectContaining({ applicationOnly: true }));
    expect(getAcquiredApplicationsPage).toHaveBeenCalled();
  });

  it('saves a READ permission when an application is set to read-only', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    mockEmptyExceptApplications([{ id: 'pub-own', title: 'Own App' }], []);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);
    fireEvent.click(await screen.findByText('Applications'));

    const row = await screen.findByText('Own App');
    fireEvent.click(within(row.closest('div')!).getByText('settings.organization.accessRead'));
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith(
        'org-1', '42', 'application', ['pub-own'], { 'pub-own': 'READ' }),
    );
  });

  it('loads an existing "application" restriction and round-trips it as read-only', async () => {
    getMemberRestrictions.mockResolvedValue([
      { resourceType: 'application', resourceId: 'pub-own', permission: 'READ' },
    ]);
    mockEmptyExceptApplications([{ id: 'pub-own', title: 'Own App' }], []);

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);
    fireEvent.click(await screen.findByText('Applications'));
    await screen.findByText('Own App');

    // Save unchanged - the loaded READ level must be preserved (proves "application" is bucketed
    // into the restricted/readOnly maps on load, not dropped by a missed RESOURCE_TYPES entry).
    fireEvent.click(screen.getByText('settings.organization.save'));

    await waitFor(() =>
      expect(setRestrictions).toHaveBeenCalledWith(
        'org-1', '42', 'application', ['pub-own'], { 'pub-own': 'READ' }),
    );
  });

  it('dedupes an acquired clone of an own-published application (own wins)', async () => {
    getMemberRestrictions.mockResolvedValue([]);
    // Same publication id as both own-published and acquired - must render once.
    mockEmptyExceptApplications(
      [{ id: 'pub-shared', title: 'Shared App' }],
      [{ sourcePublicationId: 'pub-shared', name: 'Shared App (acquired clone)' }],
    );

    render(<MemberAccessModal orgId="org-1" member={member} onClose={() => {}} />);
    fireEvent.click(await screen.findByText('Applications'));

    await screen.findByText('Shared App');
    expect(screen.queryByText('Shared App (acquired clone)')).toBeNull();
  });
});
