/**
 * Run async tasks with a concurrency limit, preserving result ordering.
 *
 * Spawns up to `concurrency` workers that pull tasks from a shared queue.
 * Each task runs sequentially within its worker slot so real HTTP concurrency
 * equals `concurrency` (not `concurrency × calls-per-task`).
 *
 * @param tasks    Array of zero-arg async functions to execute
 * @param concurrency  Maximum number of tasks running simultaneously
 * @returns Results in the same order as the input tasks
 */
export function limitConcurrency<T>(tasks: (() => Promise<T>)[], concurrency: number): Promise<T[]> {
  const results: T[] = new Array(tasks.length);
  let next = 0;
  const run = async (): Promise<void> => {
    while (next < tasks.length) {
      const idx = next++;
      results[idx] = await tasks[idx]();
    }
  };
  return Promise.all(Array.from({ length: Math.min(concurrency, tasks.length) }, () => run()))
    .then(() => results);
}
