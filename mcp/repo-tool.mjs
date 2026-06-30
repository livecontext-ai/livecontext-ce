/**
 * `repo` - a platform MCP tool that gives the bridge agent action-based access to a
 * local source checkout, EXECUTED here on the bridge host where the checkout actually
 * lives (the backend tool services run on other hosts and can't see this filesystem).
 *
 * The agent now ALSO has Claude Code's native file/shell/git tools (Bash/Read/Edit/…),
 * so `repo` is no longer the only way to touch source. It is kept because it (a) runs on
 * the bridge host without the agent needing the checkout path, and (b) returns a
 * structured, reviewable diff / git-status card to the chat (the `__BRIDGE_META__`
 * metadata below) that native Edit/Write do not.
 *
 * Activation: only when AGENT_REPO_PATH points at an existing directory (set by
 * the lc-bridge systemd unit in prod). Unset/absent → tool not advertised and
 * every call returns a clean "not available" message (CE/dev no-op).
 *
 * Safety (defense-in-depth on top of the systemd unit, which already confines
 * writes to the checkout and masks the secret .env files):
 *   - every path is resolved INSIDE AGENT_REPO_PATH ('..'/absolute escapes rejected);
 *   - command output is scrubbed of token-shaped secrets;
 *   - pushing to `main` is refused (also blocked by the repo's pre-push hook + GitHub);
 *   - the agent gets a FIXED, audited action set - never a raw shell.
 *
 * v1 executes actions directly (the bridge is admin-only). The per-action
 * user-approval card is intentionally deferred to v2; the hook is straightforward
 * (return `toolAuthorizationRequired` metadata via the bridge sentinel, gated on
 * the APPROVED_TOOL_ACTIONS env the subprocess already receives).
 */

import { existsSync, statSync, readFileSync, writeFileSync, readdirSync, mkdirSync, realpathSync } from 'fs';
import { resolve, relative, dirname, isAbsolute } from 'path';
import { execFileSync } from 'child_process';
import { createTwoFilesPatch } from 'diff';

const MAX_READ_BYTES = 128 * 1024;     // 128 KB read window (offset-expandable)
const MAX_LIST_ENTRIES = 500;
const MAX_SEARCH_LINES = 200;
const MAX_DIFF_BYTES = 64 * 1024;      // cap a single file's unified diff carried in metadata
const GIT_TIMEOUT_MS = 60_000;
const SENSITIVE_ACTIONS = new Set(['edit', 'write', 'commit', 'push', 'pull', 'checkout']);

export function repoRoot() {
  return process.env.AGENT_REPO_PATH || '';
}

/** The tool is advertised/usable only when the checkout exists on this host. */
export function isRepoEnabled() {
  const r = repoRoot();
  return !!r && existsSync(r);
}

// ─── Tool definition (advertised over MCP, like workflow/table/files) ─────────

export const REPO_TOOL_DEF = {
  name: 'repo',
  description:
    "Read, search, and modify this platform's own source-code checkout, and run git on it. "
    + "Use this ONLY when the user asks you to inspect or change the platform's source code; for "
    + "product tasks (workflows, agents, tables, catalog, …) use the dedicated platform tools. "
    + "Action-based: call repo(action='help') for the full reference. "
    + "Actions: list, read, search, edit, write, git_status, diff, commit, checkout, fetch, push, pull, help. "
    + "All paths are relative to the repository root. You may commit and push to feature branches; "
    + "pushing to the main branch is blocked. If a push is rejected because the branch diverged, move "
    + "your work to a feature branch - checkout(branch='fix/...', create=true) then push - and open a PR.",
  inputSchema: {
    type: 'object',
    properties: {
      action: {
        type: 'string',
        enum: ['list', 'read', 'search', 'edit', 'write', 'git_status', 'diff', 'commit', 'checkout', 'fetch', 'push', 'pull', 'help'],
        description: 'The operation to perform.',
      },
      path: { type: 'string', description: 'Repo-relative file or directory path (for list/read/edit/write/diff).' },
      query: { type: 'string', description: 'Extended-regex pattern (for search).' },
      glob: { type: 'string', description: 'Optional pathspec to limit search/list (e.g. "backend/**").' },
      old_string: { type: 'string', description: 'Exact text to replace (for edit).' },
      new_string: { type: 'string', description: 'Replacement text (for edit).' },
      replace_all: { type: 'boolean', description: 'Replace all occurrences instead of requiring a unique match (edit).' },
      content: { type: 'string', description: 'Full file content (for write).' },
      message: { type: 'string', description: 'Commit message (for commit).' },
      branch: { type: 'string', description: 'Branch name (for push: defaults to current branch, main refused; for checkout: required).' },
      create: { type: 'boolean', description: 'For checkout: create the branch (git checkout -b) instead of switching to an existing one.' },
      rebase: { type: 'boolean', description: 'For pull: integrate remote commits by rebasing (git pull --rebase) instead of fast-forward-only.' },
      offset: { type: 'number', description: 'Byte offset to start reading from (for read).' },
      max_bytes: { type: 'number', description: `Max bytes to read (for read); capped at ${MAX_READ_BYTES}.` },
      staged: { type: 'boolean', description: 'Show staged diff (for diff).' },
    },
    required: ['action'],
  },
};

