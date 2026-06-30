/**
 * Tests for the structured diff/gitStatus METADATA that `repo` actions attach for the
 * frontend (red/green diff cards + status badges). edit/write/diff → { diff: { files } };
 * git_status → { gitStatus }. The agent-facing `content` text is unchanged - these assert
 * the extra `metadata` field handleRepoTool now returns (re-emitted as __BRIDGE_META__ by
 * agent-cli-server's withBridgeMeta, covered in the wiring test).
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { resolve } from 'path';
import { execFileSync } from 'child_process';

const { handleRepoTool, _internals } = await import('../../repo-tool.mjs');

let repo;
const txt = (r) => r.content[0].text;

before(() => {
  repo = mkdtempSync(resolve(tmpdir(), 'repometa-'));
  execFileSync('git', ['-C', repo, 'init', '-q', '-b', 'dev']);
  execFileSync('git', ['-C', repo, 'config', 'user.email', 't@e.com']);
  execFileSync('git', ['-C', repo, 'config', 'user.name', 'T']);
  writeFileSync(resolve(repo, 'app.js'), 'const greeting = "hello world";\n');
  execFileSync('git', ['-C', repo, 'add', 'app.js']);
  execFileSync('git', ['-C', repo, 'commit', '-q', '-m', 'init']);
  process.env.AGENT_REPO_PATH = repo;
});

after(() => {
  delete process.env.AGENT_REPO_PATH;
  try { rmSync(repo, { recursive: true, force: true }); } catch { /* best effort */ }
});

test('edit attaches a structured single-file diff with add/del counts and a unified body', async () => {
  const r = await handleRepoTool({ action: 'edit', path: 'app.js', old_string: 'hello world', new_string: 'hello repo' });
  assert.ok(!r.isError, txt(r));
  assert.match(txt(r), /Edited app\.js/);                       // agent-facing text preserved
  const files = r.metadata?.diff?.files;
  assert.ok(Array.isArray(files) && files.length === 1, 'diff metadata must carry one file');
  const f = files[0];
  assert.equal(f.path, 'app.js');
  assert.equal(f.status, 'modified');
  assert.equal(f.language, 'javascript');
  assert.equal(f.additions, 1);
  assert.equal(f.deletions, 1);
  assert.match(f.unifiedDiff, /\+const greeting = "hello repo"/);
  assert.match(f.unifiedDiff, /-const greeting = "hello world"/);
});

test('write of a NEW file marks status added with all-additions diff', async () => {
  const r = await handleRepoTool({ action: 'write', path: 'src/new.ts', content: 'export const x = 1;\nexport const y = 2;\n' });
  assert.ok(!r.isError, txt(r));
  const f = r.metadata?.diff?.files?.[0];
  assert.ok(f, 'write must attach diff metadata');
  assert.equal(f.path, 'src/new.ts');
  assert.equal(f.status, 'added');
  assert.equal(f.language, 'typescript');
  assert.equal(f.additions, 2);
  assert.equal(f.deletions, 0);
});

test('write of an EXISTING file marks status modified', async () => {
  await handleRepoTool({ action: 'write', path: 'mod.txt', content: 'v1\n' });
  const r = await handleRepoTool({ action: 'write', path: 'mod.txt', content: 'v2\n' });
  assert.equal(r.metadata?.diff?.files?.[0]?.status, 'modified');
});

test('secrets inside an edited file are masked in the diff metadata', async () => {
  const fakeSecret = 'sk_' + 'live_FAKE_meta';
  const r = await handleRepoTool({ action: 'write', path: 'cfg.js', content: `const k = "${fakeSecret}";\n` });
  assert.ok(!r.isError, txt(r));
  assert.doesNotMatch(JSON.stringify(r.metadata), new RegExp(fakeSecret), 'secret must be masked in metadata');
});

test('diff action attaches per-file structured diffs parsed from git', async () => {
  writeFileSync(resolve(repo, 'app.js'), 'const greeting = "hello world";\nconst extra = 1;\n');
  const r = await handleRepoTool({ action: 'diff', path: 'app.js' });
  assert.ok(!r.isError, txt(r));
  const f = r.metadata?.diff?.files?.[0];
  assert.ok(f, 'diff must attach metadata');
  assert.equal(f.path, 'app.js');
  assert.ok(f.additions >= 1, 'should count the added line');
  assert.match(f.unifiedDiff, /diff --git/);
});

test('git_status attaches a structured status (branch + files with porcelain codes)', async () => {
  await handleRepoTool({ action: 'write', path: 'untracked.txt', content: 'u' });
  const r = await handleRepoTool({ action: 'git_status' });
  assert.ok(!r.isError, txt(r));
  const gs = r.metadata?.gitStatus;
  assert.ok(gs, 'git_status must attach gitStatus metadata');
  assert.equal(gs.branch, 'dev');
  assert.equal(typeof gs.ahead, 'number');
  assert.equal(typeof gs.behind, 'number');
  const u = gs.files.find((x) => x.path === 'untracked.txt');
  assert.ok(u, 'untracked file must appear');
  assert.equal(u.status, '??');
});

