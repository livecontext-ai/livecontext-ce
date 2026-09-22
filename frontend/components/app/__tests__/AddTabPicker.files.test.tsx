// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => {
  const translators = new Map<string | undefined, (key: string) => string>();
  return {
    useTranslations: (ns?: string) => {
      if (!translators.has(ns)) translators.set(ns, (key: string) => `${ns}.${key}`);
      return translators.get(ns)!;
    },
    useLocale: () => 'en',
  };
});

const {
  openTab, removeTab,
  getMyPublications, getAcquiredApplications,
  getInterfacesPage, getDataSources, getWorkflowsPage, getAgents,
  getConversations, searchConversations, getProjects, getProjectResources, getExplorerEntries, openWorkflowLogs,
  openAgendaPanel,
} = vi.hoisted(() => ({
  openTab: vi.fn(), removeTab: vi.fn(),
  getMyPublications: vi.fn(), getAcquiredApplications: vi.fn(),
  getInterfacesPage: vi.fn(), getDataSources: vi.fn(), getWorkflowsPage: vi.fn(), getAgents: vi.fn(),
  getConversations: vi.fn(), searchConversations: vi.fn(), getProjects: vi.fn(), getProjectResources: vi.fn(), getExplorerEntries: vi.fn(),
  openWorkflowLogs: vi.fn(),
  openAgendaPanel: vi.fn(),
}));

