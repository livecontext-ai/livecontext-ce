#!/usr/bin/env node
// LiveContext CE launcher. Wraps the prebuilt Docker stack behind one npm command.
// The container images are the runtime; this CLI only orchestrates docker compose.
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { existsSync, mkdirSync, copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = join(HERE, '..', 'assets');
const VERSION = JSON.parse(readFileSync(join(HERE, '..', 'package.json'), 'utf8')).version;

// Preserve the legacy launcher override while using Compose's canonical setting.
if (process.env.LIVECONTEXT_PORT) process.env.FRONTEND_PORT = process.env.LIVECONTEXT_PORT;
const HOME = process.env.LIVECONTEXT_HOME || join(process.cwd(), 'livecontext');
const PROJECT = 'livecontext';

const c = {
  b: (s) => `\x1b[1m${s}\x1b[0m`,
  dim: (s) => `\x1b[2m${s}\x1b[0m`,
  green: (s) => `\x1b[32m${s}\x1b[0m`,
  red: (s) => `\x1b[31m${s}\x1b[0m`,
  cyan: (s) => `\x1b[36m${s}\x1b[0m`,
};
const log = (...a) => console.log(...a);
const die = (msg) => { console.error(`\n${c.red('x')} ${msg}\n`); process.exit(1); };

// Docker is an executable on every supported platform. Pass arguments directly
// so installation paths containing spaces or shell characters stay intact.

// Run a command, inheriting stdio (for the long docker commands). Returns exit code.
function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { stdio: 'inherit', ...opts });
    p.on('close', (code) => resolve(code ?? 1));
    p.on('error', () => resolve(127));
  });
}
// Run quietly and capture, for checks.
function capture(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8' });
  return { code: r.status ?? 127, stdout: r.stdout || '', out: (r.stdout || '') + (r.stderr || '') };
}

function checkDocker() {
  const client = capture('docker', ['--version']);
  if (client.code !== 0) {
    die(`Docker is not installed.\n  Install Docker Desktop / Docker Engine, then re-run.\n  ${c.dim('https://docs.docker.com/get-docker/')}`);
  }
  const server = capture('docker', ['version', '--format', '{{.Server.Version}}']);
  if (server.code !== 0) {
    die(`Docker is installed but the daemon is not running.\n  Start Docker Desktop (or the docker service), wait until it is ready, then re-run.`);
  }
  const compose = capture('docker', ['compose', 'version', '--short']);
  if (compose.code !== 0) {
    die(`Docker Compose v2 is required (the \`docker compose\` subcommand).\n  Update Docker Desktop, or install the compose plugin.`);
  }
  return { docker: server.out.trim(), compose: compose.out.trim() };
}

function ensureWorkdir() {
  if (!existsSync(HOME)) mkdirSync(HOME, { recursive: true });
  const dst = join(HOME, 'docker-compose.yml');
  // Always refresh the bundled compose so `up` matches this CLI version.
  copyFileSync(join(ASSETS, 'docker-compose.yml'), dst);
  const envEx = join(HOME, '.env.example');
  if (!existsSync(envEx)) copyFileSync(join(ASSETS, '.env.example'), envEx);
  return dst;
}

function composeArgs(compose, extra) {
  // --project-directory HOME so the compose's relative bind-mount (./catalog-seeds)
  // resolves inside the working dir, where we drop the model seed below.
  return ['compose', '-p', PROJECT, '--project-directory', HOME, '-f', compose, ...extra];
}

// Fetch the cloud's active signed model bundle into <HOME>/catalog-seeds so the
// compose bind-mount delivers the release-day model refresh to a never-linked
// install. Tolerant: on any failure (offline, TLS-intercepting AV, endpoint down)
// the app simply falls back to its built-in classpath models. Non-fatal.
async function fetchSeed() {
  const dir = join(HOME, 'catalog-seeds');
  mkdirSync(dir, { recursive: true });
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 20000);
    const res = await fetch('https://livecontext.ai/api/catalog-bundles/seed', { signal: ctrl.signal });
    clearTimeout(timer);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const bundle = await res.json();
    if (!bundle.signature || !bundle.payloadBase64) throw new Error('not a signed bundle');
    writeFileSync(join(dir, 'model-bundle.json'), JSON.stringify(bundle));
    log(`${c.green('✓')} model catalog ${c.dim(bundle.modelCount + ' models (seed v' + bundle.version + ')')}`);
  } catch (e) {
    log(`${c.dim('· latest model seed unavailable (' + (e.message || e) + '); using the built-in baseline')}`);
  }
}