test('edit on a clean repo path with no metadata side effects does not break (idempotent shape)', async () => {
  // read carries NO diff metadata (only edit/write/diff/git_status do).
  const r = await handleRepoTool({ action: 'read', path: 'app.js' });
  assert.ok(!r.isError, txt(r));
  assert.equal(r.metadata, undefined, 'read must not attach diff/status metadata');
});

// ─── Parser-level tests (crafted inputs via _internals) ─────────────────────────
// filesFromGitDiff is the fragile regex block-splitter; cover every status it must
// classify (added/deleted/renamed) + multi-file splitting + oldPath, which the
// edit/write path (fileDiffFromStrings) can never exercise.

test('filesFromGitDiff classifies a NEW file (new file mode) as added', () => {
  const gd = 'diff --git a/new.ts b/new.ts\nnew file mode 100644\nindex 0000000..abc1234\n--- /dev/null\n+++ b/new.ts\n@@ -0,0 +1,2 @@\n+line1\n+line2\n';
  const files = _internals.filesFromGitDiff(gd);
  assert.equal(files.length, 1);
  assert.equal(files[0].path, 'new.ts');
  assert.equal(files[0].status, 'added');
  assert.equal(files[0].additions, 2);
  assert.equal(files[0].deletions, 0);
});

test('filesFromGitDiff classifies a deleted file', () => {
  const gd = 'diff --git a/gone.ts b/gone.ts\ndeleted file mode 100644\nindex abc1234..0000000\n--- a/gone.ts\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n';
  const files = _internals.filesFromGitDiff(gd);
  assert.equal(files[0].status, 'deleted');
  assert.equal(files[0].deletions, 1);
  assert.equal(files[0].additions, 0);
});

test('filesFromGitDiff classifies a rename and surfaces oldPath', () => {
  const gd = 'diff --git a/old.ts b/new.ts\nsimilarity index 90%\nrename from old.ts\nrename to new.ts\nindex aaa..bbb 100644\n--- a/old.ts\n+++ b/new.ts\n@@ -1 +1 @@\n-old content\n+new content\n';
  const files = _internals.filesFromGitDiff(gd);
  assert.equal(files[0].status, 'renamed');
  assert.equal(files[0].path, 'new.ts');
  assert.equal(files[0].oldPath, 'old.ts');
});

test('filesFromGitDiff splits a multi-file diff into one entry per file', () => {
  const gd =
    'diff --git a/a.ts b/a.ts\nindex 1..2 100644\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-a\n+A\n' +
    'diff --git a/b.ts b/b.ts\nindex 3..4 100644\n--- a/b.ts\n+++ b/b.ts\n@@ -1 +1 @@\n-b\n+B\n';
  const files = _internals.filesFromGitDiff(gd);
  assert.deepEqual(files.map((f) => f.path), ['a.ts', 'b.ts']);
});

test('parseGitStatus reads branch, ahead/behind, codes, and rename oldPath', () => {
  const porcelain = '## dev...origin/dev [ahead 2, behind 3]\n M src/a.ts\n?? b.txt\nR  old.ts -> new.ts\n';
  const gs = _internals.parseGitStatus(porcelain);
  assert.equal(gs.branch, 'dev');
  assert.equal(gs.ahead, 2);
  assert.equal(gs.behind, 3);
  assert.equal(gs.files.find((f) => f.path === 'src/a.ts').status, 'M');
  assert.equal(gs.files.find((f) => f.path === 'b.txt').status, '??');
  const rn = gs.files.find((f) => f.path === 'new.ts');
  assert.equal(rn.status, 'R');
  assert.equal(rn.oldPath, 'old.ts');
});

test('capDiff truncates an oversized diff and flags it', () => {
  const huge = 'x'.repeat(_internals.MAX_DIFF_BYTES + 5000);
  const { unifiedDiff, truncated } = _internals.capDiff(huge);
  assert.equal(truncated, true);
  assert.match(unifiedDiff, /\[diff truncated\]/);
  assert.ok(Buffer.byteLength(unifiedDiff) < Buffer.byteLength(huge));
});

test('fileDiffFromStrings normalizes Windows-style paths to POSIX in metadata', () => {
  const f = _internals.fileDiffFromStrings('src\\nested\\x.ts', 'a\n', 'b\n', 'modified');
  assert.equal(f.path, 'src/nested/x.ts');
  assert.match(f.unifiedDiff, /b\/src\/nested\/x\.ts/);
});
