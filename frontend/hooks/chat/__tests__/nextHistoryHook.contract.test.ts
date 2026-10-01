// @vitest-environment node
/**
 * Contract with the INSTALLED Next.js: where its router hooks the History API.
 *
 * useMessageHandlersV2 moves the address of a new conversation mid-stream WITHOUT routing, by
 * calling the browser's own `replaceState` rather than the one Next hooks. That is only safe
 * because Next installs its hook as an OWN property of `window.history`
 * (`window.history.replaceState = function replaceState(...)`), leaving `History.prototype`
 * alone, and the hook checks that at runtime (canReplaceAddressOnly) and falls back to moving
 * the address at the end of the reply. This test makes a Next upgrade that moves the hook fail
 * CI, instead of silently degrading the early move or, worse, routing (and remounting the chat
 * and its live stream) mid-reply.
 *
 * It also pins the Back/Forward behaviour the early move relies on: an entry whose state
 * carries no `__NA` marker makes Next reload the page (which lands on the conversation) rather
 * than restore a route tree that does not match the address.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const appRouterSource = readFileSync(require.resolve('next/dist/client/components/app-router.js'), 'utf8');

describe('installed Next.js history hook', () => {
  it('hooks replaceState on the window.history instance, keeping the browser method as the original', () => {
    expect(appRouterSource).toMatch(/window\.history\.replaceState\s*=\s*function replaceState\s*\(/);
    expect(appRouterSource).toMatch(/window\.history\.replaceState\.bind\(window\.history\)/);
    expect(appRouterSource).not.toMatch(/History\.prototype\.replaceState\s*=/);
  });

  it('reloads the page on Back/Forward to an entry it did not write (no __NA marker)', () => {
    expect(appRouterSource).toMatch(/if\s*\(\s*!event\.state\.__NA\s*\)\s*\{\s*window\.location\.reload\(\)/);
  });
});
