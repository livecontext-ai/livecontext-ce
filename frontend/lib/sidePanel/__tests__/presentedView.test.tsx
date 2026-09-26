/**
 * @vitest-environment jsdom
 *
 * workflow(action='present') lets the agent open any resource in the side panel.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

const openWorkflowBuilderTab = vi.hoisted(() => vi.fn());
const openFilesPanel = vi.hoisted(() => vi.fn());
const requestPresentApplication = vi.hoisted(() => vi.fn());

vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/InterfacePanelContent', () => ({ InterfacePanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null, AGENT_CONFIGURATION_TAB: 'configuration' }));
vi.mock('@/lib/sidePanel/openWorkflowBuilderTab', () => ({ openWorkflowBuilderTab }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel }));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ requestPresentApplication }));

import { openPresentedView } from '@/lib/sidePanel/presentedView';

describe('openPresentedView', () => {
  const openTab = vi.fn();
  const panel = { openTab };

  afterEach(() => {
    openTab.mockReset();
    openWorkflowBuilderTab.mockReset();
    openFilesPanel.mockReset();
    requestPresentApplication.mockReset();
  });

  const elsewhere = { workflowPageId: null, isChatPage: false };

  it.each([
    ['present_table', 'datasource-7', '7'],
    ['present_interface', 'interface-i-1', 'i-1'],
    ['present_agent', 'agent-a-1', 'a-1'],
  ])('%s opens its resource tab', (type, tabId, id) => {
    expect(openPresentedView(panel, { type, id, title: 'Leads' }, elsewhere)).toBe(true);

    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab.mock.calls[0][0]).toMatchObject({ id: tabId, label: 'Leads' });
  });

  it('present_file opens the file in the Files tab', async () => {
    expect(openPresentedView(panel, { type: 'present_file', id: 'f-1', title: 'report.pdf' }, elsewhere)).toBe(true);
    await vi.waitFor(() => expect(openFilesPanel).toHaveBeenCalledWith(panel, { id: 'f-1', name: 'report.pdf' }));
  });

  it('present_workflow opens another workflow in the panel', () => {
    expect(openPresentedView(panel, { type: 'present_workflow', id: 'wf-2', title: 'Sync' }, { workflowPageId: 'wf-1', isChatPage: false })).toBe(true);
    expect(openWorkflowBuilderTab).toHaveBeenCalledWith(panel, { workflowId: 'wf-2', workflowName: 'Sync' });
  });

  it('present_workflow of the workflow the page already shows opens nothing', () => {
    expect(openPresentedView(panel, { type: 'present_workflow', id: 'wf-1' }, { workflowPageId: 'wf-1', isChatPage: false })).toBe(false);
    expect(openWorkflowBuilderTab).not.toHaveBeenCalled();
  });

  // The ordinary build markers (table, interface...) must never move the view on every page.
  it.each(['table', 'interface', 'agent', 'workflow', 'workflow_run'])(
    'ignores %s, which is not a page-wide presentation', (type) => {
      expect(openPresentedView(panel, { type, id: 'x', runId: 'r' }, elsewhere)).toBe(false);
      expect(openTab).not.toHaveBeenCalled();
      expect(openWorkflowBuilderTab).not.toHaveBeenCalled();
    },
  );

  // Off the chat and off the workflow's own page, nothing else would open a run view: a
  // presentation there used to report success while the screen stayed still.
  it('opens a presented Application off the chat and asks it to show the interfaces', async () => {
    expect(openPresentedView(panel, { type: 'present_application', id: 'wf-2', runId: 'run-1', title: 'Leads' },
      { workflowPageId: 'wf-1', isChatPage: false })).toBe(true);

    expect(openWorkflowBuilderTab).toHaveBeenCalledWith(panel, { workflowId: 'wf-2', runId: 'run-1', workflowName: 'Leads' });
    await vi.waitFor(() => expect(requestPresentApplication).toHaveBeenCalledWith('wf-2', 'run-1'));
  });

  it('opens a presented run off the chat without asking for the Application', async () => {
    expect(openPresentedView(panel, { type: 'present_run', id: 'wf-2', runId: 'run-1' }, elsewhere)).toBe(true);

    expect(openWorkflowBuilderTab).toHaveBeenCalledWith(panel, { workflowId: 'wf-2', runId: 'run-1', workflowName: undefined });
    await Promise.resolve();
    expect(requestPresentApplication).not.toHaveBeenCalled();
  });

  it.each([
    ['the chat, whose own auto-open shows runs', { workflowPageId: null, isChatPage: true }],
    ['the page of that workflow, which switches in place', { workflowPageId: 'wf-2', isChatPage: false }],
  ])('leaves run views to %s', (_where, page) => {
    expect(openPresentedView(panel, { type: 'present_application', id: 'wf-2', runId: 'run-1' }, page)).toBe(false);
    expect(openWorkflowBuilderTab).not.toHaveBeenCalled();
  });
});