// ─── MCP result helpers ───────────────────────────────────────────────────────

function textResult(text) {
  return { content: [{ type: 'text', text: String(text) }] };
}
function errorResult(text) {
  return { content: [{ type: 'text', text: String(text) }], isError: true };
}

// ─── Safety helpers ───────────────────────────────────────────────────────────

function escapesRoot(root, abs) {
  const rel = relative(root, abs);
  return rel.split(/[\\/]/).includes('..') || isAbsolute(rel);
}

/** Resolve a repo-relative path and guarantee it stays inside the checkout. */
function safePath(p) {
  const root = repoRoot();
  const abs = resolve(root, p == null || p === '' ? '.' : p);
  if (escapesRoot(root, abs)) {
    throw new Error(`Path "${p}" escapes the repository root and was rejected.`);
  }
  // If the path exists, also resolve symlinks and re-check - a symlink that lives
  // inside the checkout but targets a file outside it must not be a read/edit hole.
  if (existsSync(abs)) {
    if (escapesRoot(realpathSync(root), realpathSync(abs))) {
      throw new Error(`Path "${p}" resolves through a symlink outside the repository and was rejected.`);
    }
  } else {
    // New target (write/edit creating a file): the path itself doesn't exist yet, so
    // realpath it via its nearest EXISTING ancestor. A parent directory that is a
    // symlink pointing out of the checkout must not become a write-outside hole.
    let ancestor = dirname(abs);
    while (!existsSync(ancestor) && dirname(ancestor) !== ancestor) {
      ancestor = dirname(ancestor);
    }
    if (existsSync(ancestor) && escapesRoot(realpathSync(root), realpathSync(ancestor))) {
      throw new Error(`Path "${p}" resolves through a symlink outside the repository and was rejected.`);
    }
  }
  return abs;
}

