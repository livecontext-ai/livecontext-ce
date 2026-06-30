// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

// Mock the data hook so we control toolDetails directly.
const mockUseMcpToolDetails = vi.fn();
vi.mock('../../../hooks/useMcpData', () => ({
  useMcpToolDetails: (slug: string | null) => mockUseMcpToolDetails(slug),
}));

import { useInspectorToolDetails } from '../useInspectorToolDetails';

describe('useInspectorToolDetails', () => {
  beforeEach(() => {
    mockUseMcpToolDetails.mockReset();
  });

  it('triggers a sync when the saved snapshot lacks metadata that the new fetch carries (regression for inspector dropdown blind spot)', () => {
    // Pre-existing workflow saved BEFORE defaultValue/allowedValues were
    // added to the DTO. The frontend cached parameters with only the legacy
    // fields. After the migration, /tools/:slug/details now returns the same
    // count of parameters but with the new metadata. Pre-fix, the
    // length-only `hasChanges` check skipped the resync, leaving the
    // ParamFieldSwitcher in expression-only mode. Post-fix, paramShapeUpgraded
    // forces a sync and the dropdown surfaces.
    const onUpdate = vi.fn();

    const savedNode = {
      id: 'node-1',
      data: {
        toolData: {
          toolSlug: 'openai-chat-completion',
          toolName: 'Chat Completion',
          parameters: [
            { name: 'model', dataType: 'string', isRequired: true },
            { name: 'temperature', dataType: 'number', isRequired: false },
          ],
          responses: [],
          credentials: [],
        },
      },
    } as any;

    mockUseMcpToolDetails.mockReturnValue({
      data: {
        name: 'Chat Completion',
        parameters: [
          {
            name: 'model',
            dataType: 'string',
            isRequired: true,
            defaultValue: 'gpt-4o',
            allowedValues: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1'],
          },
          {
            name: 'temperature',
            dataType: 'number',
            isRequired: false,
            defaultValue: '1',
            allowedValues: null,
          },
        ],
        responses: [],
        credentials: [],
      },
      isLoading: false,
    });

    renderHook(() =>
      useInspectorToolDetails({ node: savedNode, isToolNode: true, onUpdate })
    );

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updatedToolData = onUpdate.mock.calls[0][0].toolData;
    expect(updatedToolData.parameters[0].allowedValues).toEqual([
      'gpt-4o',
      'gpt-4o-mini',
      'gpt-4.1',
    ]);
    expect(updatedToolData.parameters[0].defaultValue).toBe('gpt-4o');
    expect(updatedToolData.parameters[1].defaultValue).toBe('1');
  });

  it('triggers a sync when the new fetch adds a Drive picker hint absent from the saved snapshot', () => {
    // A Google Sheets node saved before DriveFileParamPolicy added extras.picker: same param
    // count, no defaultValue/allowedValues change, only the picker hint is new. Pre-fix the
    // length-only check skipped the resync and the param kept rendering a raw text input forever.
    const onUpdate = vi.fn();
    const savedNode = {
      id: 'node-1',
      data: {
        toolData: {
          toolSlug: 'google-sheets-get-values',
          toolName: 'Get Values',
          parameters: [{ name: 'spreadsheetId', dataType: 'string', isRequired: true }],
          responses: [],
          credentials: [],
        },
      },
    } as any;

    mockUseMcpToolDetails.mockReturnValue({
      data: {
        name: 'Get Values',
        parameters: [
          {
            name: 'spreadsheetId',
            dataType: 'string',
            isRequired: true,
            extras: {
              picker: { provider: 'google-drive', mimeType: 'application/vnd.google-apps.spreadsheet' },
            },
          },
        ],
        responses: [],
        credentials: [],
      },
      isLoading: false,
    });

    renderHook(() => useInspectorToolDetails({ node: savedNode, isToolNode: true, onUpdate }));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const updated = onUpdate.mock.calls[0][0].toolData;
    expect(updated.parameters[0].extras.picker.mimeType).toBe('application/vnd.google-apps.spreadsheet');
  });

  it('does not trigger a redundant sync when both saved and fetched params already have metadata', () => {
    const onUpdate = vi.fn();
    const params = [
      {
        name: 'model',
        dataType: 'string',
        isRequired: true,
        defaultValue: 'gpt-4o',
        allowedValues: ['gpt-4o', 'gpt-4o-mini'],
      },
    ];

    mockUseMcpToolDetails.mockReturnValue({
      data: { name: 'Chat', parameters: params, responses: [], credentials: [] },
      isLoading: false,
    });

    renderHook(() =>
      useInspectorToolDetails({
        node: {
          id: 'node-1',
          data: {
            toolData: {
              toolSlug: 'openai-chat-completion',
              toolName: 'Chat',
              parameters: params,
              responses: [],
              credentials: [],
            },
          },
        } as any,
        isToolNode: true,
        onUpdate,
      })
    );

    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('does not trigger a sync when there is no metadata to surface', () => {
    // No allowedValues on either side, same length - no upgrade signal.
    const onUpdate = vi.fn();
    const params = [{ name: 'prompt', dataType: 'string', isRequired: true }];

    mockUseMcpToolDetails.mockReturnValue({
      data: { name: 'Tool', parameters: params, responses: [], credentials: [] },
      isLoading: false,
    });

    renderHook(() =>
      useInspectorToolDetails({
        node: {
          id: 'node-1',
          data: {
            toolData: {
              toolSlug: 'tool-x',
              toolName: 'Tool',
              parameters: params,
              responses: [],
              credentials: [],
            },
          },
        } as any,
        isToolNode: true,
        onUpdate,
      })
    );

    expect(onUpdate).not.toHaveBeenCalled();
  });
});
