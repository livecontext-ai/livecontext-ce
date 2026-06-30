// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { act } from '@testing-library/react';
import { useApprovalReviewSelection } from '../useApprovalReviewSelection';
import {
  requestApprovalReview,
  clearApprovalReview,
  getApprovalReviewTarget,
} from '../../services/approvalReviewStore';

// WorkflowBuilder side of the approval review flow: select-on-request (one
// shot per requestId), clear-on-selection-change (imperative store read so the
// request commit cannot self-clear), and clear-on-unmount.

describe('useApprovalReviewSelection', () => {
  const onSelectNode = vi.fn();
  const onEnterAdvancedMode = vi.fn();

  function mount(initialSelection: string[] = []) {
    return renderHook(
      ({ selectedNodeIds }: { selectedNodeIds: string[] }) =>
        useApprovalReviewSelection({ selectedNodeIds, onSelectNode, onEnterAdvancedMode }),
      { initialProps: { selectedNodeIds: initialSelection } },
    );
  }

  beforeEach(() => {
    clearApprovalReview();
    onSelectNode.mockClear();
    onEnterAdvancedMode.mockClear();
  });

  it('selects the requested node and enters advanced mode on a new request', () => {
    mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(onSelectNode).toHaveBeenCalledWith('approval-1');
    expect(onEnterAdvancedMode).toHaveBeenCalledTimes(1);
  });

  it('does not re-select on re-renders for the same requestId', () => {
    const { rerender } = mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ selectedNodeIds: [] });
    rerender({ selectedNodeIds: [] });
    expect(onSelectNode).toHaveBeenCalledTimes(1);
  });

  it('re-selects when the same item is re-clicked (new requestId)', () => {
    mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(onSelectNode).toHaveBeenCalledTimes(2);
  });

  it('does NOT clear the target in the request commit (selection state not yet updated)', () => {
    const { rerender } = mount(['other-node']);
    act(() => requestApprovalReview('approval-1', 1, 0));
    // Selection prop unchanged in this commit - clear effect must not fire.
    rerender({ selectedNodeIds: ['other-node'] });
    expect(getApprovalReviewTarget()).not.toBeNull();
  });

  it('keeps the target once the selection catches up with the requested node', () => {
    const { rerender } = mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ selectedNodeIds: ['approval-1'] });
    expect(getApprovalReviewTarget()).toMatchObject({ rfNodeId: 'approval-1' });
  });

  it('clears the target when the selection moves to another node', () => {
    const { rerender } = mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ selectedNodeIds: ['approval-1'] });
    rerender({ selectedNodeIds: ['some-other-node'] });
    expect(getApprovalReviewTarget()).toBeNull();
  });

  it('clears the target on deselect (inspector close)', () => {
    const { rerender } = mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ selectedNodeIds: ['approval-1'] });
    rerender({ selectedNodeIds: [] });
    expect(getApprovalReviewTarget()).toBeNull();
  });

  it('clears the module-level store on unmount (route change leak guard)', () => {
    const { rerender, unmount } = mount();
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ selectedNodeIds: ['approval-1'] });
    unmount();
    expect(getApprovalReviewTarget()).toBeNull();
  });
});
