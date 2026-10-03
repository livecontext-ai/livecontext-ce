/**
 * LC-046: nothing installed into the bridge image may float.
 *
 * Pre-fix, mcp/bridge/Dockerfile ran:
 *     RUN npm install -g @anthropic-ai/claude-code
 *     RUN npm install -g @google/gemini-cli
 *     RUN npm install -g @openai/codex
 *     RUN pip3 install --break-system-packages mistral-vibe
 * No version, no lockfile, no integrity check, as root, next to `npm ci` everywhere else. A
 * compromised publish of any of the four landed in the next build with NO git diff, inside the
 * process that receives full agent prompts and holds the provider API keys. Every assertion in
 * the first three tests below fails on that Dockerfile.
 *
 * The rules pinned here:
 *   1. every package installed by name carries an EXACT version (or comes from the committed
 *      manifest, which is itself checked line by line);
 *   2. the Python install is hash-verified against a complete, pinned closure;
 *   3. the base image is pinned by digest, and the runtime does not run as root.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const bridgeDir = resolve(__dirname, '..');

const dockerfile = readFileSync(resolve(bridgeDir, 'Dockerfile'), 'utf8');
const manifest = readFileSync(resolve(bridgeDir, 'agent-cli-versions.txt'), 'utf8');
const requirements = readFileSync(resolve(bridgeDir, 'requirements.txt'), 'utf8');

/** Dockerfile with backslash line-continuations joined, so one RUN reads as one command. */
const flatDockerfile = dockerfile.replace(/\\\r?\n\s*/g, ' ');

const EXACT_VERSION = /^\d+\.\d+\.\d+/;

/**
 * Arguments of every `npm install -g …` / `pip3 install …` invocation, flags removed.
 * Comment lines are skipped: this file's own Dockerfile quotes the pre-fix commands in a
 * comment, and a scanner that cannot tell a comment from an instruction proves nothing.
 */
