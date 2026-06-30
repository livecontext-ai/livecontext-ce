// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  useApprovalReviewLayout,
  APPROVAL_REVIEW_INPUT_WIDTH,
} from '../useApprovalReviewLayout';
import {
  requestApprovalReview,
  clearApprovalReview,
} from '../../../services/approvalReviewStore';

// InspectorPanel side of the approval review flow: collapse output / expand +
// widen input, one-shot per requestId, WITHOUT consuming the requestId while
// the inspected node doesn't match (the panel may still show the previous
// node in the commit where the request lands).

describe('useApprovalReviewLayout', () => {
  const setOutputCollapsed = vi.fn();
  const setInputCollapsed = vi.fn();
  const setInputWidth = vi.fn();

  function mount(nodeId: string | undefined, inputWidth = 280) {
    return renderHook(
      (props: { nodeId: string | undefined; inputWidth: number }) =>
        useApprovalReviewLayout({
          nodeId: props.nodeId,
          setOutputCollapsed,
          setInputCollapsed,
          inputWidth: props.inputWidth,
          setInputWidth,
        }),
      { initialProps: { nodeId, inputWidth } },
    );
  }

  beforeEach(() => {
    clearApprovalReview();
    setOutputCollapsed.mockClear();
    setInputCollapsed.mockClear();
    setInputWidth.mockClear();
  });

  it('collapses output, expands input and widens it for the targeted node', () => {
    mount('approval-1');
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(setOutputCollapsed).toHaveBeenCalledWith(true);
    expect(setInputCollapsed).toHaveBeenCalledWith(false);
    expect(setInputWidth).toHaveBeenCalledWith(APPROVAL_REVIEW_INPUT_WIDTH);
  });

  it('does not shrink an input column already wider than the review floor', () => {
    mount('approval-1', APPROVAL_REVIEW_INPUT_WIDTH + 60);
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(setOutputCollapsed).toHaveBeenCalledWith(true);
    expect(setInputWidth).not.toHaveBeenCalled();
  });

  it('does NOT consume the requestId while another node is inspected, then applies once the node matches', () => {
    const { rerender } = mount('other-node');
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(setOutputCollapsed).not.toHaveBeenCalled();

    // The builder selection catches up - the SAME request must now apply.
    rerender({ nodeId: 'approval-1', inputWidth: 280 });
    expect(setOutputCollapsed).toHaveBeenCalledWith(true);
    expect(setInputCollapsed).toHaveBeenCalledWith(false);
  });

  it('applies only once per requestId across re-renders', () => {
    const { rerender } = mount('approval-1');
    act(() => requestApprovalReview('approval-1', 1, 0));
    rerender({ nodeId: 'approval-1', inputWidth: 280 });
    rerender({ nodeId: 'approval-1', inputWidth: 280 });
    expect(setOutputCollapsed).toHaveBeenCalledTimes(1);
  });

  it('re-asserts the review layout on a new request (auto-advance or re-click)', () => {
    mount('approval-1');
    act(() => requestApprovalReview('approval-1', 1, 0));
    act(() => requestApprovalReview('approval-1', 1, 1));
    expect(setOutputCollapsed).toHaveBeenCalledTimes(2);
  });

  it('does nothing without a target or without an inspected node', () => {
    mount(undefined);
    act(() => requestApprovalReview('approval-1', 1, 0));
    expect(setOutputCollapsed).not.toHaveBeenCalled();
  });
});
