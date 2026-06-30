/**
 * useApprovalReviewSelection - WorkflowBuilder side of the approval review
 * flow. When UserApprovalNode publishes a review target (item row click):
 *  - selects that node and switches the inspector to advanced (3-column) mode
 *    so the data navigators can land on the targeted item;
 *  - drops the target as soon as the selection moves elsewhere (other node,
 *    deselect, inspector close) so stale coordinates can never retarget
 *    another node's navigators;
 *  - clears the module-level store on unmount (route change) so a stale
 *    target can never auto-select a same-named node in another workflow.
 */
import { useEffect, useRef } from 'react';
import {
  useApprovalReviewTarget,
  getApprovalReviewTarget,
  clearApprovalReview,
} from '../services/approvalReviewStore';

interface UseApprovalReviewSelectionOptions {
  selectedNodeIds: string[];
  /** Select a single node (WorkflowBuilder's handleSelectionChange([id])). */
  onSelectNode: (nodeId: string) => void;
  /** Switch the inspector to advanced mode. */
  onEnterAdvancedMode: () => void;
}

export function useApprovalReviewSelection({
  selectedNodeIds,
  onSelectNode,
  onEnterAdvancedMode,
}: UseApprovalReviewSelectionOptions): void {
  const target = useApprovalReviewTarget();
  const appliedRequestRef = useRef(0);
  // A request only becomes clearable once the selection has been OBSERVED on
  // the target node ("armed"). Clearing on any mismatch would drop the target
  // in the request commit itself - the selection state hasn't updated yet -
  // or on a re-render that merely rebuilt the selection array.
  const armedRequestRef = useRef(0);

  useEffect(() => {
    if (!target) return;
    if (appliedRequestRef.current === target.requestId) return;
    appliedRequestRef.current = target.requestId;
    onSelectNode(target.rfNodeId);
    onEnterAdvancedMode();
  }, [target, onSelectNode, onEnterAdvancedMode]);

  // Reads the store imperatively on purpose: depending on the reactive target
  // here would arm/clear out of order with the selection updates.
  useEffect(() => {
    const current = getApprovalReviewTarget();
    if (!current) return;
    const onTarget = selectedNodeIds.length === 1 && selectedNodeIds[0] === current.rfNodeId;
    if (onTarget) {
      armedRequestRef.current = current.requestId;
      return;
    }
    if (armedRequestRef.current === current.requestId) {
      clearApprovalReview();
    }
  }, [selectedNodeIds]);

  useEffect(() => () => clearApprovalReview(), []);
}
