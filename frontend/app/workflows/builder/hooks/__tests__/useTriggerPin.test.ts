/**
 * The trigger pin affordance (the "set as production" pin button under a trigger
 * node, and now the same item in the node right-click menu) derives its
 * visibility + pin-vs-unpin state from one pure helper. These tests pin that
 * version-math so the button and the menu can never drift apart.
 */
import { describe, it, expect } from 'vitest';
import { computeTriggerPinState, triggerPinTitle } from '../useTriggerPin';

/** Identity translator so assertions read the keys the label is built from. */
const tKey = (k: string) => k;

const base = {
  isRunMode: false,
  runPlanVersion: null as number | null,
  currentVersion: null as number | null,
  activeVersion: null as number | null,
  pinnedVersion: null as number | null,
  workflowDirty: false,
};

describe('computeTriggerPinState', () => {
  it('hides the affordance until version metadata has loaded', () => {
    const s = computeTriggerPinState({ ...base });
    expect(s.loaded).toBe(false);
    expect(s.shouldRender).toBe(false);
  });

  it('edit mode, nothing pinned yet: offers a fresh pin of the canvas version', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 3, activeVersion: 3 });
    expect(s.targetVersion).toBe(3);
    expect(s.loaded).toBe(true);
    expect(s.isAlreadyPinned).toBe(false);
    expect(s.hasOtherPin).toBe(false);
    expect(s.shouldRender).toBe(true);
  });

  it('edit mode, canvas version is the pinned one: flips to unpin (still rendered)', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 3, activeVersion: 3, pinnedVersion: 3 });
    expect(s.isAlreadyPinned).toBe(true);
    expect(s.hasOtherPin).toBe(false);
    expect(s.shouldRender).toBe(true);
  });

  it('edit mode, a different version is pinned: pin replaces it', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 5, activeVersion: 5, pinnedVersion: 3 });
    expect(s.targetVersion).toBe(5);
    expect(s.isAlreadyPinned).toBe(false);
    expect(s.hasOtherPin).toBe(true);
    expect(s.shouldRender).toBe(true);
  });

  it('edit mode compares against the restored activeVersion, not HEAD currentVersion', () => {
    // Canvas restored to v2 while HEAD is v5 - the pin targets the restored v2.
    const s = computeTriggerPinState({ ...base, currentVersion: 5, activeVersion: 2, pinnedVersion: 2 });
    expect(s.targetVersion).toBe(2);
    expect(s.isAlreadyPinned).toBe(true);
  });

  it('edit mode with unsaved edits: dirty, and still rendered so it can save-then-pin', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 4, activeVersion: 4, pinnedVersion: 2, workflowDirty: true });
    expect(s.effectiveDirty).toBe(true);
    expect(s.shouldRender).toBe(true);
  });

  it('run mode targets the run plan version, not the canvas version', () => {
    const s = computeTriggerPinState({ ...base, isRunMode: true, runPlanVersion: 7, currentVersion: 9, pinnedVersion: 3 });
    expect(s.targetVersion).toBe(7);
    expect(s.hasOtherPin).toBe(true);
    expect(s.shouldRender).toBe(true);
  });

  it('run mode already on the production run: nothing to do, hidden', () => {
    const s = computeTriggerPinState({ ...base, isRunMode: true, runPlanVersion: 3, currentVersion: 3, pinnedVersion: 3 });
    expect(s.isAlreadyPinned).toBe(true);
    expect(s.shouldRender).toBe(false);
  });

  it('run mode without a resolved plan version is hidden', () => {
    const s = computeTriggerPinState({ ...base, isRunMode: true, runPlanVersion: null, currentVersion: 4, pinnedVersion: 2 });
    expect(s.targetVersion).toBeNull();
    expect(s.shouldRender).toBe(false);
  });

  it('run mode ignores the edit dirty flag (run can never save)', () => {
    const s = computeTriggerPinState({ ...base, isRunMode: true, runPlanVersion: 5, currentVersion: 5, pinnedVersion: 3, workflowDirty: true });
    expect(s.effectiveDirty).toBe(false);
  });
});

describe('triggerPinTitle', () => {
  it('dirty edits: prompts to save then pin', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 4, activeVersion: 4, pinnedVersion: 2, workflowDirty: true });
    expect(triggerPinTitle(tKey, s, 2)).toBe('versionHistory.pinSaveTitle');
  });

  it('already pinned: shows the unpin label with the version', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 3, activeVersion: 3, pinnedVersion: 3 });
    expect(triggerPinTitle(tKey, s, 3)).toBe('versionHistory.unpin v3');
  });

  it('another version pinned: shows pin-target plus the currently pinned version', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 5, activeVersion: 5, pinnedVersion: 3 });
    expect(triggerPinTitle(tKey, s, 3)).toBe('versionHistory.pin v5 (versionHistory.pinned: v3)');
  });

  it('fresh pin, nothing pinned yet: shows the pin-target version', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: 4, activeVersion: 4, pinnedVersion: null });
    expect(triggerPinTitle(tKey, s, null)).toBe('versionHistory.pin v4');
  });

  it('no resolved target version: falls back to the bare pin label', () => {
    const s = computeTriggerPinState({ ...base, currentVersion: null, activeVersion: null, pinnedVersion: 2 });
    expect(s.targetVersion).toBeNull();
    expect(triggerPinTitle(tKey, s, 2)).toBe('versionHistory.pin');
  });
});
