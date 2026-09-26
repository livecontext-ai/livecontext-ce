import { describe, it, expect } from 'vitest';
import { SETUP_TASKS, setupProgress, showSetupChecklist } from '../setupChecklist';

describe('setupProgress', () => {
  it('counts only tasks whose evidence says done', () => {
    const progress = setupProgress({ integration: true, chat: false, build: true, channel: false });

    expect(progress.done).toBe(2);
    expect(progress.total).toBe(4);
    expect(progress.allDone).toBe(false);
    expect(progress.known).toBe(true);
    expect(progress.tasks.map((t) => t.done)).toEqual([true, false, true, false]);
  });

  it('is not known while any evidence is still missing, so no premature 0/4 is shown', () => {
    const progress = setupProgress({ integration: true, chat: false });

    expect(progress.known).toBe(false);
    expect(showSetupChecklist(progress)).toBe(false);
  });

  it('keeps the tasks in the order a person meets them, each with where it is done', () => {
    expect(SETUP_TASKS.map((t) => t.id)).toEqual(['integration', 'chat', 'build', 'channel']);
    expect(SETUP_TASKS.every((t) => t.href.startsWith('/app/'))).toBe(true);
  });
});

describe('showSetupChecklist', () => {
  const partial = setupProgress({ integration: true, chat: false, build: false, channel: false });
  const complete = setupProgress({ integration: true, chat: true, build: true, channel: true });

  it('shows while something is left to do', () => {
    expect(showSetupChecklist(partial)).toBe(true);
  });

  it('disappears once everything is done', () => {
    expect(complete.allDone).toBe(true);
    expect(showSetupChecklist(complete)).toBe(false);
  });

  it('has no "hidden by the person" case: while a task is open, it shows', () => {
    expect(showSetupChecklist.length).toBe(1);
    expect(showSetupChecklist(partial)).toBe(true);
  });
});
