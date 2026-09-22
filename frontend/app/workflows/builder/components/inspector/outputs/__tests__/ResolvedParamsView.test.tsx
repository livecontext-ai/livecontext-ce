// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  isFileRef: () => false,
  normalizeFileRef: (v: unknown) => v,
  getFilePath: () => '',
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: true }) }));

const runData = vi.hoisted(() => ({
  value: {
    totalItems: 1,
    isLoading: false,
    error: null as string | null,
    currentIndex: 0,
    currentItem: { id: 'row-1' },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(async (): Promise<Record<string, unknown>> => ({ resolved_params: {} })),
    availableStatuses: [],
  },
}));
vi.mock('../../../../hooks/useRunData', () => ({ useRunData: () => runData.value }));

const liveState = vi.hoisted(() => ({
  value: { liveState: null as null | 'running' | 'awaiting', pendingSignals: [] as unknown[] },
}));
vi.mock('../../../../hooks/useNodeLiveState', () => ({ useNodeLiveState: () => liveState.value }));

import { ResolvedParamsView } from '../ResolvedParamsView';

function setNode(): Node<BuilderNodeData> {
  return {
    id: 'set-1',
    type: 'setNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'set-1',
      label: 'Prepare Payload',
      kind: 'core',
      setAssignments: [{ name: 'label', value: 'x', type: 'string' }],
      setKeepOnlySet: true,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function waitNode(): Node<BuilderNodeData> {
  return {
    id: 'wait-1',
    type: 'waitNode',
    position: { x: 0, y: 0 },
    data: { id: 'wait-1', label: 'Hold', kind: 'core', waitDuration: 5000 } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

/**
 * A catalog tool step: the node that spends real time RUNNING, because it is
 * waiting on a third party. `toolData` is what makes the generator emit it as a
 * `plan.mcps` entry, and `paramExpressions` is where its arguments live.
 */
function toolNode(): Node<BuilderNodeData> {
  return {
    id: 'mcp-send-email',
    type: 'toolNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'mcp-send-email',
      label: 'Send Email',
      kind: 'mcp',
      toolData: { toolSlug: 'send_email' },
      apiData: { apiSlug: 'gmail' },
      paramExpressions: {
        to: '{{trigger:hook.output.email}}',
        subject: 'Your receipt',
      },
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}


function renderView(
  node = setNode(),
  toolParameters?: unknown[],
) {
  return render(
    <ResolvedParamsView
      workflowId="wf-1"
      runId="run-1"
      stepAlias="Prepare Payload"
      node={node}
      toolParameters={toolParameters}
    />,
  );
}

describe('ResolvedParamsView', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    liveState.value = { liveState: null, pendingSignals: [] };
    runData.value = {
      ...runData.value,
      totalItems: 1,
      isLoading: false,
      error: null,
      getObjectAtPath: vi.fn(async () => ({ resolved_params: { keepOnlySet: true, label: 'x' } })),
    };
  });

  it('shows the reported parameters through their human labels', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());
  });

  it('unwraps the resolved_params envelope rather than showing it as a nested object', async () => {
    renderView();
    await waitFor(() => expect(screen.queryByText('resolved_params')).toBeNull());
  });

  it('accepts a payload that is already the unwrapped map (legacy rows)', async () => {
    runData.value = {
      ...runData.value,
      getObjectAtPath: vi.fn(async () => ({ keepOnlySet: false })),
    };
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());
  });

  it('says the parameters are empty instead of rendering a blank panel', async () => {
    runData.value = { ...runData.value, getObjectAtPath: vi.fn(async () => ({ resolved_params: {} })) };
    renderView();
    await waitFor(() => expect(screen.getByText('noResolvedParams')).toBeTruthy());
  });

  it('surfaces a fetch error as an error, not as "no parameters"', async () => {
    runData.value = { ...runData.value, error: 'boom' };
    renderView();
    expect(screen.getByText('boom')).toBeTruthy();
  });

  describe('while the node is still working', () => {
    beforeEach(() => {
      runData.value = { ...runData.value, totalItems: 0 };
    });

    it('says the node is executing and shows what it was launched with', async () => {
      liveState.value = { liveState: 'running', pendingSignals: [] };
      renderView(waitNode());
      expect(screen.getByTestId('node-run-state-running')).toBeTruthy();
      expect(screen.getByText('configuredParamsTitle')).toBeTruthy();
      // The wait node's configured duration, read through the plan generator.
      expect(screen.getByText('Duration (ms)')).toBeTruthy();
    });

    it('says the node is parked on a signal', () => {
      liveState.value = { liveState: 'awaiting', pendingSignals: [] };
      renderView(waitNode());
      expect(screen.getByTestId('node-run-state-awaiting')).toBeTruthy();
    });

    it('falls back to the plain empty state when the node is not live', () => {
      liveState.value = { liveState: null, pendingSignals: [] };
      renderView(waitNode());
      expect(screen.queryByTestId('node-run-state-running')).toBeNull();
      expect(screen.getByText('noResolvedParams')).toBeTruthy();
    });

    it('shows a CATALOG tool node what it was launched with, which is the node a reader waits on longest', () => {
      // An mcp/catalog step is the node that spends real time RUNNING: it is waiting on a
      // third party. It was the one node excluded from this fallback, so its Params column
      // was empty for the whole call and then again on any path that leaves no row. The
      // exclusion was written for the drift COMPARISON, which has nothing to say about
      // names the catalog owns; it was never meant to remove the display.
      liveState.value = { liveState: 'running', pendingSignals: [] };
      renderView(toolNode(), [{ name: 'to', title: 'Recipient' }]);
      expect(screen.getByTestId('node-run-state-running')).toBeTruthy();
      expect(screen.getByText('configuredParamsTitle')).toBeTruthy();
      // The tool's own argument, under the label the catalog gives it.
      expect(screen.getByText('Recipient')).toBeTruthy();
      expect(screen.getByText('{{trigger:hook.output.email}}')).toBeTruthy();
    });

    it('still shows the configuration when the run left NO row for the node', () => {
      // The other half of "nothing is not an answer". A node the run skipped, or never
      // reached, has no row and is not live, and the panel said only "no resolved
      // parameters" - which does not say what the node would have run with, nor why there
      // is no row. The heading keeps the two apart: configuration, not resolution.
      liveState.value = { liveState: null, pendingSignals: [] };
      renderView(toolNode(), [{ name: 'to', title: 'Recipient' }]);
      expect(screen.getByText('noResolvedParams')).toBeTruthy();
      expect(screen.getByText('configuredParamsTitle')).toBeTruthy();
      expect(screen.getByText('Recipient')).toBeTruthy();
    });

    it('never renders a configured SECRET, in either branch of the fallback', () => {
      // The fallback reads the plan entry straight off the canvas, so it bypasses the
      // backend gate completely. `crypto_jwt` keeps its signing secret there and
      // `http_request` its whole authConfig block, and this panel showed both verbatim -
      // parked, running, or with no row. Not a disclosure to a new principal (the same
      // reader can open the edit form), but the same key answering two ways in one panel:
      // an approval parked for two days showed its secret, and the moment the signal
      // resolved the same panel showed it masked.
      const jwt = {
        id: 'crypto_jwt-1',
        type: 'cryptoJwtNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'crypto_jwt-1',
          label: 'Sign',
          kind: 'crypto_jwt',
          paramExpressions: { operation: 'sign', secret: 'hunter2-the-hmac-secret' },
        } as unknown as BuilderNodeData,
      } as Node<BuilderNodeData>;

      for (const state of ['running', null] as const) {
        liveState.value = { liveState: state, pendingSignals: [] };
        const { unmount } = renderView(jwt);
        expect(
          screen.queryByText('hunter2-the-hmac-secret'),
          `the signing secret must not render while liveState=${state}`,
        ).toBeNull();
        unmount();
      }
    });
  });

  it('shows no developer-facing alignment warning: the reader is not the audience for it', async () => {
    // The panel used to warn when a configured parameter was absent from what the
    // run reported. That is a statement about the PRODUCT (a node under-reporting),
    // not about the user's workflow, and it also fired on old runs whose rows carry
    // pre-rename keys - a warning nobody could act on. The alignment check itself
    // lives on in the e2e spec, where it belongs.
    runData.value = {
      ...runData.value,
      getObjectAtPath: vi.fn(async () => ({ resolved_params: { waited_ms: 5000 } })),
    };
    renderView(waitNode());

    await waitFor(() => expect(screen.getByTestId('resolved-params-view')).toBeTruthy());
    expect(screen.queryByTestId('param-alignment-mismatches')).toBeNull();
  });

  it('offers the raw-JSON view of the reported parameters', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());

    fireEvent.click(screen.getByRole('tab', { name: 'viewJson' }));
    expect(screen.getByTestId('run-data-json-view').textContent).toContain('"keepOnlySet"');
  });

  it('copies the whole reported map', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Keep only set fields')).toBeTruthy());

    fireEvent.click(screen.getByTestId('run-data-copy-all'));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
      JSON.stringify({ keepOnlySet: true, label: 'x' }, null, 2),
    );
  });

  // An interface reports `variableMapping` as a map of maps - the first reported
  // parameter whose value is not a scalar, a list or a flat object. The panel labels
  // TOP-LEVEL keys only, so the variable names and their {expression, resolved,
  // status} fields must read as themselves.
  describe('an interface node reporting its variable mapping', () => {
    function interfaceNode(): Node<BuilderNodeData> {
      return {
        id: 'interface-1',
        type: 'interfaceNode',
        position: { x: 0, y: 0 },
        data: { id: 'interface-1', label: 'Listing Page', kind: 'interface' } as unknown as BuilderNodeData,
      } as Node<BuilderNodeData>;
    }

    beforeEach(() => {
      runData.value = {
        ...runData.value,
        totalItems: 1,
        getObjectAtPath: vi.fn(async () => ({
          resolved_params: {
            interfaceId: 'uuid-123',
            variableMapping: {
              result: {
                expression: '{{core:normalize.output}}',
                resolved: 'Map(keys=[result])',
                status: 'resolved',
              },
            },
          },
        })),
      };
    });

    it('labels the block through the registry and keeps the variable name readable underneath', async () => {
      renderView(interfaceNode());

      await waitFor(() => expect(screen.getByText('Variables')).toBeTruthy());
      fireEvent.click(screen.getByText('Variables'));
      await waitFor(() => expect(screen.getByText('result')).toBeTruthy());
    });

    it('shows the expression and what it resolved to, which is the whole point of the block', async () => {
      renderView(interfaceNode());

      await waitFor(() => expect(screen.getByText('Variables')).toBeTruthy());
      fireEvent.click(screen.getByText('Variables'));
      await waitFor(() => expect(screen.getByText('result')).toBeTruthy());
      fireEvent.click(screen.getByText('result'));

      const showing = (needle: string) => (content: string) => content.includes(needle);
      await waitFor(() =>
        expect(screen.getAllByText(showing('{{core:normalize.output}}')).length).toBeGreaterThan(0),
      );
      // The double-`result` diagnosis, as the reader sees it.
      expect(screen.getAllByText(showing('Map(keys=[result])')).length).toBeGreaterThan(0);
    });
  });
});