/** Scrub token-shaped secrets from any text returned to the agent. */
export function maskSecrets(text) {
  if (!text) return text;
  return String(text)
    .replace(/sk_live_[A-Za-z0-9]+/g, 'sk_live_***')
    .replace(/sk_test_[A-Za-z0-9]+/g, 'sk_test_***')
    .replace(/rk_(live|test)_[A-Za-z0-9]+/g, 'rk_$1_***')                    // Stripe restricted key
    .replace(/whsec_[A-Za-z0-9]+/g, 'whsec_***')
    .replace(/\bSG\.[\w-]{16,}\.[\w-]{16,}/g, 'SG.***')                       // SendGrid API key
    .replace(/(github_pat_|ghp_|gho_|ghs_|ghr_)[A-Za-z0-9_]{10,}/g, '$1***')
    .replace(/\b(A[KS]IA)[0-9A-Z]{16}\b/g, '$1***')                         // AWS access-key id
    .replace(/\bxox[baprs]-[A-Za-z0-9-]{8,}/g, 'xox***')                     // Slack bot/user tokens
    .replace(/\bxapp-[A-Za-z0-9-]{8,}/g, 'xapp-***')                         // Slack app tokens
    .replace(/\bAIza[0-9A-Za-z_-]{20,}/g, 'AIza***')                         // Google API key
    .replace(/\bglpat-[A-Za-z0-9_-]{8,}/g, 'glpat-***')                      // GitLab PAT
    // OpenAI / Anthropic bare keys (sk-ant-…, sk-proj-…, sk-…). Hyphen form, distinct
    // from the sk_live_/sk_test_ underscore Stripe keys above. \b avoids "task-".
    .replace(/\bsk-(ant-|proj-)?[A-Za-z0-9_-]{16,}/g, 'sk-$1***')
    .replace(/\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{4,}/g, 'eyJ***.***.***') // JWT
    // Connection-string password: scheme://user:PASS@host (redis/postgres/mongodb/amqp/…).
    // This is THE named threat (the bridge's REDIS_URL holds the Redis password). The
    // user part is optional (redis://:pass@). Bounded {1,256}; a plain http://host or
    // http://host:8080/path has no '@' so it never matches (port is not masked).
    .replace(/\b([a-z][a-z0-9+.\-]*:\/\/[^\s:@/]*:)([^\s@/]{1,256})@/gi, '$1***@')
    // Bare bearer token: `Authorization: Bearer <token>` and inline `bearer <token>`.
    .replace(/\b(Bearer\s+)[A-Za-z0-9._~+/=\-]{8,}/gi, '$1***')
    // AWS secret access key / session token - no distinctive prefix (unlike AKIA), so key
    // the rule on the well-known field name. Safe (specific name + long base64 value), it
    // catches the unquoted/spaced form the generic env rules deliberately avoid.
    .replace(/\b(aws_secret_access_key|aws_session_token)(["']?\s*[:=]\s*["']?)([A-Za-z0-9/+=]{20,})/gi, '$1$2***')
    // PEM private key - body length BOUNDED so a stray BEGIN with no END can't
    // trigger O(n^2) backtracking on a large blob.
    .replace(/(-----BEGIN [A-Z ]*PRIVATE KEY-----)[\s\S]{0,8192}?(-----END [A-Z ]*PRIVATE KEY-----)/g, '$1***$2')
    // QUOTED secret assignment: key: "value" / key='value'. Requiring the quote
    // avoids corrupting ordinary code (e.g. `const apiKey = computeKey(x)`), and the
    // value is quote-bounded so there is no greedy-prefix backtracking.
    .replace(/((?:api[_-]?key|access[_-]?key|client[_-]?secret|secret|token|password|passwd|pwd)["'`]?\s*[:=]\s*)(["'`])[^"'`\r\n]{6,}\2/gi, '$1$2***$2')
    // ENV-style ALL-CAPS unquoted assignment: FOO_SECRET=value / MY_API_TOKEN=value.
    // Require NO spaces around '=' and a paren-free value, so UPPER_SNAKE CODE constants
    // assigned to a call/member (`const SECRET_KEY = readEnv();`, `API_KEY = process.env.API_KEY`)
    // are NOT corrupted - only literal env-style `KEY=opaquevalue` lines are masked.
    .replace(/\b([A-Z][A-Z0-9_]{0,40}(?:SECRET|TOKEN|KEY|PASSWORD|PASSWD)[A-Z0-9_]*=)([^\s"'#()]{8,})/g, '$1***')
    // Lowercase/mixed unquoted assignment: my_secret_token=value. Requires a secret-y
    // word AND no spaces around '=' (so `const apiKey = computeKey(x)` - which has spaces
    // - is never touched), and a value with no quote/space/paren so code calls survive.
    .replace(/\b([a-z][a-z0-9_.\-]*(?:secret|token|passwd|password|apikey|api_key|access_key|client_secret)[a-z0-9_]*=)([^\s"'#;,()]{8,})/gi, '$1***');
}

const SECRET_FILE_RE = /(^|[\\/])(\.env(\..+)?|env\.(conf|txt)|\.git-credentials|\.netrc|\.npmrc|\.pgpass|.*\.pem|.*\.key|.*\.p12|.*\.pfx|.*credentials\.json|.*service[_-]?account.*\.json|id_(rsa|ed25519|ecdsa)([^\\/]*)?)$/i;
function isSecretFile(relPath) {
  return SECRET_FILE_RE.test(relPath);
}

// ─── Diff / status metadata (the frontend renders these as red/green cards) ─────
//
// edit/write/diff attach `{ diff: { files: [...] } }`; git_status attaches
// `{ gitStatus: {...} }`. The metadata rides the result back to the frontend via the
// __BRIDGE_META__ sentinel (appended in agent-cli-server). The `content` text still
// carries a human/agent-readable summary, so the agent loses nothing if it ignores it.

const EXT_LANG = {
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', ts: 'typescript', tsx: 'tsx',
  jsx: 'jsx', java: 'java', py: 'python', rb: 'ruby', go: 'go', rs: 'rust', json: 'json',
  yml: 'yaml', yaml: 'yaml', xml: 'xml', html: 'html', css: 'css', scss: 'scss',
  md: 'markdown', sh: 'bash', sql: 'sql', kt: 'kotlin', php: 'php', c: 'c', h: 'c',
  cpp: 'cpp', cs: 'csharp', toml: 'toml', dockerfile: 'dockerfile',
};
function languageForPath(p) {
  const m = /\.([A-Za-z0-9]+)$/.exec(p || '');
  return m ? (EXT_LANG[m[1].toLowerCase()] || m[1].toLowerCase()) : undefined;
}

/** Count +/- lines in a unified diff body (ignoring the +++/--- file headers). */
function countDiffLines(unified) {
  let additions = 0, deletions = 0;
  for (const line of String(unified).split('\n')) {
    if (line.startsWith('+') && !line.startsWith('+++')) additions++;
    else if (line.startsWith('-') && !line.startsWith('---')) deletions++;
  }
  return { additions, deletions };
}

/** Bound a single file's unified diff so a huge change can't bloat the chat/token cost. */
function capDiff(unified) {
  const buf = Buffer.from(String(unified), 'utf8');
  if (buf.length <= MAX_DIFF_BYTES) return { unifiedDiff: String(unified), truncated: false };
  return { unifiedDiff: buf.subarray(0, MAX_DIFF_BYTES).toString('utf8') + '\n[diff truncated]', truncated: true };
}

/** Structured single-file diff from before/after strings (edit/write). Secrets masked. */
function fileDiffFromStrings(relPath, oldStr, newStr, status) {
  relPath = String(relPath).replace(/\\/g, '/');   // POSIX path (git-style; OS-agnostic on Windows)
  const unified = maskSecrets(
    createTwoFilesPatch(`a/${relPath}`, `b/${relPath}`, oldStr == null ? '' : oldStr, newStr == null ? '' : newStr, '', '', { context: 3 })
  );
  const { additions, deletions } = countDiffLines(unified);
  const capped = capDiff(unified);
  return { path: relPath, status: status || 'modified', additions, deletions, language: languageForPath(relPath), unifiedDiff: capped.unifiedDiff, truncated: capped.truncated };
}

/** Parse a multi-file git unified diff into structured per-file entries (diff action). */
function filesFromGitDiff(gitDiffText) {
  if (!gitDiffText || !gitDiffText.trim()) return [];
  const blocks = gitDiffText.split(/(?=^diff --git )/m).filter((b) => b.startsWith('diff --git'));
  const files = [];
  for (const blk of blocks) {
    const m = blk.match(/^diff --git a\/(.+?) b\/(.+?)$/m);
    const path = m ? m[2] : 'unknown';
    const oldPath = m && m[1] !== m[2] ? m[1] : undefined;
    let status = 'modified';
    if (/^new file mode/m.test(blk)) status = 'added';
    else if (/^deleted file mode/m.test(blk)) status = 'deleted';
    else if (/^rename (from|to)/m.test(blk)) status = 'renamed';
    const masked = maskSecrets(blk);
    const { additions, deletions } = countDiffLines(masked);
    const capped = capDiff(masked);
    files.push({ path, oldPath, status, additions, deletions, language: languageForPath(path), unifiedDiff: capped.unifiedDiff, truncated: capped.truncated });
  }
  return files;
}

/** Parse `git status --porcelain=v1 -b` into a structured status object. */
function parseGitStatus(porcelain) {
  const lines = String(porcelain).split('\n').filter(Boolean);
  let branch = '', ahead = 0, behind = 0;
  const files = [];
  for (const line of lines) {
    if (line.startsWith('##')) {
      const b = line.slice(2).trim();                 // "dev...origin/dev [ahead 1, behind 2]" | "main"
      branch = (b.split(/\.\.\.| /)[0] || '').trim();
      const am = b.match(/ahead (\d+)/); if (am) ahead = Number(am[1]);
      const bm = b.match(/behind (\d+)/); if (bm) behind = Number(bm[1]);
    } else {
      const status = (line.slice(0, 2).trim()) || '??';  // porcelain XY code (M/A/D/R/??/...)
      const rest = line.slice(3);
      // Rename/copy rows read "old -> new": surface the new path, keep the old one.
      const arrow = rest.indexOf(' -> ');
      if (arrow !== -1) files.push({ path: rest.slice(arrow + 4), oldPath: rest.slice(0, arrow), status });
      else files.push({ path: rest, status });
    }
  }
  return { branch, ahead, behind, files };
}

/** Attach metadata to an MCP result (stripped + re-emitted as __BRIDGE_META__ downstream). */
function withMeta(result, metadata) {
  if (metadata && Object.keys(metadata).length > 0) return { ...result, metadata };
  return result;
}

function git(args) {
  return execFileSync('git', ['-C', repoRoot(), ...args], {
    encoding: 'utf8',
    timeout: GIT_TIMEOUT_MS,
    maxBuffer: 16 * 1024 * 1024,
  });
}

function currentBranch() {
  return git(['rev-parse', '--abbrev-ref', 'HEAD']).trim();
}

// ─── Actions ──────────────────────────────────────────────────────────────────

function doList(params) {
  const abs = safePath(params.path);
  const st = statSync(abs);
  if (!st.isDirectory()) return errorResult(`"${params.path}" is not a directory. Use action='read' for files.`);
  const entries = readdirSync(abs, { withFileTypes: true })
    .filter((e) => e.name !== '.git')
    .slice(0, MAX_LIST_ENTRIES)
    .map((e) => {
      const kind = e.isDirectory() ? 'dir' : 'file';
      let size = '';
      if (e.isFile()) { try { size = ` ${statSync(resolve(abs, e.name)).size}b`; } catch { /* ignore */ } }
      return `${kind === 'dir' ? '[d]' : '   '} ${e.name}${size}`;
    });
  return textResult(`${relative(repoRoot(), abs) || '.'} (${entries.length} entries):\n${entries.join('\n')}`);
}

function doRead(params) {
  if (!params.path) return errorResult("read requires 'path'.");
  const rel = relative(repoRoot(), safePath(params.path));
  if (isSecretFile(rel)) return errorResult(`Reading "${rel}" is blocked (secret file).`);
  const abs = safePath(params.path);
  if (!existsSync(abs)) return errorResult(`File not found: ${rel}`);
  const buf = readFileSync(abs);
  const offset = Math.max(0, Number(params.offset) || 0);
  const max = Math.min(MAX_READ_BYTES, Number(params.max_bytes) || MAX_READ_BYTES);
  const slice = buf.subarray(offset, offset + max);
  const truncated = offset + slice.length < buf.length;
  let out = maskSecrets(slice.toString('utf8'));
  if (truncated) {
    out += `\n\n[truncated: showing bytes ${offset}-${offset + slice.length} of ${buf.length}.`
      + ` Re-read with offset=${offset + slice.length} for more.]`;
  }
  return textResult(out);
}

function doSearch(params) {
  if (!params.query) return errorResult("search requires 'query' (extended regex).");
  const args = ['grep', '-nI', '-E', '--untracked', '-e', params.query];
  if (params.glob) args.push('--', params.glob);
  let out;
  try {
    out = git(args);
  } catch (e) {
    // git grep exits 1 when there are no matches - that's not an error.
    if (e.status === 1 && !e.stderr) return textResult('No matches.');
    throw e;
  }
  // Drop matches in secret files - `read`/`edit`/`write` already refuse them, and
  // `--untracked` would otherwise let `search` surface a secret file `read` won't.
  // Each line is `path:lineno:content`; the path ends at the first ':'.
  const inSecretFile = (l) => { const i = l.indexOf(':'); return i > 0 && isSecretFile(l.slice(0, i)); };
  const lines = out.split('\n').filter(Boolean).filter((l) => !inSecretFile(l));
  const shown = lines.slice(0, MAX_SEARCH_LINES);
  let text = maskSecrets(shown.join('\n')) || 'No matches.';
  if (lines.length > shown.length) text += `\n\n[${lines.length - shown.length} more matches - narrow with a glob.]`;
  return textResult(text);
}

function doDiff(params) {
  const args = ['diff'];
  if (params.staged) args.push('--staged');
  if (params.path) args.push('--', relative(repoRoot(), safePath(params.path)));
  let out = git(args);
  // Drop hunks belonging to secret files (parity with read/search) - each file's hunk
  // begins with `diff --git a/<path> b/<path>`. maskSecrets is still the value backstop.
  if (out) {
    out = out.split(/(?=^diff --git )/m)
      .filter((blk) => { const m = blk.match(/^diff --git a\/(.+?) b\//); return !(m && isSecretFile(m[1])); })
      .join('');
  }
  // Structured per-file diffs (for the frontend card) parsed from the full filtered
  // diff before the agent-facing text is size-capped; each file is capped individually.
  const files = filesFromGitDiff(out);
  // Bound the input to maskSecrets - a huge diff stays cheap to scrub.
  const CAP = 200 * 1024;
  let truncated = false;
  if (out.length > CAP) { out = out.slice(0, CAP); truncated = true; }
  const masked = maskSecrets(out) || 'No changes.';
  return withMeta(
    textResult(truncated ? `${masked}\n\n[diff truncated at 200 KB]` : masked),
    files.length ? { diff: { files } } : null
  );
}

function doGitStatus() {
  const raw = git(['status', '--porcelain=v1', '-uall', '-b']);
  const text = maskSecrets(raw.trim()) || 'clean';
  return withMeta(textResult(text), { gitStatus: parseGitStatus(raw) });
}

function doEdit(params) {
  if (!params.path || params.old_string == null || params.new_string == null) {
    return errorResult("edit requires 'path', 'old_string' and 'new_string'.");
  }
  const rel = relative(repoRoot(), safePath(params.path));
  if (isSecretFile(rel)) return errorResult(`Editing "${rel}" is blocked (secret file).`);
  const abs = safePath(params.path);
  if (!existsSync(abs)) return errorResult(`File not found: ${rel}`);
  const orig = readFileSync(abs, 'utf8');
  const count = orig.split(params.old_string).length - 1;
  if (count === 0) return errorResult(`old_string not found in ${rel}.`);
  if (count > 1 && !params.replace_all) {
    return errorResult(`old_string occurs ${count}× in ${rel}. Pass replace_all=true or give a more specific old_string.`);
  }
  const next = params.replace_all
    ? orig.split(params.old_string).join(params.new_string)
    : orig.replace(params.old_string, params.new_string);
  writeFileSync(abs, next, 'utf8');
  const n = params.replace_all ? count : 1;
  return withMeta(
    textResult(`Edited ${rel} (${n} replacement${n > 1 ? 's' : ''}).`),
    { diff: { files: [fileDiffFromStrings(rel, orig, next, 'modified')] } }
  );
}

function doWrite(params) {
  if (!params.path || params.content == null) return errorResult("write requires 'path' and 'content'.");
  const rel = relative(repoRoot(), safePath(params.path));
  if (isSecretFile(rel)) return errorResult(`Writing "${rel}" is blocked (secret file).`);
  const abs = safePath(params.path);
  const existed = existsSync(abs);
  const before = existed ? readFileSync(abs, 'utf8') : '';
  mkdirSync(dirname(abs), { recursive: true });
  const next = String(params.content);
  writeFileSync(abs, next, 'utf8');
  return withMeta(
    textResult(`Wrote ${rel} (${Buffer.byteLength(next)} bytes).`),
    { diff: { files: [fileDiffFromStrings(rel, before, next, existed ? 'modified' : 'added')] } }
  );
}

function doCommit(params) {
  if (!params.message) return errorResult("commit requires 'message'.");
  git(['add', '-A']);
  // Never commit secret files even if they exist in the working tree (read/edit/write
  // already block them; this stops `git add -A` from sneaking them in).
  const staged = git(['diff', '--cached', '--name-only']).split('\n').filter(Boolean);
  const secrets = staged.filter((f) => isSecretFile(f));
  let note = '';
  if (secrets.length) {
    git(['reset', '-q', '--', ...secrets]);
    note = `\n(skipped secret files, NOT committed: ${secrets.join(', ')})`;
  }
  try {
    const out = git(['commit', '-m', params.message]);
    return textResult(maskSecrets(out).trim() + note);
  } catch (e) {
    const msg = (e.stdout || '') + (e.stderr || '');
    // After unstaging secret-only changes the index can be empty; git then says
    // "nothing added to commit" / "no changes added" - treat all as a clean no-op.
    if (/nothing (added )?to commit|no changes added/i.test(msg)) {
      return textResult(`Nothing to commit${note ? ' (only secret files were present, which were skipped).' + note : ' (working tree clean).'}`);
    }
    throw e;
  }
}

/** Strip ref prefixes so `refs/heads/main` / `heads/main` (even repeated) → `main`. */
function normalizeBranch(b) {
  let s = String(b || '').trim();
  let prev;
  do { prev = s; s = s.replace(/^refs\/heads\//i, '').replace(/^heads\//i, ''); } while (s !== prev);
  return s;
}

/** Refuse pushing to a protected/default branch (after normalization, case-insensitive). */
function isProtectedPushTarget(branchRaw) {
  const b = normalizeBranch(branchRaw).toLowerCase();
  if (['main', 'master', 'trunk', 'head'].includes(b)) return true;
  try {
    // e.g. "origin/main" → "main"
    const def = git(['symbolic-ref', '--short', 'refs/remotes/origin/HEAD']).trim().replace(/^origin\//, '').toLowerCase();
    if (def && b === def) return true;
  } catch { /* origin/HEAD may be unset; the literal list above still applies */ }
  return false;
}

/** Refuse a remote-prefixed name (`origin/main`) - pushing it makes a junk refs/heads/origin/*. */
function isRemotePrefixed(branch) {
  const first = branch.split('/')[0].toLowerCase();
  if (first === 'origin' || first === 'upstream') return true; // common remotes, even if not yet configured
  try {
    const remotes = git(['remote']).split('\n').map((r) => r.trim().toLowerCase()).filter(Boolean);
    return remotes.includes(first);
  } catch { return false; }
}

/**
 * Reject refspecs (`src:dst`), the force modifier (`+ref`), flags (`-x`), and anything
 * outside a conservative branch charset (blocks `+`, `:`, `~`, `^`, `@{`, whitespace,
 * globs). `+main` is the dangerous one: `git push origin +main` force-overwrites main;
 * a leading `-` could smuggle a flag into the git argv. Shared by push and checkout.
 */
function isWellFormedBranchArg(raw) {
  return !!raw && !raw.startsWith('-') && !raw.startsWith('+') && !raw.includes(':') && /^[\w./-]+$/.test(raw);
}

function doPush(params) {
  const raw = (params.branch || currentBranch()).trim();
  if (!isWellFormedBranchArg(raw)) {
    return errorResult("Provide a single feature branch name to push - no 'src:dst' refspecs, no '+'/flags, letters/digits/dot/dash/underscore/slash only.");
  }
  // Normalize first so a `refs/heads/…` input can't slip past the protected check.
  const branch = normalizeBranch(raw);
  if (isProtectedPushTarget(branch) || isRemotePrefixed(branch)) {
    return errorResult('Pushing to a protected/default or remote-prefixed branch (main/master/trunk/origin HEAD, origin/*) is not allowed. Push to a feature branch and open a PR.');
  }
  const out = git(['push', '-u', 'origin', branch]);
  return textResult(`Pushed ${branch}.\n${maskSecrets(out).trim()}`);
}

/**
 * Create or switch to a branch - the recovery path when a push is rejected because the
 * shared branch diverged: move the local commits onto a feature branch, then push it
 * (the workflow this tool documents). Local-only, no network, no secret exposure.
 *
 * Same argv-safety guard as push (no flags/refspecs/remote-prefixed names). `create`
 * carries the working tree + any local commits onto the new branch (git checkout -b);
 * switching to an existing branch with conflicting local changes is refused BY git,
 * which we surface verbatim. Creating a protected name (main/master/…) is refused so the
 * agent can't strand commits on a local branch it could never push.
 */
function doCheckout(params) {
  const raw = String(params.branch || '').trim();
  if (!isWellFormedBranchArg(raw)) {
    return errorResult("checkout requires a single branch name - no 'src:dst' refspecs, no '+'/flags, letters/digits/dot/dash/underscore/slash only.");
  }
  const branch = normalizeBranch(raw);
  if (isRemotePrefixed(branch)) {
    return errorResult("Refusing a remote-prefixed name (origin/*). To start from the remote branch, fetch then checkout(branch='<name>', create=true).");
  }
  if (params.create && isProtectedPushTarget(branch)) {
    return errorResult('Refusing to create a protected/default branch name (main/master/trunk/origin HEAD). Use a feature branch like fix/<topic>.');
  }
  // The trailing `--` is load-bearing: it forces git to treat `branch` as a REF, never a
  // pathspec. Without it, `checkout('src/app.js')` (a tracked file - the charset allows
  // '/' and '.') would run `git checkout <pathspec>` and silently REVERT that file to HEAD,
  // discarding the agent's uncommitted edits in this shared checkout while reporting success.
  const args = params.create ? ['checkout', '-b', branch, '--'] : ['checkout', branch, '--'];
  try {
    const out = git(args);
    return textResult(`On branch ${branch} (${params.create ? 'created' : 'switched'}).\n${maskSecrets(out).trim()}`);
  } catch (e) {
    const detail = (e.stderr || e.stdout || e.message || '');
    const hint = params.create
      ? " If it already exists, retry without create=true."
      : " If it doesn't exist yet, retry with create=true.";
    return errorResult(`repo checkout failed: ${maskSecrets(String(detail)).trim()}${hint}`);
  }
}

/** Update remote-tracking refs so git_status's ahead/behind is current. Read-only. */
function doFetch() {
  const out = git(['fetch', '--prune', 'origin']);
  // git fetch reports on stderr; execFileSync only returns stdout, so re-read state.
  const status = git(['status', '-sb']).split('\n')[0] || '';
  return textResult(`Fetched origin.\n${maskSecrets((out + '\n' + status)).trim()}`);
}

/**
 * Integrate the current branch with its upstream. Default is fast-forward-only (safe,
 * never rewrites). `rebase=true` replays local commits on top of the fetched upstream
 * for a diverged branch; on conflict the rebase is ABORTED so the working tree is left
 * exactly as it was, and the agent is told to use the feature-branch + PR path instead.
 */
function doPull(params) {
  if (params.rebase) {
    try {
      const out = git(['pull', '--rebase']);
      return textResult(maskSecrets(out).trim() || 'Rebased onto upstream (already up to date).');
    } catch (e) {
      // Don't leave a half-finished rebase in the shared checkout.
      try { git(['rebase', '--abort']); } catch { /* not mid-rebase */ }
      const detail = (e.stderr || e.stdout || e.message || '');
      return errorResult(
        `repo pull --rebase failed (rebase aborted, working tree restored): ${maskSecrets(String(detail)).trim()}\n`
        + "Tip: move your commits to a feature branch - checkout(branch='fix/...', create=true) then push - and open a PR.");
    }
  }
  // Fast-forward-only: never rewrites history; fails cleanly when the branch diverged.
  const out = git(['pull', '--ff-only']);
  return textResult(maskSecrets(out).trim() || 'Already up to date.');
}

// ─── Help ─────────────────────────────────────────────────────────────────────

const HELP_TEXT = `repo - work on this platform's source checkout (read/search/edit/git).

Actions:
- list(path?)                          list a directory (repo root by default)
- read(path, offset?, max_bytes?)      read a file (128 KB window; secret files blocked)
- search(query, glob?)                 extended-regex search across tracked+untracked files
- diff(path?, staged?)                 show git diff
- git_status                           show working-tree status
- edit(path, old_string, new_string, replace_all?)   exact-match replacement in a file
- write(path, content)                 create/overwrite a file
- commit(message)                      stage everything and commit
- checkout(branch, create?)            switch to a branch, or create one with create=true
- fetch                                update remote-tracking refs (refresh ahead/behind)
- push(branch?)                        push a branch (defaults to current; main is refused)
- pull(rebase?)                        fast-forward pull; rebase=true integrates a diverged branch

All paths are relative to the repo root and cannot escape it. Secret files are blocked and
token-shaped secrets are best-effort masked in output (don't treat masking as a guarantee for
arbitrary secrets). You may push only to feature branches - main is blocked here, by a pre-push
hook, and by branch protection. Use this tool only for source-code work; use the platform tools
for workflows/agents/tables/catalog.

Recovering a rejected push (branch diverged: someone else pushed while you worked):
- Preferred: checkout(branch='fix/<topic>', create=true) carries your commits onto a feature
  branch, then push(branch='fix/<topic>') succeeds - open a PR. Never push the shared branch
  from a diverged checkout.
- Alternatively, fetch then pull(rebase=true) replays your commits onto the upstream; if it hits
  a conflict the rebase is aborted and your tree is left untouched - fall back to a feature branch.`;

// ─── Dispatch ─────────────────────────────────────────────────────────────────

/**
 * Execute a `repo` action. Returns an MCP tool-result object
 * ({ content: [{type:'text', text}], isError? }).
 *
 * @param {object} params  the tool call arguments (must include `action`)
 */
export async function handleRepoTool(params = {}) {
  if (!isRepoEnabled()) {
    return errorResult('Source-code access is not available in this environment (no checkout configured).');
  }
  const action = params.action;
  try {
    switch (action) {
      case 'help': return textResult(HELP_TEXT);
      case 'list': return doList(params);
      case 'read': return doRead(params);
      case 'search': return doSearch(params);
      case 'git_status': return doGitStatus();
      case 'diff': return doDiff(params);
      case 'edit': return doEdit(params);
      case 'write': return doWrite(params);
      case 'commit': return doCommit(params);
      case 'checkout': return doCheckout(params);
      case 'fetch': return doFetch();
      case 'push': return doPush(params);
      case 'pull': return doPull(params);
      default:
        return errorResult(`Unknown action "${action}". Call repo(action='help') for the action list.`);
    }
  } catch (e) {
    const detail = e && (e.stderr || e.stdout || e.message) || String(e);
    return errorResult(`repo ${action} failed: ${maskSecrets(String(detail)).trim()}`);
  }
}

// Exported for tests.
export const _internals = {
  safePath, isSecretFile, SENSITIVE_ACTIONS,
  // Diff/status metadata builders - exported so the fragile parsers can be tested
  // directly against crafted inputs (added/deleted/renamed/multi-file/truncation).
  fileDiffFromStrings, filesFromGitDiff, parseGitStatus, countDiffLines, capDiff,
  MAX_DIFF_BYTES,
};