function installArguments(pattern) {
  const out = [];
  for (const line of flatDockerfile.split('\n')) {
    if (/^\s*#/.test(line)) continue;
    let match;
    const re = new RegExp(pattern.source, 'g');
    while ((match = re.exec(line)) !== null) {
      // Everything up to the next `;`, `&&` or end of line belongs to this invocation.
      const rest = line.slice(match.index + match[0].length).split(/\s*(?:;|&&)\s*/)[0];
      const args = rest.trim().split(/\s+/).filter(Boolean)
        .filter(a => !a.startsWith('-'))       // flags
        .filter(a => a !== '<' && a !== '/dev/null'); // shell redirection of the loop's stdin
      out.push({ command: `${match[0]}${rest}`.trim(), args });
    }
  }
  return out;
}

test('every npm install -g in the bridge Dockerfile names an EXACT version (or installs from the pinned manifest)', () => {
  const invocations = installArguments(/npm install -g\b/);
  assert.ok(invocations.length > 0, 'expected at least one global npm install to check');
  for (const { command, args } of invocations) {
    assert.ok(args.length > 0, `no package argument parsed out of: ${command}`);
    for (const arg of args) {
      // The manifest loop (every manifest line is checked below): by exact spec, or from the
      // tarball whose integrity it just verified; $scripts_flag is the declared script policy.
      if (arg === '"$spec"' || arg === '"/tmp/agent-cli-pkgs/$tgz"' || arg === '$scripts_flag') continue;
      const version = arg.slice(arg.lastIndexOf('@') + 1);
      assert.ok(
        arg.lastIndexOf('@') > 0 && EXACT_VERSION.test(version),
        `"${arg}" is installed without an exact version (${command}). A floating global install `
        + 'puts whatever the registry served that day into the process that holds the provider keys.',
      );
    }
  }
});

test('every pip install in the bridge Dockerfile is hash-verified against the pinned closure', () => {
  const invocations = installArguments(/pip3? install\b/);
  assert.ok(invocations.length > 0, 'expected at least one pip install to check');
  for (const { command, args } of invocations) {
    if (/-r\s/.test(command)) {
      assert.match(command, /--require-hashes/,
        `${command} installs from a requirements file without --require-hashes, so a tampered `
        + 'artifact of the same version would be accepted');
      assert.match(command, /--no-deps/,
        `${command} must pass --no-deps: the requirements file is the COMPLETE closure, and without `
        + 'it pip may resolve an extra distribution that no hash covers');
      continue;
    }
    for (const arg of args) {
      assert.match(arg, /==\d+\.\d+/,
        `"${arg}" is pip-installed without an exact version (${command})`);
    }
  }
});

test('the base image is pinned by digest, not by a moving tag', () => {
  const from = dockerfile.match(/^FROM\s+(\S+)/m);
  assert.ok(from, 'expected a FROM instruction');
  assert.match(from[1], /@sha256:[0-9a-f]{64}$/,
    'FROM must carry a digest; `node:20-alpine` alone rebuilds on a different base every time, '
    + 'which also silently changes the CPython ABI requirements.txt was locked against');
});

test('the bridge image drops root for the runtime', () => {
  const user = dockerfile.match(/^USER\s+(\S+)\s*$/m);
  assert.ok(user, 'the image must declare a USER; running the agent-spawning process as root is '
    + 'the difference between a contained CLI and a root shell on the container');
  assert.notEqual(user[1], 'root');
  assert.ok(dockerfile.indexOf('\nUSER ') > dockerfile.lastIndexOf('RUN npm ci'),
    'USER must come after the installs, which need root; otherwise the build breaks instead of the '
    + 'runtime being hardened');
});

// ─── The manifest itself ──────────────────────────────────────────────────

/** Parsed manifest rows: `<pkg>@<version> <policy>` with `#` comments. */
function manifestRows() {
  return manifest.split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#'))
    .map(line => {
      const [spec, policy, integrity] = line.split(/\s+/);
      return { line, spec, policy, integrity };
    });
}

test('every agent CLI in the manifest is pinned to an exact version and declares an install-script policy', () => {
  const rows = manifestRows();
  assert.ok(rows.length >= 3, 'expected the three Node CLIs the bridge spawns');
  for (const { line, spec, policy } of rows) {
    const at = spec.lastIndexOf('@');
    assert.ok(at > 0, `"${line}": expected <package>@<version>`);
    assert.match(spec.slice(at + 1), EXACT_VERSION,
      `"${line}": the version must be exact - a range or a dist-tag reopens the floating install`);
    assert.ok(['run-scripts', 'ignore-scripts'].includes(policy),
      `"${line}": declare run-scripts or ignore-scripts, so running publisher code at build time `
      + 'is always a decision somebody wrote down');
  }
});

test('the manifest still covers every CLI the bridge spawns, exactly once', () => {
  const names = manifestRows().map(r => r.spec.slice(0, r.spec.lastIndexOf('@')));
  assert.deepEqual([...new Set(names)].sort(), names.sort(), 'a package is listed twice');
  for (const pkg of ['@anthropic-ai/claude-code', '@google/gemini-cli', '@openai/codex']) {
    assert.ok(names.includes(pkg), `${pkg} is spawned by an adapter but is no longer installed`);
  }
});

test('the Dockerfile refuses an unpinned manifest line at BUILD time too, not only in this test', () => {
  // The guard has to live in the image build as well: a manifest edited on a branch that never
  // runs the bridge test suite would otherwise install a floating version.
  assert.match(dockerfile, /is not pinned to an exact version/,
    'the manifest loop must fail the build on a spec without an exact version');
  assert.match(dockerfile, /declares no install-script policy/,
    'the manifest loop must fail the build on a line with no install-script policy');
  assert.match(dockerfile, /npm install -g[^\n]*< \/dev\/null/,
    'the npm call inside the loop must read from /dev/null: the loop\'s stdin is the manifest, and '
    + 'npm would consume the remaining lines and silently install only the first CLI');
});

test('every manifest line declares an integrity column: a sha512 pin or an explicit "-"', () => {
  for (const { line, integrity } of manifestRows()) {
    assert.ok(integrity === '-' || /^sha512-[A-Za-z0-9+/]{86}==$/.test(integrity || ''),
      `"${line}": third column must be "-" or a sha512 dist.integrity`);
  }
});

test('claude-code and codex are pinned to the versions the adapters are verified against', () => {
  const bySpec = Object.fromEntries(manifestRows().map(r => [r.spec.slice(0, r.spec.lastIndexOf('@')), r]));
  const claude = bySpec['@anthropic-ai/claude-code'];
  // 2.1.286: the version prod lane 2 ran the adapter with on 2026-10-02 (bridge agent runs OK, same
  // flags: -p, stream-json, --strict-mcp-config, --mcp-config, --tools, --allowedTools).
  assert.equal(claude.spec, '@anthropic-ai/claude-code@2.1.286');
  assert.match(claude.integrity, /^sha512-/, 'claude-code must carry a verified integrity pin');
  const [major, minor] = bySpec['@openai/codex'].spec.split('@').pop().split('.').map(Number);
  assert.ok(major > 0 || minor >= 154, 'codex usage/billing parsing is verified against >= 0.154.0');
});

test('the Dockerfile verifies a pinned integrity and can require one for every line', () => {
  assert.match(dockerfile, /tarball integrity mismatch/);
  assert.match(dockerfile, /has no integrity pin and BRIDGE_CLI_REQUIRE_INTEGRITY=1/);
  // The default tracks whether the manifest is FULLY pinned: every row here carries a
  // verified sha512 (no "-" placeholder), so the build should enforce integrity by default
  // (ARG BRIDGE_CLI_REQUIRE_INTEGRITY=1) rather than merely allow it. If a future CLI bump
  // lands with its integrity not yet filled in (a "-" row), this assertion flips with it and
  // demands the safer ARG BRIDGE_CLI_REQUIRE_INTEGRITY=0 default until the pin is filled.
  const fullyPinned = manifestRows().every(r => /^sha512-/.test(r.integrity || ''));
  const expectedDefault = fullyPinned ? '1' : '0';
  assert.match(dockerfile, new RegExp(`ARG BRIDGE_CLI_REQUIRE_INTEGRITY=${expectedDefault}\\b`),
    fullyPinned
      ? 'every manifest line is pinned, so the Dockerfile should default to REQUIRING integrity '
        + '(ARG BRIDGE_CLI_REQUIRE_INTEGRITY=1), not merely allow it'
      : 'not every manifest line is pinned yet, so the Dockerfile must default to '
        + 'ARG BRIDGE_CLI_REQUIRE_INTEGRITY=0 (a "-" row would otherwise fail every build)');
});

// ─── The Python closure ───────────────────────────────────────────────────

test('every requirement is pinned with == and carries at least one sha256', () => {
  // Continuations joined, comments dropped: each remaining chunk is one requirement.
  const body = requirements
    .split(/\r?\n/)
    .filter(line => !line.trim().startsWith('#'))
    .join('\n')
    .replace(/\\\r?\n\s*/g, ' ');

  const entries = body.split('\n').map(l => l.trim()).filter(Boolean);
  assert.ok(entries.length > 50,
    `expected the full resolved closure, got ${entries.length} entries - a truncated file would `
    + 'make --require-hashes fail the build, or (worse) hide an unpinned transitive dependency');

  for (const entry of entries) {
    assert.match(entry, /^[A-Za-z0-9._-]+==\S+/, `"${entry}" is not pinned with ==`);
    assert.match(entry, /--hash=sha256:[0-9a-f]{64}/, `"${entry}" carries no sha256`);
  }

  assert.ok(entries.some(e => e.startsWith('mistral-vibe==')),
    'mistral-vibe itself must be in the closure - it is the CLI the bridge spawns');
});
