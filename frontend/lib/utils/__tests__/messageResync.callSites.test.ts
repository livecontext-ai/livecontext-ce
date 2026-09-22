import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The KNOWN surfaces that reload a conversation after a stream reconcile instead of repainting.
 *
 * The helper being correct is not the fix; the fix is that these call sites use it. Each one is
 * a single line (`setMessages(rows)`, or a visible `loadMessages`) in a component no unit test
 * renders, so a revert would leave the whole behavioural suite green while the reader watched
 * the transcript flash at the end of every answer.
 *
 * The registry below is hand-maintained, so this guards REGRESSION, not completeness: a seventh
 * surface added later is simply not scanned until someone lists it here. A test that discovered
 * them would have to recognise "reloads because a stream ended" from the source, which is the
 * judgement call this list exists to record instead.
 *
 * Two shapes are allowed:
 *  - a hook holding its own list reconciles through `reconcileMessageIdentity`;
 *  - a hook using `useMessages` passes `{ silent: true }`, which reconciles internally.
 *
 * The assertions match on the CALL, not on the file containing the word: an import line or a
 * `{ silent: true }` somewhere else in the file must not be enough to pass.
 */
const FRONTEND = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..');

interface ResyncSite {
  /** How this file must reconcile, and how many such call sites it has. */
  shape: 'reconcileMessageIdentity' | 'silent';
  count: number;
}

const RESYNC_CALL_SITES: Record<string, ResyncSite> = {
  // Own their message state, so they reconcile explicitly.
  // ChatPanelContent and useWorkflowChat each reload on reconnect AND after a send.
  'components/app/ChatPanelContent.tsx': { shape: 'reconcileMessageIdentity', count: 2 },
  'hooks/useWorkflowChat.ts': { shape: 'reconcileMessageIdentity', count: 2 },
  'app/workflows/builder/components/TriggerPanel.tsx': { shape: 'reconcileMessageIdentity', count: 1 },
  // Go through useMessages, which reconciles for them once the load is silent.
  // useMessageHandlersV2 loads silently before a send, and twice more in the post-stream
  // reconciliation (the attempt and its one retry).
  'hooks/chat/useMessageHandlersV2.ts': { shape: 'silent', count: 3 },
  'components/chat/ChatPageV2/index.tsx': { shape: 'silent', count: 1 },
  'components/app/ConversationPanelContent.tsx': { shape: 'silent', count: 1 },
};

/** `setMessages(prev => reconcileMessageIdentity(prev, rows))`, in any of its spellings. */
const RECONCILED_SET = /set(Chat)?Messages\(\s*(\w+|\(\w+\))\s*=>\s*reconcileMessageIdentity\(/g;

/** A load that opted out of every visible side effect: `loadX(…, { silent: true })`. */
const SILENT_LOAD = /load(Messages|ConversationAndMessages)\([^;]*?\{\s*silent:\s*true\s*\}/g;

const read = (file: string) => fs.readFileSync(path.join(FRONTEND, file), 'utf8');
const countOf = (source: string, pattern: RegExp) => (source.match(pattern) ?? []).length;

describe('end-of-stream message resync call sites', () => {
  it('every surface that reloads after a stream reconciles instead of replacing', () => {
    const offenders: string[] = [];

    for (const [file, { shape, count }] of Object.entries(RESYNC_CALL_SITES)) {
      const source = read(file);
      const found = countOf(source, shape === 'reconcileMessageIdentity' ? RECONCILED_SET : SILENT_LOAD);
      // EXACT, not a lower bound: a lower bound goes green when one of several sites is
      // reverted, which is exactly the regression this guard exists for. A new legitimate site
      // is meant to come here and be counted.
      if (found !== count) {
        offenders.push(`${file}: expected ${count} ${shape} call site(s), found ${found}`);
      }
    }

    expect(offenders).toEqual([]);
  });

  it('no surface replaces its whole message list straight from a post-stream reload', () => {
    // The signature of the bug: handing the raw fetch result to setState. Every object in that
    // array is new, so React re-renders every bubble. An initial mount load may do it (there is
    // nothing on screen to preserve); a reload triggered by a stream may not.
    //
    // "Triggered by a stream" is decided by the enclosing CALL, not by prose in a comment: a
    // revert would keep its neighbouring comment but could not keep the handler it sits in.
    const RAW_REPLACEMENT = /set(Chat)?Messages\(\s*(Array\.isArray\(\w+\)\s*\?\s*)?\w+(\s*:\s*\[\])?\s*\)/;
    const STREAM_TRIGGERED = /onStreamComplete|checkAndReconnect|triggerSpecific|onExecuteTrigger/;

    const offenders: string[] = [];
    for (const [file, { shape }] of Object.entries(RESYNC_CALL_SITES)) {
      if (shape !== 'reconcileMessageIdentity') continue;
      const lines = read(file).split('\n');
      lines.forEach((line, index) => {
        if (!RAW_REPLACEMENT.test(line)) return;
        const enclosing = lines.slice(Math.max(0, index - 25), index).join('\n');
        if (STREAM_TRIGGERED.test(enclosing)) {
          offenders.push(`${file}:${index + 1}: ${line.trim()}`);
        }
      });
    }

    expect(offenders).toEqual([]);
  });

});