// Poll the frontend until it answers (first boot runs migrations + tool registration).
function waitForHealth(port, timeoutMs = 240000) {
  const started = Date.now();
  const url = `http://localhost:${port}/`;
  return new Promise((resolve) => {
    const tick = () => {
      const req = http.get(url, (res) => {
        res.resume();
        if (res.statusCode && res.statusCode < 500) return resolve(true);
        retry();
      });
      req.on('error', retry);
      req.setTimeout(4000, () => { req.destroy(); retry(); });
    };
    const retry = () => {
      if (Date.now() - started > timeoutMs) return resolve(false);
      process.stdout.write('.');
      setTimeout(tick, 3000);
    };
    tick();
  });
}

async function up() {
  const info = checkDocker();
  log(`${c.green('✓')} Docker ${c.dim(info.docker)} · Compose ${c.dim(info.compose)}`);
  const compose = ensureWorkdir();
  log(`${c.b('LiveContext')} ${c.dim('v' + VERSION)} : starting the stack in ${c.cyan(HOME)}`);
  const config = capture('docker', composeArgs(compose, ['config', '--format', 'json']));
  if (config.code !== 0) die('Cannot resolve Docker Compose configuration. Check your .env file.');
  let port;
  try {
    port = JSON.parse(config.stdout).services.frontend.ports.find(p => Number(p.target) === 3000)?.published;
    if (!/^\d+$/.test(String(port)) || Number(port) < 1 || Number(port) > 65535) throw new Error();
  } catch {
    die('Cannot determine the frontend port. Set FRONTEND_PORT to a valid port in your .env file.');
  }
  await fetchSeed();
  log(c.dim('Pulling images and starting containers (first run downloads a few GB)...\n'));
  const code = await run('docker', composeArgs(compose, ['up', '-d', '--remove-orphans']));
  if (code !== 0) die('docker compose failed to start the stack. See the output above.');
  process.stdout.write(`\n${c.dim('Waiting for LiveContext to become ready')}`);
  const ok = await waitForHealth(port);
  log('');
  if (!ok) {
    log(`${c.red('!')} The stack started but did not answer on port ${port} in time.`);
    log(`  Check logs with: ${c.b('livecontext logs')}`);
    process.exit(2);
  }
  log(`\n${c.green('✓ LiveContext is running.')}`);
  log(`  Open ${c.b(c.cyan(`http://localhost:${port}`))}  ${c.dim('(the first account you create becomes the admin)')}`);
  log(`\n  ${c.dim('Manage it:')}  livecontext logs   ·   livecontext down   ·   livecontext update`);
  log(`  ${c.dim('Optional config (LLM keys, SMTP, ports):')} edit ${c.dim(join(HOME, '.env.example'))} → .env and re-run.\n`);
}

async function down() {
  checkDocker();
  const compose = ensureWorkdir();
  const code = await run('docker', composeArgs(compose, ['down', '--remove-orphans']));
  process.exit(code);
}
async function logs() {
  checkDocker();
  const compose = ensureWorkdir();
  const code = await run('docker', composeArgs(compose, ['logs', '-f', '--tail', '120']));
  process.exit(code);
}
async function status() {
  checkDocker();
  const compose = ensureWorkdir();
  const code = await run('docker', composeArgs(compose, ['ps']));
  process.exit(code);
}
async function update() {
  checkDocker();
  const compose = ensureWorkdir();
  log(c.dim('Pulling the latest images...'));
  if (await run('docker', composeArgs(compose, ['pull'])) !== 0) die('pull failed.');
  const code = await run('docker', composeArgs(compose, ['up', '-d', '--remove-orphans']));
  process.exit(code);
}
function help() {
  log(`
${c.b('LiveContext')} ${c.dim('v' + VERSION)} : self-hosted AI automation, one command.

  ${c.b('npx livecontext')} ${c.dim('[command]')}

  ${c.cyan('up')}        Start LiveContext (default). Pulls images and boots the stack.
  ${c.cyan('down')}      Stop and remove the containers.
  ${c.cyan('logs')}      Follow the logs.
  ${c.cyan('status')}    Show container status.
  ${c.cyan('update')}    Pull the latest images and restart.
  ${c.cyan('help')}      Show this help.
  ${c.dim('--version')}  Print the CLI version.

  ${c.dim('Docker is required (it is the runtime). This CLI only orchestrates it.')}
  ${c.dim('Configuration lives in ' + HOME + '; application data persists in Docker volumes.')}
`);
}

const cmd = (process.argv[2] || 'up').toLowerCase();
const table = { up, start: up, down, stop: down, logs, status, ps: status, update, upgrade: update, help };
if (cmd === '--version' || cmd === '-v') { log(VERSION); process.exit(0); }
if (cmd === '--help' || cmd === '-h') { help(); process.exit(0); }
const fn = table[cmd];
if (!fn) { console.error(`Unknown command: ${cmd}`); help(); process.exit(1); }
fn();
