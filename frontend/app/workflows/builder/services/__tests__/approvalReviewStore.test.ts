import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  requestApprovalReview,
  clearApprovalReview,
  getApprovalReviewTarget,
  subscribeApprovalReview,
} from '../approvalReviewStore';

describe('approvalReviewStore', () => {
  beforeEach(() => {
    clearApprovalReview();
  });

  it('stores the requested target with its coordinates', () => {
    requestApprovalReview('node-1', 2, 3);
    const target = getApprovalReviewTarget();
    expect(target).toMatchObject({ rfNodeId: 'node-1', epoch: 2, itemIndex: 3 });
  });

  it('bumps requestId on every request so re-clicking the same item re-applies the jump', () => {
    requestApprovalReview('node-1', 1, 0);
    const first = getApprovalReviewTarget()!.requestId;
    requestApprovalReview('node-1', 1, 0);
    const second = getApprovalReviewTarget()!.requestId;
    expect(second).toBeGreaterThan(first);
  });

  it('clear() nulls the target and notifies subscribers', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeApprovalReview(listener);
    requestApprovalReview('node-1', null, null);
    expect(listener).toHaveBeenCalledTimes(1);
    clearApprovalReview();
    expect(listener).toHaveBeenCalledTimes(2);
    expect(getApprovalReviewTarget()).toBeNull();
    unsubscribe();
  });

  it('clear() on an already-null target does not notify (no render churn)', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeApprovalReview(listener);
    clearApprovalReview();
    expect(listener).not.toHaveBeenCalled();
    unsubscribe();
  });

  it('unsubscribed listeners stop receiving notifications', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeApprovalReview(listener);
    unsubscribe();
    requestApprovalReview('node-1', null, null);
    expect(listener).not.toHaveBeenCalled();
  });
});
