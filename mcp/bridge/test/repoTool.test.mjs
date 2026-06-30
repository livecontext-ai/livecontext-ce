/**
 * Behavioral tests for the `repo` MCP tool (mcp/repo-tool.mjs) - a platform tool for
 * source-code access that runs on the bridge host and returns reviewable diff cards.
 * (The agent now also has Claude Code's native file/git tools; `repo` is kept alongside
 * them, not instead of them - see repo-tool.mjs header.)
 *
 * Exercises each action against a real throwaway git repo, plus the safety
 * invariants: path-containment, secret masking, secret-file blocking, and the
 * main-push refusal.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, symlinkSync, existsSync, readFileSync } from 'fs';
import { tmpdir } from 'os';
import { resolve } from 'path';
import { execFileSync } from 'child_process';

const { REPO_TOOL_DEF, handleRepoTool, isRepoEnabled, maskSecrets } = await import('../../repo-tool.mjs');

let repo;
const txt = (r) => r.content[0].text;
const fakeSkLivePrefix = 'sk_' + 'live_FAKE';

before(() => {
  repo = mkdtempSync(resolve(tmpdir(), 'repotool-'));
  execFileSync('git', ['-C', repo, 'init', '-q', '-b', 'dev']);
  execFileSync('git', ['-C', repo, 'config', 'user.email', 'test@example.com']);
  execFileSync('git', ['-C', repo, 'config', 'user.name', 'Test']);
  writeFileSync(resolve(repo, 'README.md'), `# hello\nconst token = "${fakeSkLivePrefix}_readme";\n`);
  writeFileSync(resolve(repo, '.env'), `SECRET=${fakeSkLivePrefix}_env\n`); // left UNTRACKED on purpose
  execFileSync('git', ['-C', repo, 'add', 'README.md']);
  execFileSync('git', ['-C', repo, 'commit', '-q', '-m', 'init']);
  process.env.AGENT_REPO_PATH = repo;
});

after(() => {
  delete process.env.AGENT_REPO_PATH;
  try { rmSync(repo, { recursive: true, force: true }); } catch { /* best effort */ }
});

test('isRepoEnabled reflects AGENT_REPO_PATH presence', () => {
  assert.equal(isRepoEnabled(), true);
  const saved = process.env.AGENT_REPO_PATH;
  delete process.env.AGENT_REPO_PATH;
  try { assert.equal(isRepoEnabled(), false); } finally { process.env.AGENT_REPO_PATH = saved; }
});

test('REPO_TOOL_DEF is a valid MCP tool named repo with an action enum', () => {
  assert.equal(REPO_TOOL_DEF.name, 'repo');
  const en = REPO_TOOL_DEF.inputSchema.properties.action.enum;
  for (const a of ['read', 'write', 'edit', 'search', 'commit', 'push', 'help']) assert.ok(en.includes(a), a);
  assert.deepEqual(REPO_TOOL_DEF.inputSchema.required, ['action']);
});

