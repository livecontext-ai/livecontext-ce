// Optional dedicated uid for the spawned CLI agent (LC-053).
//
// Scrubbing the child's environment (lib/childEnv.mjs) does not stop a child running under the
// bridge's OWN uid from reading /proc/<bridge pid>/environ or the bridge's memory. Running the
// agent under a different uid does. When BRIDGE_CHILD_UID is set the bridge launches every agent
// through `setpriv`:
//
//   setpriv --reuid=U --regid=G --clear-groups --inh-caps=-all --ambient-caps=-all \
//           --bounding-set=-all -- <cli> <args...>
//
// setpriv needs CAP_SETUID/CAP_SETGID (and CAP_SETPCAP for the bounding set), which the systemd
// drop-in grants to the bridge as ambient capabilities (deploy/systemd/lc-bridge-child-uid.conf).
// It clears the inheritable, ambient and bounding sets before exec, so the agent ends up with NO
// capabilities and cannot switch back; NoNewPrivileges=true on the unit keeps setuid binaries
// (sudo) from giving any back. Node's own `spawn({uid, gid})` is NOT used on purpose: it would
// leave the ambient capabilities in place across the exec, i.e. the agent could setuid() back to
// the bridge user.
//
// The per-run directories the agent must read or write (MCP config, attachments, CODEX_HOME,
// the empty cwd) are created by the bridge; grantChildAccess() hands them to the agent's GROUP
// (the bridge user must be a member of that group: `usermod -aG <group> <bridge user>`), so no
// CAP_CHOWN is needed.
//
// Unset (the default, and always on Windows) = the agent runs as the bridge uid, with a WARN at
// boot.

import { chownSync, chmodSync, readdirSync, lstatSync } from 'node:fs';
import { join } from 'node:path';

function parseId(value, name) {
  if (value == null || String(value).trim() === '') return null;
  const n = Number(String(value).trim());
  if (!Number.isInteger(n) || n <= 0) {
    throw new Error(`${name} must be a positive integer uid/gid (got "${value}")`);
  }
  return n;
}

/**
 * @returns {{uid:number, gid:number, home:string|null, setpriv:string}|null}
 */
export function childUserFromEnv(env = process.env, platform = process.platform) {
  const uid = parseId(env.BRIDGE_CHILD_UID, 'BRIDGE_CHILD_UID');
  if (uid == null) return null;
  if (platform === 'win32') {
    throw new Error('BRIDGE_CHILD_UID is not supported on Windows');
  }
  const gid = parseId(env.BRIDGE_CHILD_GID, 'BRIDGE_CHILD_GID') ?? uid;
  return {
    uid,
    gid,
    home: env.BRIDGE_CHILD_HOME || null,
    setpriv: env.BRIDGE_SETPRIV_PATH || '/usr/bin/setpriv',
  };
}

/**
 * Rewrite a spawn plan so the command runs as the child user. Returns the plan unchanged when no
 * child user is configured.
 */
export function wrapSpawnForChildUser({ cmd, args, useShell }, childUser) {
  if (!childUser) return { cmd, args, useShell };
  if (useShell) {
    // A shell-wrapped command line cannot be prefixed safely; this only happens on Windows,
    // where childUserFromEnv already refuses a child uid.
    throw new Error('cannot run a shell-wrapped agent command under BRIDGE_CHILD_UID');
  }
  return {
    cmd: childUser.setpriv,
    args: [
      `--reuid=${childUser.uid}`,
      `--regid=${childUser.gid}`,
      '--clear-groups',
      '--inh-caps=-all',
      '--ambient-caps=-all',
      '--bounding-set=-all',
      '--',
      cmd,
      ...args,
    ],
    useShell: false,
  };
}

/**
 * Give the child user's group read/write access to the per-run directories (recursively), keeping
 * the bridge as owner so it can still clean them up. Throws when the bridge cannot hand them over
 * (typically: the bridge user is not a member of the child's group) - the run must fail rather
 * than fall back to the bridge uid silently.
 */
export function grantChildAccess(paths, childUser, fsImpl = { chownSync, chmodSync, readdirSync, lstatSync }, ownerUid = process.getuid?.()) {
  if (!childUser) return;
  const visit = (p) => {
    const st = fsImpl.lstatSync(p);
    if (st.isSymbolicLink()) return;
    fsImpl.chownSync(p, ownerUid, childUser.gid);
    if (st.isDirectory()) {
      fsImpl.chmodSync(p, 0o2770); // setgid dir: files the agent creates stay in the group
      for (const name of fsImpl.readdirSync(p)) visit(join(p, name));
    } else {
      fsImpl.chmodSync(p, 0o660);
    }
  };
  for (const p of paths) if (p) visit(p);
}

/**
 * Environment overrides for the child user: its own HOME holds its CLI logins (claude reads
 * ~/.claude). An adapter that already pointed HOME at the per-run dir (mistral) is left alone.
 */
export function childUserEnv(childEnv, childUser, inheritedHome = process.env.HOME) {
  if (!childUser) return childEnv;
  const out = { ...childEnv };
  if (childUser.home && out.HOME === inheritedHome) {
    out.HOME = childUser.home;
    delete out.USERPROFILE;
  }
  return out;
}
