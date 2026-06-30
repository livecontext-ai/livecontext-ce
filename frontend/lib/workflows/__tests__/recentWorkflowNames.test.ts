import { describe, it, expect } from 'vitest';
import { rememberWorkflowName, recallWorkflowName, forgetWorkflowName } from '../recentWorkflowNames';

describe('recentWorkflowNames', () => {
  it('recalls a name that was remembered', () => {
    rememberWorkflowName('id-1', 'My flow');
    expect(recallWorkflowName('id-1')).toBe('My flow');
  });

  it('returns undefined for an id that was never primed (the card-navigation path)', () => {
    expect(recallWorkflowName('never-set')).toBeUndefined();
  });

  it('returns undefined for null / undefined ids', () => {
    expect(recallWorkflowName(null)).toBeUndefined();
    expect(recallWorkflowName(undefined)).toBeUndefined();
  });

  it('ignores blank ids and blank names (never poisons the cache)', () => {
    rememberWorkflowName('', 'ghost');
    rememberWorkflowName('id-2', '');
    expect(recallWorkflowName('')).toBeUndefined();
    expect(recallWorkflowName('id-2')).toBeUndefined();
  });

  it('forget drops a primed name so it can never resurface (consumed-once / rename)', () => {
    rememberWorkflowName('id-3', 'Foo');
    forgetWorkflowName('id-3');
    expect(recallWorkflowName('id-3')).toBeUndefined();
  });

  it('forget is a safe no-op for null / undefined ids', () => {
    expect(() => forgetWorkflowName(null)).not.toThrow();
    expect(() => forgetWorkflowName(undefined)).not.toThrow();
  });
});
