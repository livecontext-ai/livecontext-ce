// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

const {
  openTab, removeTab,
  getMyPublications, getAcquiredApplications,
  getInterfacesPage, getDataSources, getWorkflows, getAgents,
  getConversations, getProjects, getProjectResources,
} = vi.hoisted(() => ({
  openTab: vi.fn(), removeTab: vi.fn(),
  getMyPublications: vi.fn(), getAcquiredApplications: vi.fn(),
  getInterfacesPage: vi.fn(), getDataSources: vi.fn(), getWorkflows: vi.fn(), getAgents: vi.fn(),
  getConversations: vi.fn(), getProjects: vi.fn(), getProjectResources: vi.fn(),
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab, removeTab }),
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { getDataSources, getWorkflows, getAgents },
}));
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { getInterfacesPage },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getMyPublications, getAcquiredApplications },
}));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { getConversations } }));
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

import { AddTabPicker } from '../AddTabPicker';

describe('AddTabPicker - files in the project drill-down', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMyPublications.mockResolvedValue({ publications: [] });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getInterfacesPage.mockResolvedValue({ items: [] });
    getDataSources.mockResolvedValue([]);
    getWorkflows.mockResolvedValue([]);
    getAgents.mockResolvedValue([]);
    getConversations.mockResolvedValue({ content: [] });
    getProjects.mockResolvedValue([{ id: 'pr1', name: 'Alpha', icon: 'box', color: '#fff' }]);
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
});