test('list returns directory entries', async () => {
  const r = await handleRepoTool({ action: 'list' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /README\.md/);
});

test('read returns content with token-shaped secrets masked (code kept intact)', async () => {
  const r = await handleRepoTool({ action: 'read', path: 'README.md' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /# hello/);
  assert.match(txt(r), /const token =/, 'surrounding code must NOT be corrupted by masking');
  assert.doesNotMatch(txt(r), new RegExp(`${fakeSkLivePrefix}_readme`), 'the secret value must be masked');
});

test('read of a secret file (.env) is blocked', async () => {
  const r = await handleRepoTool({ action: 'read', path: '.env' });
  assert.ok(r.isError);
  assert.match(txt(r), /blocked|secret/i);
});

test('path traversal outside the repo is rejected', async () => {
  const r = await handleRepoTool({ action: 'read', path: '../../etc/passwd' });
  assert.ok(r.isError);
  assert.match(txt(r), /escape/i);
});

test('write → edit → read round-trips, and git_status sees the change', async () => {
  let r = await handleRepoTool({ action: 'write', path: 'src/a.txt', content: 'hello world' });
  assert.ok(!r.isError, txt(r));
  r = await handleRepoTool({ action: 'edit', path: 'src/a.txt', old_string: 'world', new_string: 'repo' });
  assert.ok(!r.isError, txt(r));
  r = await handleRepoTool({ action: 'read', path: 'src/a.txt' });
  assert.match(txt(r), /hello repo/);
  r = await handleRepoTool({ action: 'git_status' });
  assert.match(txt(r), /a\.txt/);
});

test('edit errors clearly when old_string is absent', async () => {
  const r = await handleRepoTool({ action: 'edit', path: 'README.md', old_string: 'NOTHERE', new_string: 'x' });
  assert.ok(r.isError);
  assert.match(txt(r), /not found/i);
});

test('write/edit on a secret file is blocked', async () => {
  const r = await handleRepoTool({ action: 'write', path: '.env', content: 'x' });
  assert.ok(r.isError);
  assert.match(txt(r), /blocked|secret/i);
});

test('search finds regex matches', async () => {
  const r = await handleRepoTool({ action: 'search', query: 'hello' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /README\.md/);
});

test('commit succeeds', async () => {
  await handleRepoTool({ action: 'write', path: 'b.txt', content: 'b' });
  const r = await handleRepoTool({ action: 'commit', message: 'add b' });
  assert.ok(!r.isError, txt(r));
});

test('push to main is refused (before contacting any remote)', async () => {
  const r = await handleRepoTool({ action: 'push', branch: 'main' });
  assert.ok(r.isError);
  assert.match(txt(r), /main/i);
});

test('unknown action errors with guidance', async () => {
  const r = await handleRepoTool({ action: 'frobnicate' });
  assert.ok(r.isError);
  assert.match(txt(r), /Unknown action/);
});

test('help is available and free', async () => {
  const r = await handleRepoTool({ action: 'help' });
  assert.ok(!r.isError);
  assert.match(txt(r), /Actions:/);
});

test('maskSecrets scrubs common token shapes', () => {
  assert.doesNotMatch(maskSecrets(`x ${fakeSkLivePrefix}_token y`), new RegExp(fakeSkLivePrefix));
  assert.doesNotMatch(maskSecrets('whsec_ABCDEF1234'), /whsec_ABCDEF1234/);
  assert.doesNotMatch(maskSecrets('github_pat_abcdefghij1234567890'), /github_pat_abcdefghij1234567890/);
  assert.doesNotMatch(maskSecrets('password: "hunter2hunter2"'), /hunter2hunter2/);
});

test('returns a clean "not available" error when AGENT_REPO_PATH is unset', async () => {
  const saved = process.env.AGENT_REPO_PATH;
  delete process.env.AGENT_REPO_PATH;
  try {
    const r = await handleRepoTool({ action: 'read', path: 'README.md' });
    assert.ok(r.isError);
    assert.match(txt(r), /not available/i);
  } finally { process.env.AGENT_REPO_PATH = saved; }
});

test('push refspec (feature:main) is refused - no bypass of the main block', async () => {
  const r = await handleRepoTool({ action: 'push', branch: 'feature:main' });
  assert.ok(r.isError);
  assert.match(txt(r), /refspec|not allowed|single feature branch/i);
});

test('push to master/default branch is refused', async () => {
  const r = await handleRepoTool({ action: 'push', branch: 'master' });
  assert.ok(r.isError);
  assert.match(txt(r), /not allowed|master/i);
});

test('diff masks secrets present in tracked source', async () => {
  writeFileSync(resolve(repo, 'README.md'), `# hello\nconst k = "${fakeSkLivePrefix}_diff";\n`);
  const r = await handleRepoTool({ action: 'diff', path: 'README.md' });
  assert.ok(!r.isError, txt(r));
  assert.doesNotMatch(txt(r), new RegExp(`${fakeSkLivePrefix}_diff`), 'secret in diff must be masked');
});

test('commit refuses to stage secret files (git add -A safety)', async () => {
  writeFileSync(resolve(repo, '.env'), `SECRET=${fakeSkLivePrefix}_new\n`); // untracked secret
  await handleRepoTool({ action: 'write', path: 'normal.txt', content: 'ok' });
  const r = await handleRepoTool({ action: 'commit', message: 'add normal' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /skipped secret files/i);
  const tracked = execFileSync('git', ['-C', repo, 'ls-files'], { encoding: 'utf8' });
  assert.doesNotMatch(tracked, /\.env/, '.env must never get committed');
});

test('maskSecrets covers AWS/Slack/Google/GitLab/JWT shapes', () => {
  const awsKey = 'AKIA' + 'IOSFODNN7EXAMPLE';
  assert.doesNotMatch(maskSecrets(awsKey), new RegExp(awsKey));
  assert.doesNotMatch(maskSecrets('xoxb-123456789012-abcdefabcdef'), /xoxb-123456789012-abcdefabcdef/);
  assert.doesNotMatch(maskSecrets('AIzaSyA1234567890abcdefghijklmnopqrstu'), /AIzaSyA1234567890abcdefghijklmnopqrstu/);
  assert.doesNotMatch(maskSecrets('glpat-abcdefghij1234567890'), /glpat-abcdefghij1234567890/);
  assert.doesNotMatch(maskSecrets('eyJhbGciOiJ.eyJzdWIiOi.SflKxwRJSM'), /eyJhbGciOiJ\.eyJzdWIiOi\.SflKxwRJSM/);
});

test('push refs/heads/main and heads/main are refused (ref-prefix bypass closed)', async () => {
  for (const b of ['refs/heads/main', 'heads/main', 'MAIN', 'refs/heads/master', 'refs/heads/refs/heads/main']) {
    const r = await handleRepoTool({ action: 'push', branch: b });
    assert.ok(r.isError, `push to ${b} must be refused`);
    assert.match(txt(r), /not allowed|protected/i);
  }
});

test('masking does NOT corrupt ordinary code identifiers', () => {
  assert.match(maskSecrets('const apiKey = computeKey(foo);'), /computeKey\(foo\)/);
  assert.match(maskSecrets('this.token = response.token;'), /response\.token/);
  assert.match(maskSecrets('let secret = getSecret();'), /getSecret\(\)/);
});

test('read through an in-repo symlink that targets outside the repo is rejected', async (t) => {
  const link = resolve(repo, 'evil-link');
  const target = process.platform === 'win32' ? 'C:/Windows/win.ini' : '/etc/hostname';
  try { symlinkSync(target, link); } catch { t.skip('symlink creation not permitted on this host'); return; }
  try {
    const r = await handleRepoTool({ action: 'read', path: 'evil-link' });
    assert.ok(r.isError, 'symlink read must be rejected');
    assert.match(txt(r), /symlink|escape|reject/i);
  } finally { try { rmSync(link, { force: true }); } catch { /* ignore */ } }
});

test('maskSecrets covers connection-string passwords, Bearer, lowercase env, and sk- keys', () => {
  // Connection-string passwords - the named threat (REDIS_URL holds the Redis password).
  assert.doesNotMatch(maskSecrets('redis://:example-password@203.0.113.10:6379'), /example-password/);
  assert.doesNotMatch(maskSecrets('redis://user:pw12345678@h'), /pw12345678/);
  assert.doesNotMatch(maskSecrets('postgres://u:dbpass1234@h:5432/db'), /dbpass1234/);
  // Bare bearer token.
  assert.doesNotMatch(maskSecrets('Authorization: Bearer abcDEF1234567890tok'), /abcDEF1234567890tok/);
  // Lowercase/mixed unquoted env assignment.
  assert.doesNotMatch(maskSecrets('my_secret_token=abcd1234efgh5678'), /abcd1234efgh5678/);
  assert.doesNotMatch(maskSecrets('client_secret=shh-this-is-secret-9'), /shh-this-is-secret-9/);
  // OpenAI/Anthropic bare keys (hyphen form).
  assert.doesNotMatch(maskSecrets('sk-ant-apiX-example-token'), /example-token/);
  assert.doesNotMatch(maskSecrets('sk-proj-ZyXw9876543210uvtsrq'), /ZyXw9876543210uvtsrq/);
});

test('maskSecrets does NOT corrupt non-secret URLs, ports, or code', () => {
  // A plain URL with a port (no credentials) - the port is not a password.
  assert.match(maskSecrets('fetch("http://service:8080/api/path")'), /service:8080\/api\/path/);
  // A word that merely contains "sk" must not be masked (no `sk-` token boundary).
  assert.match(maskSecrets('const taskRunner = makeTask();'), /taskRunner = makeTask\(\)/);
  // Spaced assignment is code, not env - must survive (the env rule requires no spaces).
  assert.match(maskSecrets('let apiKey = computeKey(opts);'), /computeKey\(opts\)/);
  // A lowercase assignment without a secret-y word must survive.
  assert.match(maskSecrets('monkey=12345678'), /monkey=12345678/);
});

test('search excludes secret files (parity with read)', async () => {
  // A *.pem is a secret file; write() refuses it, so create + commit it directly, then
  // prove `search` won't surface it (filename OR content) even though read/write block it.
  writeFileSync(resolve(repo, 'config.pem'), 'token=SEARCHLEAKVALUE123\n');
  execFileSync('git', ['-C', repo, 'add', 'config.pem']);
  execFileSync('git', ['-C', repo, 'commit', '-q', '-m', 'add pem']);
  const r = await handleRepoTool({ action: 'search', query: 'SEARCHLEAKVALUE123' });
  assert.ok(!r.isError, txt(r));
  assert.doesNotMatch(txt(r), /config\.pem/, 'secret file must be excluded from search results');
  assert.doesNotMatch(txt(r), /SEARCHLEAKVALUE123/, 'secret-file content must not leak via search');
});

test('write whose PARENT dir is a symlink out of the repo is rejected (new-target containment)', async (t) => {
  const linkDir = resolve(repo, 'outdir');
  const target = process.platform === 'win32' ? 'C:/Windows/Temp' : '/tmp';
  try { symlinkSync(target, linkDir, 'dir'); } catch { t.skip('symlink creation not permitted on this host'); return; }
  try {
    const r = await handleRepoTool({ action: 'write', path: 'outdir/escaped.txt', content: 'x' });
    assert.ok(r.isError, 'write through a symlinked parent dir must be rejected');
    assert.match(txt(r), /symlink|escape|reject/i);
  } finally { try { rmSync(linkDir, { force: true }); } catch { /* ignore */ } }
});

// ─── checkout / fetch / pull(rebase) - branch & divergence-recovery actions ─────

test('REPO_TOOL_DEF advertises checkout and fetch with create/rebase params', () => {
  const en = REPO_TOOL_DEF.inputSchema.properties.action.enum;
  for (const a of ['checkout', 'fetch']) assert.ok(en.includes(a), `enum missing ${a}`);
  assert.ok(REPO_TOOL_DEF.inputSchema.properties.create, 'create param missing');
  assert.ok(REPO_TOOL_DEF.inputSchema.properties.rebase, 'rebase param missing');
});

test('checkout create makes a feature branch and carries the local commit, then can switch back', async () => {
  await handleRepoTool({ action: 'write', path: 'recover.txt', content: 'wip' });
  await handleRepoTool({ action: 'commit', message: 'wip to move' });
  let r = await handleRepoTool({ action: 'checkout', branch: 'fix/recover', create: true });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /fix\/recover/);
  assert.equal(execFileSync('git', ['-C', repo, 'rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8' }).trim(), 'fix/recover');
  assert.match(execFileSync('git', ['-C', repo, 'log', '--oneline'], { encoding: 'utf8' }), /wip to move/);
  r = await handleRepoTool({ action: 'checkout', branch: 'dev' });
  assert.ok(!r.isError, txt(r));
  assert.equal(execFileSync('git', ['-C', repo, 'rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8' }).trim(), 'dev');
});

test('checkout to a non-existent branch without create errors with a create=true hint', async () => {
  const r = await handleRepoTool({ action: 'checkout', branch: 'no-such-branch' });
  assert.ok(r.isError, txt(r));
  assert.match(txt(r), /create=true/i);
});

test('checkout rejects refspecs, flags, and remote-prefixed names', async () => {
  for (const b of ['a:b', '-D', '+x', 'origin/dev']) {
    const r = await handleRepoTool({ action: 'checkout', branch: b, create: true });
    assert.ok(r.isError, `checkout ${b} must be refused`);
  }
});

test('checkout create refuses protected branch names (main/master/refs-prefixed)', async () => {
  for (const b of ['main', 'master', 'refs/heads/main']) {
    const r = await handleRepoTool({ action: 'checkout', branch: b, create: true });
    assert.ok(r.isError, `checkout create ${b} must be refused`);
    assert.match(txt(r), /protected|feature branch/i);
  }
});

test('checkout(branch=<tracked file>) is treated as a ref, never a pathspec - uncommitted edits survive', async () => {
  // Regression: the branch charset allows '/' and '.', so a tracked filename passes the
  // guard. Without the `--` ref/pathspec terminator, git would run `checkout <pathspec>`
  // and SILENTLY revert the file to HEAD (discarding the agent's edits) while reporting
  // success. The `--` makes git treat the arg as a ref → clean error, working tree intact.
  await handleRepoTool({ action: 'write', path: 'cko.txt', content: 'committed\n' });
  await handleRepoTool({ action: 'commit', message: 'add cko' });
  await handleRepoTool({ action: 'write', path: 'cko.txt', content: 'UNCOMMITTED EDIT\n' });
  const r = await handleRepoTool({ action: 'checkout', branch: 'cko.txt' });
  assert.ok(r.isError, 'a filename is not a valid ref - must error, not do a pathspec checkout');
  assert.match(readFileSync(resolve(repo, 'cko.txt'), 'utf8'), /UNCOMMITTED EDIT/,
    'the uncommitted working-tree edit must NOT be reverted');
});

// Build a clone whose `dev` has DIVERGED from origin/dev: one commit pushed remote-side
// (via a second clone) and one committed locally. `conflict` makes both edit the same
// file so a rebase collides. Default branch is `main` so `dev` is NOT push-protected.
function makeDivergedClone({ conflict }) {
  const base = mkdtempSync(resolve(tmpdir(), 'repotool-remote-'));
  const bare = resolve(base, 'remote.git');
  const work = resolve(base, 'work');
  const other = resolve(base, 'other');
  const g = (dir, ...a) => execFileSync('git', ['-C', dir, ...a], { encoding: 'utf8' });
  execFileSync('git', ['init', '-q', '--bare', '-b', 'main', bare]);
  execFileSync('git', ['clone', '-q', bare, work]);
  g(work, 'config', 'user.email', 't@e.com'); g(work, 'config', 'user.name', 'T');
  writeFileSync(resolve(work, 'f.txt'), 'base\n');
  g(work, 'add', 'f.txt'); g(work, 'commit', '-q', '-m', 'base');
  g(work, 'push', '-q', '-u', 'origin', 'main');
  g(work, 'checkout', '-q', '-b', 'dev'); g(work, 'push', '-q', '-u', 'origin', 'dev');
  execFileSync('git', ['clone', '-q', bare, other]);
  g(other, 'config', 'user.email', 'o@e.com'); g(other, 'config', 'user.name', 'O');
  g(other, 'checkout', '-q', 'dev');
  writeFileSync(resolve(other, conflict ? 'f.txt' : 'remote-side.txt'), conflict ? 'remote-change\n' : 'remote\n');
  g(other, 'add', '-A'); g(other, 'commit', '-q', '-m', 'remote-side');
  g(other, 'push', '-q', 'origin', 'dev');
  writeFileSync(resolve(work, conflict ? 'f.txt' : 'local-side.txt'), conflict ? 'local-change\n' : 'local\n');
  g(work, 'add', '-A'); g(work, 'commit', '-q', '-m', 'local-side');
  return { base, bare, work, g };
}

test('e2e: a diverged dev push is rejected by git, but checkout -b feature + push succeeds', async () => {
  const saved = process.env.AGENT_REPO_PATH;
  const { base, bare, work } = makeDivergedClone({ conflict: false });
  try {
    process.env.AGENT_REPO_PATH = work;
    // (a) pushing the shared, diverged dev is rejected by GIT (non-ff) - not by the guard.
    let r = await handleRepoTool({ action: 'push', branch: 'dev' });
    assert.ok(r.isError, txt(r));
    assert.match(txt(r), /repo push failed/i);
    assert.doesNotMatch(txt(r), /not allowed|single feature branch/i,
      'dev must pass the guard and fail only at the remote');
    // (b) recovery: move the local commit onto a feature branch and push it.
    r = await handleRepoTool({ action: 'checkout', branch: 'fix/recover', create: true });
    assert.ok(!r.isError, txt(r));
    r = await handleRepoTool({ action: 'push', branch: 'fix/recover' });
    assert.ok(!r.isError, txt(r));
    assert.match(txt(r), /Pushed fix\/recover/);
    assert.match(execFileSync('git', ['-C', bare, 'branch', '--list'], { encoding: 'utf8' }), /fix\/recover/);
  } finally {
    process.env.AGENT_REPO_PATH = saved;
    try { rmSync(base, { recursive: true, force: true }); } catch { /* best effort */ }
  }
});

test('e2e: fetch refreshes ahead/behind, and pull(rebase=true) integrates the diverged branch', async () => {
  const saved = process.env.AGENT_REPO_PATH;
  const { base, work } = makeDivergedClone({ conflict: false });
  try {
    process.env.AGENT_REPO_PATH = work;
    let r = await handleRepoTool({ action: 'fetch' });
    assert.ok(!r.isError, txt(r));
    assert.match(txt(r), /ahead 1, behind 1/);
    r = await handleRepoTool({ action: 'pull', rebase: true });
    assert.ok(!r.isError, txt(r));
    // Both sides' files now coexist; the local commit was replayed on top of remote-side.
    assert.ok(existsSync(resolve(work, 'remote-side.txt')), 'remote commit integrated');
    assert.ok(existsSync(resolve(work, 'local-side.txt')), 'local commit preserved');
  } finally {
    process.env.AGENT_REPO_PATH = saved;
    try { rmSync(base, { recursive: true, force: true }); } catch { /* best effort */ }
  }
});

test('e2e: pull(rebase=true) on a conflict aborts and leaves the working tree untouched', async () => {
  const saved = process.env.AGENT_REPO_PATH;
  const { base, work } = makeDivergedClone({ conflict: true });
  try {
    process.env.AGENT_REPO_PATH = work;
    const r = await handleRepoTool({ action: 'pull', rebase: true });
    assert.ok(r.isError, txt(r));
    assert.match(txt(r), /aborted|feature branch/i);
    // No rebase left in progress, and no conflict markers in the file.
    assert.ok(!existsSync(resolve(work, '.git', 'rebase-merge')), 'rebase-merge state cleared');
    assert.ok(!existsSync(resolve(work, '.git', 'rebase-apply')), 'rebase-apply state cleared');
    assert.doesNotMatch(readFileSync(resolve(work, 'f.txt'), 'utf8'), /<<<<<<</, 'no conflict markers left');
  } finally {
    process.env.AGENT_REPO_PATH = saved;
    try { rmSync(base, { recursive: true, force: true }); } catch { /* best effort */ }
  }
});

test('edit replace_all replaces every occurrence; single-mode multi-match errors', async () => {
  await handleRepoTool({ action: 'write', path: 'multi.txt', content: 'x x x' });
  let r = await handleRepoTool({ action: 'edit', path: 'multi.txt', old_string: 'x', new_string: 'y', replace_all: true });
  assert.ok(!r.isError, txt(r));
  r = await handleRepoTool({ action: 'read', path: 'multi.txt' });
  assert.match(txt(r), /y y y/);
  await handleRepoTool({ action: 'write', path: 'multi2.txt', content: 'a a' });
  r = await handleRepoTool({ action: 'edit', path: 'multi2.txt', old_string: 'a', new_string: 'b' });
  assert.ok(r.isError);
  assert.match(txt(r), /occurs 2|replace_all/i);
});

test('read honors the byte window and reports truncation with a continue offset', async () => {
  const big = 'A'.repeat(300 * 1024); // > MAX_READ_BYTES (128 KB)
  await handleRepoTool({ action: 'write', path: 'big.txt', content: big });
  const r = await handleRepoTool({ action: 'read', path: 'big.txt' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /truncated/i, 'a file larger than the read window must report truncation');
  assert.match(txt(r), /offset=/, 'truncation note must tell the agent how to continue');
});

test('list on a file (not a directory) errors with guidance', async () => {
  const r = await handleRepoTool({ action: 'list', path: 'README.md' });
  assert.ok(r.isError);
  assert.match(txt(r), /not a directory|action='read'/i);
});

test('diff staged shows staged changes', async () => {
  await handleRepoTool({ action: 'write', path: 'staged.txt', content: 'staged content' });
  execFileSync('git', ['-C', repo, 'add', 'staged.txt']);
  const r = await handleRepoTool({ action: 'diff', staged: true });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /staged\.txt/);
});

test('pull surfaces a clean error (no remote) instead of throwing', async () => {
  const r = await handleRepoTool({ action: 'pull' });
  assert.ok(r.isError, txt(r));
  assert.match(txt(r), /repo pull failed/i);
});

test('push +main / +ref (force modifier) is refused before contacting any remote', async () => {
  // `git push origin +main` force-overwrites main - the `+` must be rejected.
  for (const b of ['+main', '+refs/heads/main', '+feature']) {
    const r = await handleRepoTool({ action: 'push', branch: b });
    assert.ok(r.isError, `push ${b} must be refused`);
    assert.match(txt(r), /single feature branch|refspec|not allowed|\+/i);
  }
});

test('push to a remote-prefixed branch (origin/*, upstream/*) is refused', async () => {
  for (const b of ['origin/main', 'upstream/release']) {
    const r = await handleRepoTool({ action: 'push', branch: b });
    assert.ok(r.isError, `push ${b} must be refused`);
    assert.match(txt(r), /not allowed|remote|protected/i);
  }
});

test('read of additional secret-file types is blocked (credentials.json, env.conf, *.p12)', async () => {
  for (const f of ['credentials.json', 'env.conf', 'keystore.p12']) {
    writeFileSync(resolve(repo, f), 'token=BLOCKEDSECRETVAL123\n');
    const r = await handleRepoTool({ action: 'read', path: f });
    assert.ok(r.isError, `${f} read must be blocked`);
    assert.match(txt(r), /blocked|secret/i);
  }
});

test('diff of a secret file returns no content (filtered like read)', async () => {
  writeFileSync(resolve(repo, 'secret2.pem'), 'k=ORIGINALPEM111\n');
  execFileSync('git', ['-C', repo, 'add', 'secret2.pem']);
  execFileSync('git', ['-C', repo, 'commit', '-q', '-m', 'add pem2']);
  writeFileSync(resolve(repo, 'secret2.pem'), 'k=CHANGEDPEM222\n');
  const r = await handleRepoTool({ action: 'diff', path: 'secret2.pem' });
  assert.ok(!r.isError, txt(r));
  assert.doesNotMatch(txt(r), /CHANGEDPEM222/, 'secret-file content must not leak via diff');
});

test('maskSecrets covers lowercase bearer, backtick values, and rk_/SendGrid keys', () => {
  assert.doesNotMatch(maskSecrets('authorization: bearer LOWERcasetoken123'), /LOWERcasetoken123/);
  assert.doesNotMatch(maskSecrets('const apiKey = `bq-secret-value-12345`'), /bq-secret-value-12345/);
  assert.doesNotMatch(maskSecrets('rk_live_ABCDEF1234567890'), /ABCDEF1234567890/);
  assert.doesNotMatch(maskSecrets('SG.aaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbb'), /aaaaaaaaaaaaaaaa\.bbbbbbbbbbbbbbbb/);
});

test('maskSecrets does NOT corrupt UPPER_SNAKE constants assigned to code', () => {
  // The ALL-CAPS env rule must not mangle real source the agent could write back.
  assert.match(maskSecrets('const SECRET_KEY = readEnv();'), /SECRET_KEY = readEnv\(\)/);
  assert.match(maskSecrets('const API_KEY = process.env.API_KEY;'), /process\.env\.API_KEY/);
  assert.match(maskSecrets('let MAX_TOKEN_LEN = computeMax();'), /computeMax\(\)/);
  // …but a literal env-style assignment IS still masked.
  assert.doesNotMatch(maskSecrets('MY_API_TOKEN=abcd1234efgh5678'), /abcd1234efgh5678/);
});

test('maskSecrets masks an unquoted/spaced AWS secret access key', () => {
  assert.doesNotMatch(
    maskSecrets('aws_secret_access_key = wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY'),
    /wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY/);
});

test('push does NOT wrongly reject a legitimate feature branch (guard lets it reach git)', async () => {
  // Create the branch so the only failure is the missing remote, not a missing local ref.
  execFileSync('git', ['-C', repo, 'branch', 'feature/cool-thing']);
  const r = await handleRepoTool({ action: 'push', branch: 'feature/cool-thing' });
  assert.ok(r.isError, txt(r));
  // The GUARD must NOT be what rejected it - its rejections say "not allowed" or
  // "single feature branch". A valid branch must reach git and fail only at the remote.
  assert.doesNotMatch(txt(r), /not allowed|single feature branch/i,
    'a valid feature branch must pass the guard and fail only at the remote');
  assert.match(txt(r), /repo push failed/i);
});
