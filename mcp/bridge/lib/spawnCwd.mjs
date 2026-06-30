import { existsSync } from 'fs';

/**
 * Resolve the working directory for the spawned agent CLI.
 *
 * Defaults to the source checkout when AGENT_REPO_PATH points at an existing directory,
 * so the agent's native file/shell tools (Bash/Read/Edit/Glob/Grep) operate ON the
 * platform's own code - like a real Claude Code launched inside the repo, instead of from
 * the bridge service dir. Unset or missing (dev/CE) → undefined, so the spawned child
 * inherits the bridge process cwd unchanged (no-op).
 *
 * `exists` is injectable so the branch can be unit-tested without touching the filesystem.
 *
 * @param {string} repoPath  the configured checkout path (process.env.AGENT_REPO_PATH or '')
 * @param {(p: string) => boolean} [exists]  existence predicate (defaults to fs.existsSync)
 * @returns {string|undefined}  the checkout path, or undefined to inherit the bridge cwd
 */
export function resolveAgentCwd(repoPath, exists = existsSync) {
  return repoPath && exists(repoPath) ? repoPath : undefined;
}
