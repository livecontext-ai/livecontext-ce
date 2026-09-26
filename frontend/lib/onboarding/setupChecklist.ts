/**
 * The post-onboarding setup checklist: what a new account still has to do before it gets the
 * product's value, detected from what already exists rather than from anything the person ticks.
 *
 * <p>Four tasks, in the order a person usually meets them. Each is done the moment its evidence
 * exists (a connection, a conversation, an agent or workflow, a destination that has received a
 * message), so nothing here can be checked without having been done, and nothing done elsewhere
 * (by the assistant, by a teammate in the same workspace) is missed.
 *
 * <p>Kept pure so the rule is testable without React or network: the hook gathers the evidence,
 * this decides what it means.
 */

export type SetupTaskId = 'integration' | 'chat' | 'build' | 'channel';

export interface SetupTask {
  id: SetupTaskId;
  /** Where the task is done, relative to the locale root. */
  href: string;
}

export const SETUP_TASKS: readonly SetupTask[] = [
  { id: 'integration', href: '/app/settings/credentials' },
  { id: 'chat', href: '/app/chat' },
  { id: 'build', href: '/app/agent' },
  { id: 'channel', href: '/app/settings/channels' },
] as const;

/** What was found for each task: true done, false not done, undefined not known yet. */
export type SetupEvidence = Partial<Record<SetupTaskId, boolean>>;

export interface SetupProgress {
  tasks: Array<SetupTask & { done: boolean }>;
  done: number;
  total: number;
  allDone: boolean;
  /** Every task's evidence has been read; before that the counter would be a guess. */
  known: boolean;
}

export function setupProgress(evidence: SetupEvidence): SetupProgress {
  const tasks = SETUP_TASKS.map((task) => ({ ...task, done: evidence[task.id] === true }));
  const done = tasks.filter((task) => task.done).length;
  return {
    tasks,
    done,
    total: tasks.length,
    allDone: done === tasks.length,
    known: SETUP_TASKS.every((task) => evidence[task.id] !== undefined),
  };
}

/**
 * Whether to show the checklist at all.
 *
 * <p>Not before the evidence is known (a "0/4" that becomes "3/4" a second later reads as a
 * regression), and not once everything is done. It cannot be hidden before that, on purpose.
 */
export function showSetupChecklist(progress: SetupProgress): boolean {
  return progress.known && !progress.allDone;
}
