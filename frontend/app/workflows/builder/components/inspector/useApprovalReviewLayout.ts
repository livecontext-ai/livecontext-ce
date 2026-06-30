/**
 * useApprovalReviewLayout - InspectorPanel side of the approval review flow.
 * When a review target is published for the inspected node, collapse the
 * output column (an awaiting item has no output yet) and expand + widen the
 * input column - the approve/reject decision is made from the input data.
 *
 * One-shot per requestId, and the requestId is NOT consumed while the
 * inspected node doesn't match (the panel may still be showing the previous
 * node in that commit) - the layout applies once the right node is inspected.
 * The user can still re-arrange the columns manually afterwards; each
 * subsequent request (row click or auto-advance) re-asserts the review layout.
 */
import { useEffect, useRef } from 'react';
import { useApprovalReviewTarget } from '../../services/approvalReviewStore';

export const APPROVAL_REVIEW_INPUT_WIDTH = 440;

interface UseApprovalReviewLayoutOptions {
  /** ReactFlow id of the currently inspected node (undefined when none). */
  nodeId: string | undefined;
  setOutputCollapsed: (collapsed: boolean) => void;
  setInputCollapsed: (collapsed: boolean) => void;
  inputWidth: number;
  setInputWidth: (width: number) => void;
}

export function useApprovalReviewLayout({
  nodeId,
  setOutputCollapsed,
  setInputCollapsed,
  inputWidth,
  setInputWidth,
}: UseApprovalReviewLayoutOptions): void {
  const target = useApprovalReviewTarget();
  const appliedRequestRef = useRef(0);

  // Width read through a ref so applying the layout doesn't depend on (and
  // re-fire with) every manual resize.
  const inputWidthRef = useRef(inputWidth);
  inputWidthRef.current = inputWidth;

  useEffect(() => {
    if (!target || !nodeId) return;
    if (target.rfNodeId !== nodeId) return; // don't consume - panel may switch next commit
    if (appliedRequestRef.current === target.requestId) return;
    appliedRequestRef.current = target.requestId;
    setOutputCollapsed(true);
    setInputCollapsed(false);
    if (inputWidthRef.current < APPROVAL_REVIEW_INPUT_WIDTH) {
      setInputWidth(APPROVAL_REVIEW_INPUT_WIDTH);
    }
  }, [target, nodeId, setOutputCollapsed, setInputCollapsed, setInputWidth]);
}