vi.mock('@/contexts/SidePanelContext', () => {
  const sidePanel = { openTab, removeTab };
  return { useSidePanelSafe: () => sidePanel };
});
vi.mock('@/lib/api', () => ({
  orchestratorApi: { getDataSources, getWorkflowsPage, getAgents },
}));
vi.mock('@/lib/api/storage-api', () => ({
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
  storageApi: { getExplorerEntries },
}));
vi.mock('@/components/workflow/useWorkflowLogsSidePanel', () => ({
  useWorkflowLogsSidePanel: () => ({ openWorkflowLogs, canOpenWorkflowLogs: true }),
}));
vi.mock('@/components/workflow/run-panel/RunHistoryList', () => ({
  RunHistoryList: ({ onSelectRun }: { onSelectRun: (run: { id: string; runId: string }) => void }) => (
    <button type="button" onClick={() => onSelectRun({ id: 'internal-1', runId: 'run-1' })}>run-1</button>
  ),
}));
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { getInterfacesPage },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getMyPublications, getAcquiredApplications },
}));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { getConversations, searchConversations } }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (value: string) => value }));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProjects, getProjectResources },
}));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({ getProjectIcon: () => () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/app/ApplicationSidePanel', () => ({ ApplicationPanelContent: () => null }));
vi.mock('@/components/app/InterfacePanelContent', () => ({ InterfacePanelContent: () => null }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({ WorkflowBuilderPanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
vi.mock('@/components/app/ConversationPanelContent', () => ({ ConversationPanelContent: () => null }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div>file-detail</div> }));
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({ StorageExplorerTab: () => null }));
vi.mock('@/lib/sidePanel/openAgendaPanel', () => ({ openAgendaPanel }));

import { AddTabPicker } from '../AddTabPicker';

describe('AddTabPicker - files in the project drill-down', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMyPublications.mockResolvedValue({ publications: [] });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getInterfacesPage.mockResolvedValue({ items: [], totalCount: 0 });
    getDataSources.mockResolvedValue([]);
    getWorkflowsPage.mockResolvedValue({ workflows: [], totalCount: 0 });
    getAgents.mockResolvedValue([]);
    getConversations.mockResolvedValue({ content: [], totalElements: 0 });
    searchConversations.mockResolvedValue({ content: [], totalElements: 0 });
    getProjects.mockResolvedValue([{ id: 'pr1', name: 'Alpha', icon: 'box', color: '#fff' }]);
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 0 });
    getProjectResources.mockResolvedValue({
      agents: [], workflows: [], interfaces: [], datasources: [], applications: [],
      files: [{ id: 'f1', fileName: 'report.pdf' }],
    });
  });
  afterEach(() => cleanup());

  async function openProjectDrillDown() {
    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    // Projects category button (loaded after the fetch resolves)
    fireEvent.click(await screen.findByText('sidePanel.projects'));
    // The project list item
    fireEvent.click(await screen.findByText('Alpha'));
  }

  async function revealNextServerPage() {
    for (let click = 0; click < 10; click += 1) {
      fireEvent.click(await screen.findByRole('button', { name: 'sidePanel.showMore' }));
    }
  }

  it('opens the agenda directly as a side-panel tab', async () => {
    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidebar.nav.agenda'));

    expect(openAgendaPanel).toHaveBeenCalledWith(
      expect.objectContaining({ openTab }),
      'sidebar.nav.agenda',
    );
  });

  it('shows a Files category inside the project with its file count', async () => {
    await openProjectDrillDown();
    // The drill-down resource categories include Files (from PROJECT_RESOURCE_TYPES).
    const filesCat = await screen.findByText('sidePanel.files');
    expect(filesCat).toBeInTheDocument();
    expect(getProjectResources).toHaveBeenCalledWith('pr1');
  });

  it('opens a FileDetailView side-panel tab when a project file is selected', async () => {
    await openProjectDrillDown();
    fireEvent.click(await screen.findByText('sidePanel.files'));
    fireEvent.click(await screen.findByText('report.pdf'));

    expect(openTab).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'file-f1', label: 'report.pdf' }),
    );
  });

  it('uses exact paginated totals and displays the global files count', async () => {
    getInterfacesPage.mockImplementation(({ page = 0 }) => Promise.resolve({
      items: page === 0 ? [{ id: 'i1', name: 'Interface 1', interfaceType: 'html' }] : [],
      totalCount: 101,
      page,
      size: 100,
    }));
    getWorkflowsPage.mockImplementation(({ page = 0 }) => Promise.resolve({
      workflows: page === 0 ? [{ id: 'w1', name: 'Workflow 1' }] : [],
      totalCount: 102,
      page,
      size: 100,
    }));
    getConversations.mockImplementation((page = 0) => Promise.resolve({
      content: page === 0 ? [{ id: 'c1', title: 'Conversation 1' }] : [],
      totalElements: 103,
      totalPages: 2,
      page,
      size: 100,
      first: page === 0,
      last: page === 1,
      hasNext: page === 0,
      hasPrevious: page > 0,
      numberOfElements: page === 0 ? 1 : 0,
    }));
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 1_234 });

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));

    expect((await screen.findByText('sidePanel.interfaces')).parentElement).toHaveTextContent('101');
    expect(screen.getByText('sidePanel.workflows').parentElement).toHaveTextContent('102');
    expect(screen.getByText('sidePanel.logs').parentElement).toHaveTextContent('102');
    expect(screen.getByText('sidePanel.conversations').parentElement).toHaveTextContent('103');
    expect(screen.getByText('sidePanel.files').parentElement).toHaveTextContent('1,234');
    expect(getInterfacesPage).not.toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
    expect(getWorkflowsPage).not.toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
    expect(getConversations).not.toHaveBeenCalledWith(1, 100);
    expect(getExplorerEntries).toHaveBeenCalledWith({ page: 0, size: 1, filesOnly: true, s3Only: true });
  });

  it('loads later pages so category search stays aligned with the exact count', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      id: `i${index + 1}`,
      name: `Interface ${index + 1}`,
      interfaceType: 'html',
    }));
    getInterfacesPage.mockImplementation(({ page = 0 }) => Promise.resolve({
      items: page === 0
        ? firstPage
        : [{ id: 'i101', name: 'Interface 101', interfaceType: 'html' }],
      totalCount: 101,
      page,
      size: 100,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.interfaces'));
    await revealNextServerPage();
    await waitFor(() => expect(getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.filterPlaceholder'), {
      target: { value: 'Interface 101' },
    });

    expect(await screen.findByText('Interface 101')).toBeInTheDocument();
  });

  it('aggregates later workflow pages without loading them at picker open', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      id: `w${index + 1}`,
      name: `Workflow ${index + 1}`,
    }));
    getWorkflowsPage.mockImplementation(({ page = 0 }) => Promise.resolve({
      workflows: page === 0 ? firstPage : [{ id: 'w101', name: 'Workflow 101' }],
      totalCount: 101,
      page,
      size: 100,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.workflows'));
    await revealNextServerPage();
    await waitFor(() => expect(getWorkflowsPage).toHaveBeenCalledWith({ page: 1, size: 100 }));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.filterPlaceholder'), {
      target: { value: 'Workflow 101' },
    });

    expect(await screen.findByText('Workflow 101')).toBeInTheDocument();
  });

  it('aggregates later conversation pages without loading them at picker open', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      id: `c${index + 1}`,
      title: `Conversation ${index + 1}`,
    }));
    getConversations.mockImplementation((page = 0) => Promise.resolve({
      content: page === 0 ? firstPage : [{ id: 'c101', title: 'Conversation 101' }],
      totalElements: 101,
      totalPages: 2,
      page,
      size: 100,
      first: page === 0,
      last: page === 1,
      hasNext: page === 0,
      hasPrevious: page > 0,
      numberOfElements: page === 0 ? 100 : 1,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.conversations'));
    await revealNextServerPage();
    await waitFor(() => expect(getConversations).toHaveBeenCalledWith(1, 100));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.filterPlaceholder'), {
      target: { value: 'Conversation 101' },
    });

    expect(await screen.findByText('Conversation 101')).toBeInTheDocument();
  });

  it('searches the whole server-side category instead of only the loaded page', async () => {
    getInterfacesPage.mockImplementation(({ q }: { q?: string }) => Promise.resolve({
      items: q ? [{ id: 'i101', name: 'Remote Needle', interfaceType: 'html' }] : [],
      totalCount: q ? 1 : 101,
      page: 0,
      size: 100,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.interfaces'));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.filterPlaceholder'), {
      target: { value: 'Remote Needle' },
    });

    expect(await screen.findByText('Remote Needle')).toBeInTheDocument();
    expect(getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      q: 'Remote Needle',
      excludeType: 'web_search',
    }));
  });

  it('adds server-side results to the global cross-resource search', async () => {
    getWorkflowsPage.mockImplementation(({ q }: { q?: string }) => Promise.resolve({
      workflows: q ? [{ id: 'w101', name: 'Remote Workflow' }] : [],
      totalCount: q ? 1 : 101,
      page: 0,
      size: 100,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.searchPlaceholder'), {
      target: { value: 'Remote Workflow' },
    });

    expect(await screen.findByText('Remote Workflow')).toBeInTheDocument();
    expect(getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      q: 'Remote Workflow',
    }));
  });

  it('keeps the exact count when a later page fails and remains retryable', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      id: `i${index + 1}`,
      name: `Interface ${index + 1}`,
      interfaceType: 'html',
    }));
    getInterfacesPage.mockImplementation(({ page = 0 }) => page === 0
      ? Promise.resolve({ items: firstPage, totalCount: 101, page: 0, size: 100 })
      : Promise.reject(new Error('page unavailable')));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.interfaces'));
    await revealNextServerPage();
    await waitFor(() => expect(getInterfacesPage).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));

    expect(screen.getByRole('button', { name: 'sidePanel.showMore' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'sidePanel.showMore' }));
    await waitFor(() => expect(
      getInterfacesPage.mock.calls.filter(([options]) => options.page === 1),
    ).toHaveLength(2));
    fireEvent.click(screen.getByRole('button', { name: 'sidePanel.interfaces' }));
    expect((await screen.findByText('sidePanel.interfaces')).parentElement).toHaveTextContent('101');
  });

  it('keeps the files entry available when the workspace contains no other resources', async () => {
    getProjects.mockResolvedValue([]);
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 2 });

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));

    expect((await screen.findByText('sidePanel.files')).parentElement).toHaveTextContent('2');
    expect(screen.getByText('sidePanel.workflows').parentElement).toHaveTextContent('0');
  });

  it('opens logs after selecting a workflow and one of its runs', async () => {
    getWorkflowsPage.mockResolvedValue({
      workflows: [{ id: 'wf1', name: 'Daily digest' }],
      totalCount: 1,
    });

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.logs'));
    fireEvent.click(await screen.findByText('Daily digest'));
    fireEvent.click(await screen.findByText('run-1'));

    expect(openWorkflowLogs).toHaveBeenCalledWith({
      workflowId: 'wf1',
      runId: 'run-1',
      workflowName: 'Daily digest',
    });
  });

  it('searches all workflows while choosing the workflow for logs', async () => {
    getWorkflowsPage.mockImplementation(({ q }: { q?: string }) => Promise.resolve({
      workflows: q ? [{ id: 'wf-remote', name: 'Remote log workflow' }] : [],
      totalCount: q ? 1 : 101,
      page: 0,
      size: 100,
    }));

    render(<AddTabPicker />);
    fireEvent.click(screen.getByTitle('sidePanel.addTab'));
    fireEvent.click(await screen.findByText('sidePanel.logs'));
    fireEvent.change(await screen.findByPlaceholderText('sidePanel.filterPlaceholder'), {
      target: { value: 'Remote log workflow' },
    });

    expect(await screen.findByText('Remote log workflow')).toBeInTheDocument();
    expect(getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      q: 'Remote log workflow',
    }));
  });
});
