// @vitest-environment jsdom
/**
 * Run-mode context on the User Approval pending-signal rows.
 *
 * When a workflow pauses on a USER_APPROVAL signal inside a Split, the per-item
 * list must tell the user WHICH item each row refers to:
 *   - "Item N" is 1-based (itemId is the 0-based split index),
 *   - an "Epoch N" badge appears when the signal carries an epoch (RAW value -
 *     fires are numbered from 1 by TriggerEpochManager, same as the epoch selector),
 *   - when the backend sends `itemContext` (the split item's context, e.g.
 *     current_item), a one-line truncated preview is shown with the full text
 *     in the `title` tooltip.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';

let mockExec: any;
let mockMode: any;

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      itemBadge: 'Item {number}',
      epochBadge: 'Epoch {number}',
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => mockExec,
}));
vi.mock('../../../contexts/ValidationContext', () => ({
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('../../../nodes/nodeClasses', () => ({
  findNodeClassById: () => undefined,
}));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../NodeBottomBar', () => ({ NodeBottomBar: () => null }));
vi.mock('../../NodePlayButton', () => ({
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="spinner" />,
}));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
}));
// Open-gated Dialog so the approval-context modal renders deterministically (no Radix portal in jsdom).
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div data-testid="dialog">{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { UserApprovalNode, formatItemContextPreview } from '../UserApprovalNode';
import {
  getApprovalReviewTarget,
  clearApprovalReview,
} from '../../../services/approvalReviewStore';

const signal = (over: Partial<PendingSignal> & { id: number }): PendingSignal => ({
  nodeId: 'core:approve',
  signalType: 'USER_APPROVAL',
  status: 'PENDING',
  ...over,
});

const execAwaiting = (signals: PendingSignal[]) => ({
  isStepByStepMode: false,
  isRunning: false,
  isExecuting: false,
  isAwaitingSignal: true,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  isEvaluated: false,
  isReady: false,
  pendingSignals: signals,
  pendingSignalCount: signals.length,
  resolveApproval: vi.fn(),
});

function renderAwaiting(signals: PendingSignal[]) {
  mockExec = execAwaiting(signals);
  const utils = render(
    <UserApprovalNode
      data={{ id: 'approval-1', label: 'Approve items' } as any}
      selected={false}
      id="approval-1"
      // NodeProps fields unused by the component
      type="approval" zIndex={0} isConnectable dragging={false} xPos={0} yPos={0}
    />,
  );
  // Split context list is collapsed by default - expand it.
  fireEvent.click(utils.getByText(`Show ${signals.length} items`));
  return utils;
}

beforeEach(() => {
  mockMode = { isRunMode: true, viewingEpoch: null };
  clearApprovalReview();
});

describe('UserApprovalNode - pending-signal row context', () => {
  it('renders the split item number 1-based (itemId 0 → "Item 1", itemId 1 → "Item 2")', () => {
    const { getByText } = renderAwaiting([
      signal({ id: 1, itemId: '0' }),
      signal({ id: 2, itemId: '1' }),
    ]);
    expect(getByText('Item 1')).toBeTruthy();
    expect(getByText('Item 2')).toBeTruthy();
  });

  it('shows the RAW "Epoch N" badge (TriggerEpochManager numbers fires from 1 - matches the epoch selector and Files browser)', () => {
    const { getByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0', epoch: 2 }),
      signal({ id: 2, itemId: '1' }),
    ]);
    expect(getByTestId('signal-epoch-badge-1').textContent).toBe('Epoch 2');
  });

  it('renders NO epoch badge when the signal has no epoch', () => {
    const { queryByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0' }),
      signal({ id: 2, itemId: '1' }),
    ]);
    expect(queryByTestId('signal-epoch-badge-1')).toBeNull();
    expect(queryByTestId('signal-epoch-badge-2')).toBeNull();
  });

  it('does NOT render the raw itemContext JSON in the per-item list (removed as not useful)', () => {
    const { queryByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0', itemContext: { current_index: 0, current_item: 'Alice order #42' } }),
      signal({ id: 2, itemId: '1' }),
    ]);
    expect(queryByTestId('signal-item-context-1')).toBeNull();
    expect(queryByTestId('signal-item-context-2')).toBeNull();
  });

  it('shows the configured approvalContext per item in the split list', () => {
    const { getByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0', approvalContext: 'Approve Alice order' }),
      signal({ id: 2, itemId: '1', approvalContext: 'Approve Bob order' }),
    ]);
    expect(getByTestId('signal-approval-context-1').textContent).toBe('Approve Alice order');
    expect(getByTestId('signal-approval-context-2').textContent).toBe('Approve Bob order');
  });

  it('shows the single-approval context (labelled + clickable) when there is one pending signal with approvalContext', () => {
    mockExec = execAwaiting([signal({ id: 1, itemId: '0', approvalContext: 'Approve refund of 120 EUR?' })]);
    const { getByTestId } = render(
      <UserApprovalNode
        data={{ id: 'approval-1', label: 'Approve items' } as any}
        selected={false}
        id="approval-1"
        type="approval" zIndex={0} isConnectable dragging={false} xPos={0} yPos={0}
      />,
    );
    // The chip now carries a label caption + the resolved text and is the modal
    // trigger (a button), so assert the text is present rather than ===.
    const chip = getByTestId('approval-node-context');
    expect(chip.tagName).toBe('BUTTON');
    expect(chip.textContent).toContain('Approve refund of 120 EUR?');
    expect(chip.textContent).toContain('approvalContextLabel'); // mocked i18n key
  });

  it('opens the approval-context modal with the full text when the single chip is clicked', () => {
    mockExec = execAwaiting([signal({ id: 1, itemId: '0', approvalContext: 'Approve refund of 120 EUR?' })]);
    const { getByTestId, queryByTestId } = render(
      <UserApprovalNode
        data={{ id: 'approval-1', label: 'Approve items' } as any}
        selected={false}
        id="approval-1"
        type="approval" zIndex={0} isConnectable dragging={false} xPos={0} yPos={0}
      />,
    );
    expect(queryByTestId('approval-context-dialog-text')).toBeNull(); // closed initially
    fireEvent.click(getByTestId('approval-node-context'));
    expect(getByTestId('approval-context-dialog-text').textContent).toBe('Approve refund of 120 EUR?');
  });

  it('split: the "Review N" button opens the review modal at the first item and Approve resolves it', async () => {
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    mockExec = {
      ...execAwaiting([
        signal({ id: 1, itemId: '0', epoch: 1, approvalContext: 'Approve Alice' }),
        signal({ id: 2, itemId: '1', epoch: 1, approvalContext: 'Approve Bob' }),
      ]),
      resolveApproval,
    };
    const { getByTestId } = render(
      <UserApprovalNode
        data={{ id: 'approval-1', label: 'Approve items' } as any}
        selected={false}
        id="approval-1"
        type="approval" zIndex={0} isConnectable dragging={false} xPos={0} yPos={0}
      />,
    );
    fireEvent.click(getByTestId('approval-node-review-all'));
    expect(getByTestId('approval-context-dialog-text').textContent).toBe('Approve Alice');
    await act(async () => { fireEvent.click(getByTestId('approval-modal-approve')); });
    expect(resolveApproval).toHaveBeenCalledWith('APPROVED', '0', 1);
  });

  it("clicking an item row publishes the review target (node id + signal's epoch + 0-based itemIndex)", () => {
    const { getByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0', epoch: 2 }),
      signal({ id: 2, itemId: '1', epoch: 2 }),
    ]);
    fireEvent.click(getByTestId('signal-item-review-2'));
    expect(getApprovalReviewTarget()).toMatchObject({
      rfNodeId: 'approval-1',
      epoch: 2,
      itemIndex: 1,
    });
  });

  it('clicking an item row without epoch/numeric itemId publishes null coordinates (permissive match)', () => {
    const { getByTestId } = renderAwaiting([
      signal({ id: 1, itemId: '0' }),
      signal({ id: 2, itemId: 'abc' }),
    ]);
    fireEvent.click(getByTestId('signal-item-review-2'));
    expect(getApprovalReviewTarget()).toMatchObject({
      rfNodeId: 'approval-1',
      epoch: null,
      itemIndex: null,
    });
  });

  it("the row's approve button resolves with the signal's own epoch (not the viewed epoch)", () => {
    const resolveApproval = vi.fn();
    mockExec = { ...execAwaiting([
      signal({ id: 1, itemId: '0', epoch: 3 }),
      signal({ id: 2, itemId: '1', epoch: 3 }),
    ]), resolveApproval };
    const utils = render(
      <UserApprovalNode
        data={{ id: 'approval-1', label: 'Approve items' } as any}
        selected={false}
        id="approval-1"
        type="approval" zIndex={0} isConnectable dragging={false} xPos={0} yPos={0}
      />,
    );
    fireEvent.click(utils.getByText('Show 2 items'));
    const row = utils.getByTestId('signal-item-review-1').parentElement!;
    // The per-item action buttons live in the row's trailing <div> (the review
    // button is a direct child of the row, so scope past it).
    const buttons = row.querySelectorAll(':scope > div > button');
    fireEvent.click(buttons[0]); // per-item approve
    expect(resolveApproval).toHaveBeenCalledWith('APPROVED', '0', 3);
  });
});

describe('formatItemContextPreview', () => {
  it('prefers the first non-empty string value over JSON', () => {
    expect(formatItemContextPreview({ idx: 3, name: 'Bob' })).toEqual({ preview: 'Bob', full: 'Bob' });
  });

  it('falls back to compact JSON when no string value exists', () => {
    expect(formatItemContextPreview({ idx: 3, ok: true })).toEqual({
      preview: '{"idx":3,"ok":true}',
      full: '{"idx":3,"ok":true}',
    });
  });

  it('caps the preview at 80 chars (79 + ellipsis) but keeps full untruncated', () => {
    const long = 'a'.repeat(200);
    const result = formatItemContextPreview({ v: long });
    expect(result?.preview).toHaveLength(80);
    expect(result?.preview.endsWith('…')).toBe(true);
    expect(result?.full).toBe(long);
  });

  it('returns null for null, non-objects, arrays and empty objects', () => {
    expect(formatItemContextPreview(null)).toBeNull();
    expect(formatItemContextPreview(undefined)).toBeNull();
    expect(formatItemContextPreview('text')).toBeNull();
    expect(formatItemContextPreview(['a'])).toBeNull();
    expect(formatItemContextPreview({})).toBeNull();
  });
});
